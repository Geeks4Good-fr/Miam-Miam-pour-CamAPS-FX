package com.najah.mealannouncer.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.najah.mealannouncer.MainActivity
import com.najah.mealannouncer.core.datastore.WakeWordConfig
import com.najah.mealannouncer.core.datastore.WakeWordSettings
import com.najah.mealannouncer.feature.assistant.VoskModelStorage
import com.najah.mealannouncer.feature.assistant.WakeWordDetector
import com.najah.mealannouncer.feature.assistant.WakeWordLink
import com.najah.mealannouncer.feature.assistant.WakeWordStatus
import com.najah.mealannouncer.feature.assistant.WakeWordTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service au premier plan (`foregroundServiceType="microphone"`) qui écoute le mot-clé
 * « miam-miam » hors ligne sur le téléphone (`docs/MIAM-MIAM.md`).
 *
 * Dès que « miam-miam » est détecté (seul ou suivi d'une phrase), il publie le déclenchement dans
 * [WakeWordLink] et réveille [MainActivity] par-dessus l'écran de verrouillage.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var detector: WakeWordDetector

    @Inject
    lateinit var link: WakeWordLink

    @Inject
    lateinit var settings: WakeWordSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.save(WakeWordConfig(enabled = false))
            link.updateStatus(WakeWordStatus.Off)
            stopSelf()
            return START_NOT_STICKY
        }
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Log.w(WakeWordDetector.TAG, "Permission RECORD_AUDIO absente : arrêt de WakeWordService")
            link.updateStatus(WakeWordStatus.MicUnavailable)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = statusNotification(link.status.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(STATUS_ID, notification)
            }
        } catch (error: Exception) {
            Log.w(WakeWordDetector.TAG, "Impossible de passer WakeWordService au premier plan", error)
            link.updateStatus(WakeWordStatus.MicUnavailable)
            stopSelf()
            return START_NOT_STICKY
        }

        if (running?.isActive != true) {
            running = scope.launch {
                val watcher = launch {
                    link.status.collect { status -> notify(STATUS_ID, statusNotification(status)) }
                }
                try {
                    detector.run(onTriggered = ::onWakeWordDetected)
                } catch (error: Exception) {
                    Log.w(WakeWordDetector.TAG, "Veille « miam-miam » interrompue", error)
                }
                watcher.cancel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        link.updateStatus(WakeWordStatus.Off)
        super.onDestroy()
    }

    private fun onWakeWordDetected(trigger: WakeWordTrigger) {
        link.publish(trigger)
        val openIntent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_WAKE_WORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subtitle = when (trigger) {
            WakeWordTrigger.WakeOnly -> "Je t'écoute…"
            is WakeWordTrigger.WithAudio -> "Analyse du repas dicté…"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_TRIGGER)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Miam-miam")
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPending)
            .setFullScreenIntent(openPending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000L)
            .build()
        notify(TRIGGER_ID, notification)
        runCatching { startActivity(openIntent) }
    }

    private fun statusNotification(status: WakeWordStatus): Notification {
        val text = when (status) {
            WakeWordStatus.Off, WakeWordStatus.Listening -> "À l'écoute : dis « Miam-miam » pour dicter un repas."
            WakeWordStatus.Paused -> "En pause pendant la dictée en cours."
            WakeWordStatus.ModelMissing -> "Modèle vocal Vosk français absent."
            WakeWordStatus.MicUnavailable -> "Microphone indisponible."
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Écoute « miam-miam »")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Couper", stop)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun notify(id: Int, notification: Notification) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(id, notification) }
    }

    companion object {
        const val ACTION_WAKE_WORD = "com.najah.mealannouncer.action.WAKE_WORD"
        const val ACTION_STOP = "com.najah.mealannouncer.action.STOP_WAKE_WORD"

        private const val CHANNEL_STATUS = "wake-word-status"
        private const val CHANNEL_TRIGGER = "wake-word-trigger"
        private const val STATUS_ID = 4301
        private const val TRIGGER_ID = 4302

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Veille « miam-miam »", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Notification discrète tant que le téléphone écoute « miam-miam »." },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_TRIGGER, "Réveil « miam-miam »", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Ouvre l'écran du repas quand tu dis « miam-miam »." },
            )
        }

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, WakeWordService::class.java)
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w(WakeWordDetector.TAG, "WakeWordService non démarré", it) }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/**
 * Tuile de raccourcis rapides Android (« Quick Settings ») pour activer ou couper l'écoute
 * « miam-miam » d'un geste (`docs/MIAM-MIAM.md` § 1.3).
 */
@AndroidEntryPoint
class WakeWordTileService : TileService() {

    @Inject
    lateinit var settings: WakeWordSettings

    @Inject
    lateinit var modelStorage: VoskModelStorage

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic || !modelStorage.available) {
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
            return
        }
        val next = !settings.config.value.enabled
        settings.save(WakeWordConfig(enabled = next))
        WakeWordService.sync(this, next)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = settings.config.value.enabled
        tile.label = "Miam-miam"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (enabled) "Actif" else "Coupé"
        }
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WakeWordEntryPoint {
    fun wakeWordSettings(): WakeWordSettings
}

/** Relance la veille « miam-miam » au démarrage du téléphone si elle était activée. */
class WakeWordBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = EntryPointAccessors
            .fromApplication(context.applicationContext, WakeWordEntryPoint::class.java)
            .wakeWordSettings()
        if (settings.config.value.enabled) WakeWordService.sync(context, enabled = true)
    }
}
