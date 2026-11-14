package com.najah.mealannouncer.data.nightscout.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.data.nightscout.NightscoutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Met un repas en file d'envoi vers Nightscout. */
interface NightscoutScheduler {

    /**
     * Marque le repas « en attente » et programme son envoi.
     *
     * Renvoie `false` si rien n'a été programmé : Nightscout désactivé, incomplet, ou
     * simulation. L'appelant peut alors dire pourquoi sans mentir à l'utilisateur.
     */
    suspend fun enqueue(mealId: String): Boolean
}

@Singleton
class WorkManagerNightscoutScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: NightscoutSettings,
    private val repository: NightscoutRepository,
    private val meals: MealRepository,
) : NightscoutScheduler {

    override suspend fun enqueue(mealId: String): Boolean {
        val config = settings.config.value
        if (!config.enabled || !config.isComplete() || config.simulation) return false
        repository.markPending(mealId)
        val request = OneTimeWorkRequestBuilder<NightscoutSendWorker>()
            .setInputData(workDataOf(NightscoutSendWorker.KEY_MEAL_ID to mealId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // 30 s puis doublement à chaque échec : WorkManager plafonne de lui-même.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(mealId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    private fun uniqueName(mealId: String): String = "$UNIQUE_PREFIX$mealId"

    private companion object {
        const val UNIQUE_PREFIX = "nightscout-send-"
    }
}
