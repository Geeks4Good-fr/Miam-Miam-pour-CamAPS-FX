package com.najah.mealannouncer.integration.glookoexport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.najah.mealannouncer.integration.glooko.CamapsGlookoUploader
import com.najah.mealannouncer.integration.glooko.GlookoCredentials
import com.najah.mealannouncer.integration.glooko.GlookoDestination
import com.najah.mealannouncer.integration.glooko.GlookoImporter
import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Un passage d'import Glooko, en service avant-plan.
 *
 * Le service demande à Glooko de produire un export (si l'accessibilité est active). Le CSV
 * produit par l'application n'existe que le temps de son partage : c'est `GlookoShareActivity`
 * qui reçoit le fichier et fait l'import, dès que l'utilisateur — ou l'automatisation — choisit
 * Meal Announcer dans la feuille de partage. Sans automatisation, un CSV déjà présent dans
 * « Téléchargements » est importé au passage suivant : les deux chemins mènent au même import.
 *
 * Aucun échec n'est maquillé en succès : la notification finale dit ce qui s'est passé.
 */
@AndroidEntryPoint
class GlookoExportService : Service() {

    @Inject
    lateinit var preferences: GlookoPreferences

    @Inject
    lateinit var credentials: GlookoCredentials

    @Inject
    lateinit var importer: GlookoImporter

    @Inject
    lateinit var camapsUploader: CamapsGlookoUploader

    @Inject
    lateinit var csvSource: GlookoCsvSource

    @Inject
    lateinit var automation: GlookoAutomation

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(getString(R.string.glooko_notification_running), ongoing = true))
        // Tout passage compte, quel qu'en soit l'auteur — réveil périodique, appui sur
        // « Synchroniser maintenant » ou repas. Un export manuel posé juste avant un repas ne
        // doit pas en déclencher un second trois minutes plus tard.
        preferences.lastExportAttemptMs = System.currentTimeMillis()
        scope.launch {
            val summary = try {
                runOnce()
            } catch (error: Exception) {
                "Import interrompu : ${error.message ?: error::class.simpleName}"
            }
            preferences.lastSummary = summary
            finish(summary)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runOnce(): String = withContext(Dispatchers.IO) {
        if (!preferences.enabled) return@withContext "Import Glooko désactivé."
        val credentialsOrNull = credentials.read()
            ?: return@withContext "Nightscout n'est pas configuré : adresse ou secret manquant."

        val notes = StringBuilder()
        val triggered = if (GlookoAccessibilityService.isRunning) {
            when (val result = automation.triggerExport()) {
                is GlookoAutomationResult.Triggered -> {
                    notes.append("export déclenché (").append(result.steps.joinToString(" → ")).append(")")
                    true
                }
                is GlookoAutomationResult.Failed -> {
                    notes.append(result.userMessage)
                    false
                }
            }
        } else {
            notes.append("service d'accessibilité non activé")
            false
        }

        // L'export piloté ne laisse aucun fichier dans « Téléchargements » : le CSV n'existe que
        // par la feuille de partage d'Android, et c'est GlookoShareActivity qui fera l'import.
        // Attendre un fichier ici ne ferait que retenir le service une minute pour rien.
        if (triggered) {
            return@withContext notes
                .append(" ; le CSV passe par le partage : l'import se fera à sa réception.")
                .toString()
        }

        val since = maxOf(
            preferences.lastSuccessMs,
            System.currentTimeMillis() - LOOKBACK_MS,
        )
        val file = csvSource.newest(since)
            ?: return@withContext notes.append(" ; aucun export Glooko récent trouvé.").toString()

        val bytes = try {
            csvSource.readBytes(file.uri)
        } catch (error: Exception) {
            return@withContext notes.append(" ; ").append(error.message).toString()
        }
        // Nightscout for CamAPS FX : le fichier part intact, le serveur l'interprète (P7).
        if (preferences.destination == GlookoDestination.CAMAPS_NIGHTSCOUT) {
            val nightscout = credentialsOrNull
                ?: return@withContext notes.append(" ; Nightscout n'est pas configuré.").toString()
            val report = camapsUploader.upload(nightscout, bytes, preferences.dryRun || credentials.isSimulation())
            if (report.ok && !report.dryRun) preferences.lastSuccessMs = System.currentTimeMillis()
            return@withContext notes.append(" ; ").append(file.name).append(" : ").append(report.summary).toString()
        }
        val text = String(bytes, Charsets.UTF_8)

        val options = preferences.planOptions(System.currentTimeMillis()).let {
            if (credentials.isSimulation()) it.copy(dryRun = true) else it
        }
        val report = importer.import(text, credentialsOrNull, options)
        notes.append(" ; ").append(file.name).append(" : ").append(report.summary())
        notes.toString()
    }

    private fun finish(summary: String) {
        notify(notification(summary, ongoing = false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun notification(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.glooko_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setSilent(true)
            .build()

    private fun notify(notification: Notification) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.glooko_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.glooko_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "glooko-export"
        const val NOTIFICATION_ID = 4_201

        /** Fenêtre de recherche d'un export déjà présent, quand l'automatisation n'a pas tourné. */
        const val LOOKBACK_MS = 24 * 3_600_000L
    }
}
