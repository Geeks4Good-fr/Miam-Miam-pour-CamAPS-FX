package com.najah.mealannouncer.feature.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Installe tout seul les paquets hors ligne qui manquent (demande du porteur du projet du 23/11/2026 : « il
 * faut que l'utilisateur ait le moins d'actions à faire possible »).
 *
 * Lancé au démarrage de l'appli dès qu'un paquet requis manque (base Open Food Facts, portions en
 * images, dictée, mot-clé). Il attend le **Wi-Fi** (plus de 550 Mo en tout : pas sur le forfait
 * mobile), assez de place et une batterie correcte, puis télécharge un paquet après l'autre avec
 * une notification de progression. Une panne réseau est reprise plus tard par WorkManager.
 * L'assistant d'installation garde ses boutons : les deux chemins partagent le même verrou.
 */
@HiltWorker
class OfflinePacksWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val installer: OfflinePacksInstaller,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val missing = OfflinePack.entries.filter { it.required && !installer.isInstalled(it) }
        if (missing.isEmpty()) return Result.success()
        // Un long téléchargement doit être déclaré à Android, sinon il est coupé au bout de 10 minutes.
        runCatching { setForeground(foreground("Préparation de Miam-Miam…", 0)) }
        var failed = 0
        missing.forEachIndexed { index, pack ->
            var lastPercent = -1
            val result = try {
                installer.downloadAndInstall(pack) { progress ->
                    val (label, percent) = when (progress) {
                        is PackInstallProgress.Downloading ->
                            "Téléchargement" to percentOf(progress.downloadedBytes, progress.totalBytes)
                        is PackInstallProgress.Verifying -> "Vérification" to 100
                        is PackInstallProgress.Extracting ->
                            "Installation" to percentOf(progress.extractedBytes, progress.totalUncompressedBytes)
                    }
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notify("$label : ${pack.title} (${index + 1}/${missing.size})", percent)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            }
            result.onFailure { error ->
                failed++
                Log.w(TAG, "Paquet ${pack.id} non installé : ${error.message}")
            }.onSuccess { Log.i(TAG, "Paquet ${pack.id} installé") }
        }
        return when {
            failed == 0 -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun percentOf(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

    /** Met à jour la notification de progression ; appelé depuis le rappel (non suspendu) de l'installeur. */
    private fun notify(text: String, percent: Int) {
        runCatching { setForegroundAsync(foreground(text, percent)) }
    }

    private fun foreground(text: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Installation des données hors ligne", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Miam-Miam s'installe")
            .setContentText(text)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "MealAnnouncerPacks"
        private const val CHANNEL = "offline-packs"
        private const val NOTIFICATION_ID = 4_711
        private const val MAX_ATTEMPTS = 8
        private const val UNIQUE_NAME = "offline-packs-install"

        /**
         * Programme l'installation si un paquet requis manque ; ne fait rien sinon. Sans effet si
         * elle est déjà programmée (KEEP) : on peut l'appeler à chaque démarrage.
         */
        fun enqueueIfNeeded(context: Context, installer: OfflinePacksInstaller) {
            if (installer.allRequiredInstalled()) return
            val request = OneTimeWorkRequestBuilder<OfflinePacksWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
