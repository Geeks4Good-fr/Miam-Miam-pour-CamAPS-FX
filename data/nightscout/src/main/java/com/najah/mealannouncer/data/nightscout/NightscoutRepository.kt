package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.core.model.NightscoutStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Résultat du bouton « Tester la connexion ». */
sealed interface NightscoutTestResult {

    data class Success(val server: NightscoutServerInfo) : NightscoutTestResult

    data class Failure(val kind: NightscoutErrorKind) : NightscoutTestResult
}

/** Ce qu'est devenu un envoi de repas. */
sealed interface NightscoutSendOutcome {

    /** Traitement accepté par Nightscout et journal marqué comme envoyé. */
    data object Sent : NightscoutSendOutcome

    /** Simulation : rien n'a quitté le téléphone, le journal n'a pas bougé. */
    data object Simulated : NightscoutSendOutcome

    /** Déjà envoyé auparavant : on ne renvoie pas. */
    data object AlreadySent : NightscoutSendOutcome

    /** Nightscout est désactivé ou incomplet : rien à faire. */
    data object Disabled : NightscoutSendOutcome

    /** Le repas n'existe plus (supprimé entre-temps). */
    data object NoMeal : NightscoutSendOutcome

    data class Failed(val kind: NightscoutErrorKind) : NightscoutSendOutcome
}

/**
 * Envoi des repas vers Nightscout.
 *
 * Le repository ne décide pas des reprises : il dit ce qui s'est passé, et c'est l'appelant
 * (le worker) qui réessaie ou abandonne. Il tient en revanche le journal à jour, pour que
 * l'historique dise toujours la vérité sur ce qui est parti.
 */
@Singleton
class NightscoutRepository @Inject constructor(
    private val settings: NightscoutSettings,
    private val api: NightscoutApi,
    private val meals: MealRepository,
) {

    /** Vérifie adresse et identifiant, sans rien écrire. */
    suspend fun testConnection(): NightscoutTestResult = withContext(Dispatchers.IO) {
        val config = settings.config.value
        val secret = settings.readSecret()
        if (config.baseUrl.isBlank() || secret.isNullOrBlank()) {
            return@withContext NightscoutTestResult.Failure(NightscoutErrorKind.NOT_CONFIGURED)
        }
        val credentials = NightscoutCredentials(config.baseUrl, config.credentialType, secret)
        try {
            NightscoutTestResult.Success(api.status(credentials))
        } catch (error: NightscoutException) {
            NightscoutTestResult.Failure(error.kind)
        }
    }

    /** Envoie un repas, une fois. Ne réessaie pas : voir `NightscoutSendWorker`. */
    suspend fun sendNow(mealId: String): NightscoutSendOutcome = withContext(Dispatchers.IO) {
        val config = settings.config.value
        if (!config.enabled || !config.isComplete()) return@withContext NightscoutSendOutcome.Disabled
        if (meals.nightscoutStatusOf(mealId) == NightscoutStatus.SENT) {
            return@withContext NightscoutSendOutcome.AlreadySent
        }
        val meal = meals.findById(mealId) ?: return@withContext NightscoutSendOutcome.NoMeal
        if (config.simulation) return@withContext NightscoutSendOutcome.Simulated
        val secret = settings.readSecret()
        if (secret.isNullOrBlank()) return@withContext NightscoutSendOutcome.Disabled

        val credentials = NightscoutCredentials(config.baseUrl, config.credentialType, secret)
        try {
            api.postTreatment(credentials, TreatmentPayload.toJson(meal))
            meals.updateNightscoutStatus(mealId, NightscoutStatus.SENT)
            NightscoutSendOutcome.Sent
        } catch (error: NightscoutException) {
            // Le journal reste « en attente » : le worker décidera d'une reprise ou d'un
            // abandon, et c'est lui qui écrira l'échec définitif.
            NightscoutSendOutcome.Failed(error.kind)
        }
    }

    /** Note l'échec définitif d'un envoi. */
    suspend fun markFailed(mealId: String) {
        meals.updateNightscoutStatus(mealId, NightscoutStatus.FAILED)
    }

    /** Rend le repas à l'état « rien demandé » : l'envoi n'aura pas lieu. */
    suspend fun markNotRequested(mealId: String) {
        meals.updateNightscoutStatus(mealId, NightscoutStatus.NOT_REQUESTED)
    }

    /** Met le repas en file d'attente avant de confier l'envoi au worker. */
    suspend fun markPending(mealId: String) {
        meals.updateNightscoutStatus(mealId, NightscoutStatus.PENDING)
    }
}
