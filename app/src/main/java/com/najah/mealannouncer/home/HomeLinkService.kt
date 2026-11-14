package com.najah.mealannouncer.home

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.najah.mealannouncer.MainActivity
import com.najah.mealannouncer.core.datastore.HomeVoiceSettings
import com.najah.mealannouncer.data.homeassistant.HomeLink
import com.najah.mealannouncer.data.homeassistant.HomeLinkStatus
import com.najah.mealannouncer.data.homeassistant.PendingDictation
import com.najah.mealannouncer.data.homeassistant.userMessage
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tient la liaison avec Home Assistant tant que la voix de la maison est activée
 * (`docs/VOIX-MAISON.md`, V1–V2).
 *
 * Service au premier plan (type « usage particulier » : une WebSocket qui attend une phrase
 * dite à une enceinte n'entre dans aucune autre catégorie), avec une notification discrète
 * « Relié à la maison ». Quand une dictée arrive, [HomeLink] a déjà accusé réception ; le service
 * réveille l'écran du repas par une notification plein écran, au-dessus du verrouillage.
 */
@AndroidEntryPoint
class HomeLinkService : Service() {

    @Inject
    lateinit var link: HomeLink

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = statusNotification(link.status.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, notification)
        }
        if (running?.isActive != true) {
            running = scope.launch {
                val watcher = launch {
                    link.status.collect { status -> notify(STATUS_ID, statusNotification(status)) }
                }
                try {
                    link.run(onDictation = ::announceDictation)
                } catch (error: Exception) {
                    Log.w(TAG, "Liaison avec la maison interrompue", error)
                }
                watcher.cancel()
                // Désactivée, jeton refusé ou réglage manquant : le service n'a plus de raison d'être.
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Réveille l'écran du repas : notification plein écran, qui ouvre l'appli au-dessus du verrouillage. */
    private fun announceDictation(pending: PendingDictation) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_HOME_MEAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MEAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Repas dicté à la maison")
            .setContentText(pending.dictation.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(HomeLink.PENDING_MAX_AGE_MS)
            .build()
        notify(MEAL_ID, notification)
        // Filet : si l'écran du repas ne s'est pas ouvert (plein écran refusé, notification non
        // touchée), l'enceinte ne doit pas rester muette après « C'est noté, je regarde ».
        scope.launch {
            delay(NOT_OPENED_DELAY_MS)
            if (link.pending.value === pending) {
                runCatching { link.say(pending.dictation.satellite, NOT_OPENED) }
            }
        }
    }

    private fun statusNotification(status: HomeLinkStatus): Notification {
        val text = when (status) {
            HomeLinkStatus.Off, HomeLinkStatus.Connecting -> "Connexion à la maison…"
            HomeLinkStatus.Connected -> "Relié à la maison : dis « Okay Nabu, je mange… »."
            is HomeLinkStatus.Failed -> "${status.kind.userMessage} " +
                if (status.retrying) "Nouvel essai bientôt." else "Liaison arrêtée."
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Voix de la maison")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun notify(id: Int, notification: Notification) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(id, notification) }
    }

    companion object {
        /** Action de l'intention qui ouvre l'écran du repas pour une dictée de la maison. */
        const val ACTION_HOME_MEAL = "com.najah.mealannouncer.action.HOME_MEAL"

        private const val TAG = "MealAnnouncerHome"
        private const val CHANNEL_STATUS = "home-link-status"
        private const val CHANNEL_MEAL = "home-link-meal"
        private const val STATUS_ID = 4201
        private const val MEAL_ID = 4202
        private const val NOT_OPENED_DELAY_MS = 15_000L
        private const val NOT_OPENED =
            "Je n'arrive pas à ouvrir l'appli sur le téléphone. Touche la notification Miam-Miam."

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Liaison avec la maison", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Notification permanente tant que le téléphone écoute les enceintes." },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MEAL, "Repas dicté à une enceinte", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Ouvre l'écran du repas quand tu le dictes à une enceinte." },
            )
        }

        /** Démarre ou arrête le service selon l'interrupteur ; sans effet si rien ne change. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, HomeLinkService::class.java)
            if (enabled) {
                // Android refuse un service au premier plan lancé depuis l'arrière-plan : on le dit
                // dans le journal plutôt que de planter.
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w(TAG, "Service de la maison non démarré", it) }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/** Ce dont le récepteur de démarrage a besoin, lu dans le graphe Hilt de l'application. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HomeLinkEntryPoint {
    fun homeVoiceSettings(): HomeVoiceSettings
}

/** Relance la liaison après un redémarrage du téléphone, si la voix de la maison est activée. */
class HomeLinkBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = EntryPointAccessors
            .fromApplication(context.applicationContext, HomeLinkEntryPoint::class.java)
            .homeVoiceSettings()
        if (settings.config.value.enabled) HomeLinkService.sync(context, enabled = true)
    }
}
