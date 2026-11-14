package com.najah.mealannouncer

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.najah.mealannouncer.core.database.seed.DatabaseSeeder
import com.najah.mealannouncer.core.datastore.HomeVoiceSettings
import com.najah.mealannouncer.core.datastore.WakeWordSettings
import com.najah.mealannouncer.home.HomeLinkService
import com.najah.mealannouncer.integration.camaps.CamapsAccessibilityService
import com.najah.mealannouncer.wake.WakeWordService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Point d'entrée de l'application, qui installe le graphe d'injection Hilt.
 *
 * Deux travaux y sont faits : remplir la base de démonstration au tout premier lancement, et
 * fournir à WorkManager sa fabrique de workers pour que l'envoi Nightscout puisse recevoir
 * ses dépendances. Les deux sont hors du fil principal : l'utilisateur ouvre souvent
 * l'application debout, juste avant de manger, et l'écran ne doit pas attendre.
 */
@HiltAndroidApp
class MealAnnouncerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var seeder: DatabaseSeeder

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var wakeWordSettings: WakeWordSettings

    @Inject
    lateinit var homeVoiceSettings: HomeVoiceSettings

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Durée de chaque appel Mistral, pour comprendre la lenteur du calcul (09/11/2026).
        com.najah.mealannouncer.data.mistral.MistralTiming.sink = { Log.i("MealAnnouncerTiming", it) }
        // Après un arrêt forcé, CamAPS Guardian remet le service d'accessibilité ; Android relance
        // alors l'appli par lui. On en profite pour rallumer ce qui était activé (09/11/2026).
        CamapsAccessibilityService.onConnected = { context ->
            Log.i(TAG, "Service d'accessibilité relié : relance de la veille et de la liaison maison")
            if (wakeWordSettings.config.value.enabled) WakeWordService.sync(context, enabled = true)
            if (homeVoiceSettings.config.value.enabled) HomeLinkService.sync(context, enabled = true)
        }
        applicationScope.launch {
            try {
                val inserted = seeder.seedIfEmpty()
                if (inserted > 0) {
                    Log.i(TAG, "Base de démonstration : $inserted produits ajoutés.")
                }
            } catch (error: Exception) {
                // Un échec de remplissage ne doit jamais empêcher l'application de démarrer :
                // l'utilisateur peut toujours saisir ses glucides à la main.
                Log.w(TAG, "Remplissage de la base impossible", error)
            }
        }
    }

    private companion object {
        const val TAG = "MealAnnouncer"
    }
}
