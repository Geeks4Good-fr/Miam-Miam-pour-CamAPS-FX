package com.najah.mealannouncer.integration.glookoexport

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le réveil périodique de l'import Glooko.
 *
 * Il ne fait rien lui-même : il réveille [GlookoExportService] et s'arrête. Tout ce qui prend
 * du temps — l'automatisation de l'export, la réception du CSV, les écritures — vit dans le
 * service avant-plan, qui a le droit de durer.
 */
@HiltWorker
class GlookoExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferences: GlookoPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!preferences.enabled) return Result.success()
        return try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, GlookoExportService::class.java),
            )
            Result.success()
        } catch (error: Exception) {
            // Un service avant-plan refusé (batterie, restriction système) mérite un nouvel
            // essai plus tard ; WorkManager applique son propre recul exponentiel.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * Ce qu'a décidé le déclencheur d'export pour un repas annoncé.
 *
 * L'écran de confirmation s'en sert pour ne rien promettre qu'il ne tiendrait pas : il n'affiche
 * une ligne que lorsque quelque chose est réellement programmé.
 */
enum class GlookoMealTrigger {

    /** Un passage est programmé dans quelques minutes. */
    SCHEDULED,

    /** L'import Glooko est désarmé : le repas ne le réveille pas. */
    DISABLED,

    /** Le réglage « à chaque repas » est coupé. */
    OFF,

    /** Un export a déjà été déclenché il y a moins de [GlookoPreferences.DEBOUNCE_MINUTES]. */
    DEBOUNCED,
}

/**
 * Décide ce que fait un repas annoncé.
 *
 * Séparé du planificateur pour être vérifiable sans Android : les trois règles — import armé,
 * réglage gardé, délai minimal entre deux passages — sont faciles à écrire de travers, et un
 * export oublié ou pris deux fois ne se voit pas à l'écran.
 */
internal fun decideMealTrigger(
    enabled: Boolean,
    exportOnMeal: Boolean,
    lastAttemptMs: Long,
    nowMs: Long,
    debounceMs: Long,
): GlookoMealTrigger {
    if (!enabled) return GlookoMealTrigger.DISABLED
    if (!exportOnMeal) return GlookoMealTrigger.OFF
    // Un horodatage dans le futur (horloge remise à l'heure) ne doit pas bloquer l'export
    // pour des heures : seul un délai réellement écoulé compte.
    val elapsed = nowMs - lastAttemptMs
    if (lastAttemptMs > 0L && elapsed >= 0L && elapsed < debounceMs) {
        return GlookoMealTrigger.DEBOUNCED
    }
    return GlookoMealTrigger.SCHEDULED
}

/** Programme ou arrête l'import automatique, et le déclenche à chaque repas annoncé. */
@Singleton
class GlookoScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: GlookoPreferences,
) {

    fun schedule(minutes: Long = preferences.cadenceMinutes) {
        val request = PeriodicWorkRequestBuilder<GlookoExportWorker>(minutes, TimeUnit.MINUTES)
            .build()
        // UPDATE : changer de cadence remplace la planification au lieu d'en empiler une autre.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME)
        // Un export déclenché par un repas serait parti juste derrière : il part aussi.
        workManager.cancelUniqueWork(WORK_NAME_MEAL)
    }

    /** Met la planification en accord avec les réglages courants. */
    fun apply() {
        if (preferences.enabled) schedule() else cancel()
    }

    /**
     * Réveille l'export à l'occasion d'un repas annoncé — le déclencheur normal.
     *
     * Glooko ne publie ses données que toutes les quelques heures, et un repas est le moment
     * où l'on a besoin de son contexte : c'est donc l'occasion la mieux placée. Le passage est
     * différé de [GlookoPreferences.MEAL_DELAY_MINUTES] pour ne pas prendre l'écran pendant la
     * saisie du bolus dans CamAPS FX, et [GlookoPreferences.DEBOUNCE_MINUTES] séparent deux
     * déclenchements — un second export immédiat ne rapporterait rien de neuf.
     *
     * Rien n'est fait si l'import est désarmé ou si le réglage « à chaque repas » est coupé :
     * l'écran de confirmation doit pouvoir dire la vérité sur ce qui va se passer.
     */
    fun onMealAnnounced(nowMs: Long = System.currentTimeMillis()): GlookoMealTrigger {
        val decision = decideMealTrigger(
            enabled = preferences.enabled,
            exportOnMeal = preferences.exportOnMeal,
            lastAttemptMs = preferences.lastExportAttemptMs,
            nowMs = nowMs,
            debounceMs = TimeUnit.MINUTES.toMillis(GlookoPreferences.DEBOUNCE_MINUTES),
        )
        if (decision != GlookoMealTrigger.SCHEDULED) return decision
        // L'empreinte est posée avant la mise en file : si WorkManager refuse, un nouvel
        // appui ne doit pas retenter indéfiniment.
        preferences.lastExportAttemptMs = nowMs
        val request = OneTimeWorkRequestBuilder<GlookoExportWorker>()
            .setInitialDelay(GlookoPreferences.MEAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        // KEEP : un passage déjà en attente suffit, et garder le sien évite d'en empiler deux.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_MEAL,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return GlookoMealTrigger.SCHEDULED
    }

    companion object {
        const val WORK_NAME = "glooko-export"
        const val WORK_NAME_MEAL = "glooko-export-meal"
    }
}
