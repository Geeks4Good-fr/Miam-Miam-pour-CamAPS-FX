package com.najah.mealannouncer.integration.glookoexport

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.najah.mealannouncer.integration.glooko.CamapsGlookoUploader
import com.najah.mealannouncer.integration.glooko.GlookoCredentials
import com.najah.mealannouncer.integration.glooko.GlookoDestination
import com.najah.mealannouncer.integration.glooko.GlookoImporter
import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reçoit le CSV Glooko par la feuille de partage d'Android, l'importe, puis se referme.
 *
 * C'est une **activité** et non un récepteur, et ce n'est pas un détail : la feuille de partage
 * ne propose que des activités. Un `BroadcastReceiver` déclaré pour `ACTION_SEND` n'y apparaît
 * jamais — constaté sur l'appareil le 24/10/2026, où « Meal Announcer » restait introuvable
 * dans le sélecteur alors qu'il était correctement déclaré. Sans cette activité, Glooko ne peut
 * pas remettre son fichier à l'application.
 *
 * Aucune interface : la fenêtre est transparente et l'activité se ferme dès l'import terminé, ce
 * qui ramène l'utilisateur à Glooko. Le compte rendu s'affiche dans les réglages, comme pour un
 * import venu de l'automatisation, et la simulation est respectée.
 */
@AndroidEntryPoint
class GlookoShareActivity : ComponentActivity() {

    @Inject
    lateinit var importer: GlookoImporter

    @Inject
    lateinit var camapsUploader: CamapsGlookoUploader

    @Inject
    lateinit var credentials: GlookoCredentials

    @Inject
    lateinit var csvSource: GlookoCsvSource

    @Inject
    lateinit var preferences: GlookoPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aucune vue : la fenêtre ne sert qu'à recevoir le fichier et à garder le processus en
        // vie pendant l'import, qui écrit sur le réseau.
        window?.setDimAmount(0f)

        val uri = streamOf(intent)
        if (uri == null) {
            finish()
            return
        }
        scope.launch {
            importShared(uri)
            runOnUiThread { finish() }
        }
    }

    private suspend fun importShared(uri: Uri) {
        val nightscout = credentials.read()
        if (nightscout == null) {
            preferences.lastSummary = "Fichier reçu, mais Nightscout n'est pas configuré."
            return
        }
        val bytes = try {
            csvSource.readBytes(uri)
        } catch (error: Exception) {
            preferences.lastSummary = "Fichier partagé illisible : ${error.message}"
            return
        }
        val text = String(bytes, Charsets.UTF_8)
        // L'activité est proposée pour tout partage (Glooko n'annonce pas de type CSV) : un
        // fichier qui n'a pas l'allure d'un export Glooko est ignoré sans rien écrire, plutôt
        // que de laisser un « illisible » dans les réglages à cause d'une photo partagée.
        if (!looksLikeGlooko(text)) return
        // Nightscout for CamAPS FX : le fichier part intact, le serveur l'interprète (P7).
        if (preferences.destination == GlookoDestination.CAMAPS_NIGHTSCOUT) {
            val dryRun = preferences.dryRun || credentials.isSimulation()
            val report = camapsUploader.upload(nightscout, bytes, dryRun)
            val now = System.currentTimeMillis()
            preferences.lastSummary = report.summary
            preferences.lastRunMs = now
            if (report.ok && !report.dryRun) preferences.lastSuccessMs = now
            return
        }
        val options = preferences.planOptions(System.currentTimeMillis()).let {
            if (credentials.isSimulation()) it.copy(dryRun = true) else it
        }
        val report = importer.import(text, nightscout, options)
        preferences.lastSummary = report.summary()
        preferences.lastRunMs = System.currentTimeMillis()
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent?): Uri? =
        (intent?.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: intent?.data

    /** Un export Glooko est un CSV à point-virgule d'une trentaine de colonnes. */
    private fun looksLikeGlooko(text: String): Boolean =
        text.lineSequence().take(10).any { line -> line.count { it == ';' } >= 10 }
}
