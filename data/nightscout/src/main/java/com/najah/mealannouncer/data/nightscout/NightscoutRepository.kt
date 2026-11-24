package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.core.model.NightscoutStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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
 * Ce qu'est devenu l'envoi d'un repas Gemini : [carbsCounted] dit si les glucides ont été écrits
 * (faux quand la synchro Glooko les avait déjà comptés : seule la note est partie).
 */
data class MealEntryResult(
    val outcome: NightscoutSendOutcome,
    val carbsCounted: Boolean = false,
    /** `_id` de l'entrée « Glooko-sync » qui portait déjà ces glucides, s'il y en a une. */
    val glookoMatchId: String? = null,
)

/** Fenêtre de rapprochement d'un repas et de son entrée « Glooko-sync » : ± 30 minutes. */
internal const val MATCH_WINDOW_MS = 30L * 60L * 1000L

/** Écart toléré entre les deux valeurs (arrondis de CamAPS et de Glooko). */
internal const val CARBS_TOLERANCE_G = 1.0

/** La marque des glucides recopiés de Glooko par le glooko-worker du serveur (10/11/2026). */
internal const val GLOOKO_SYNC_ENTERED_BY = "Glooko-sync"

/**
 * L'entrée « Glooko-sync » qui porte déjà ce repas : même valeur à [CARBS_TOLERANCE_G] près, la plus
 * proche dans le temps. `null` s'il n'y en a pas.
 */
internal fun matchingGlookoEntry(found: List<NightscoutTreatmentRef>, carbs: Int, atMs: Long): NightscoutTreatmentRef? =
    found
        .filter { it.enteredBy.equals(GLOOKO_SYNC_ENTERED_BY, ignoreCase = true) }
        .filter { ref -> ref.carbs?.let { kotlin.math.abs(it - carbs) <= CARBS_TOLERANCE_G } == true }
        .filter { kotlin.math.abs(it.createdAtMs - atMs) <= MATCH_WINDOW_MS }
        .minByOrNull { kotlin.math.abs(it.createdAtMs - atMs) }

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

    /**
     * Envoie une note de repas (sans glucides, voir [TreatmentPayload.note]), une fois.
     * Rend [NightscoutSendOutcome.Disabled] si Nightscout n'est pas réglé, [NightscoutSendOutcome.Simulated]
     * en simulation : dans les deux cas rien ne quitte le téléphone.
     */
    suspend fun sendNote(notes: String, createdAt: Instant): NightscoutSendOutcome = withContext(Dispatchers.IO) {
        val config = settings.config.value
        if (!config.enabled || !config.isComplete()) return@withContext NightscoutSendOutcome.Disabled
        if (config.simulation) return@withContext NightscoutSendOutcome.Simulated
        val secret = settings.readSecret()
        if (secret.isNullOrBlank()) return@withContext NightscoutSendOutcome.Disabled
        val credentials = NightscoutCredentials(config.baseUrl, config.credentialType, secret)
        try {
            api.postTreatment(credentials, TreatmentPayload.note(notes, createdAt).toString())
            NightscoutSendOutcome.Sent
        } catch (error: NightscoutException) {
            NightscoutSendOutcome.Failed(error.kind)
        }
    }

    /**
     * Envoie un repas avec ses glucides et sa description (mode Gemini, décision du porteur du projet du
     * 23/11/2026), une fois, **sans doublon** avec la synchro Glooko du serveur.
     *
     * Le cas courant (Miam-Miam écrit avant la synchro) est réglé côté serveur : le glooko-worker
     * n'écrit pas son entrée « Glooko-sync » quand une entrée « Meal Announcer » correspond. Ici, le
     * cas inverse : si une entrée « Glooko-sync » de même valeur (± [CARBS_TOLERANCE_G] g) existe déjà
     * à ± [MATCH_WINDOW_MS], les glucides sont déjà comptés — seule la description part, en note.
     */
    suspend fun sendMealWithCarbs(carbs: Int, notes: String, createdAt: Instant): MealEntryResult = withContext(Dispatchers.IO) {
        val config = settings.config.value
        if (!config.enabled || !config.isComplete()) return@withContext MealEntryResult(NightscoutSendOutcome.Disabled)
        if (config.simulation) return@withContext MealEntryResult(NightscoutSendOutcome.Simulated)
        val secret = settings.readSecret()
        if (secret.isNullOrBlank()) return@withContext MealEntryResult(NightscoutSendOutcome.Disabled)
        val credentials = NightscoutCredentials(config.baseUrl, config.credentialType, secret)
        val at = createdAt.toEpochMilli()
        // Une lecture impossible ne bloque pas le repas : l'anti-doublon du serveur couvre le cas courant.
        val glooko = runCatching {
            api.treatments(credentials, at - MATCH_WINDOW_MS, at + MATCH_WINDOW_MS)
        }.getOrDefault(emptyList()).let { found -> matchingGlookoEntry(found, carbs, at) }
        val payload = if (glooko == null) {
            TreatmentPayload.build(carbs = carbs, notes = notes, createdAt = createdAt)
        } else {
            TreatmentPayload.note("$notes (glucides déjà comptés par la synchro Glooko)", createdAt)
        }
        try {
            api.postTreatment(credentials, payload.toString())
            MealEntryResult(NightscoutSendOutcome.Sent, carbsCounted = glooko == null, glookoMatchId = glooko?.id)
        } catch (error: NightscoutException) {
            MealEntryResult(NightscoutSendOutcome.Failed(error.kind))
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
