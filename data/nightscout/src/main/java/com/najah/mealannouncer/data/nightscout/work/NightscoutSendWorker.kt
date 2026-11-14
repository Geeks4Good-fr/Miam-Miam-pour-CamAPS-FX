package com.najah.mealannouncer.data.nightscout.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.najah.mealannouncer.data.nightscout.NightscoutRepository
import com.najah.mealannouncer.data.nightscout.NightscoutSendOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Envoi d'un repas vers Nightscout, avec reprises.
 *
 * WorkManager s'occupe du reste : il attend le réseau, espace les essais (30 s, 1 min,
 * 2 min…), survit au redémarrage du téléphone. Au-delà de [MAX_ATTEMPTS], l'envoi est
 * déclaré en échec dans le journal plutôt que de tourner indéfiniment.
 */
@HiltWorker
class NightscoutSendWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: NightscoutRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mealId = inputData.getString(KEY_MEAL_ID) ?: return Result.failure()
        return when (val outcome = repository.sendNow(mealId)) {
            NightscoutSendOutcome.Sent,
            NightscoutSendOutcome.AlreadySent,
            NightscoutSendOutcome.NoMeal -> Result.success()

            NightscoutSendOutcome.Disabled,
            NightscoutSendOutcome.Simulated -> {
                // Plus rien à envoyer (désactivé ou simulation entre-temps) : le journal ne
                // doit pas rester « en attente » pour un envoi qui n'aura jamais lieu.
                repository.markNotRequested(mealId)
                Result.success()
            }

            is NightscoutSendOutcome.Failed -> {
                val attemptsLeft = runAttemptCount + 1 < MAX_ATTEMPTS
                if (outcome.kind.isRetryable && attemptsLeft) {
                    Result.retry()
                } else {
                    repository.markFailed(mealId)
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val KEY_MEAL_ID = "mealId"

        /** Nombre total d'essais avant de renoncer. */
        const val MAX_ATTEMPTS = 5
    }
}
