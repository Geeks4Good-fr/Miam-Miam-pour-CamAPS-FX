package com.najah.mealannouncer.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Résultat d'un envoi de journaux.
 *
 * [reference] est le numéro du ticket créé dans l'Odoo de Geeks4Good, renseigné seulement si
 * l'envoi a réussi. Aucune adresse n'est jamais rendue : les journaux ne sont pas publics.
 */
data class LogShipResult(
    val reference: String? = null,
    val file: File? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Journaux de l'application, et leur envoi à l'équipe de Geeks4Good (point 6 de la demande du
 * 14/11/2026, inspiré de « Filmage »).
 *
 * Trois précautions :
 * 1. le fichier ne contient **que** les journaux de Miam-Miam (ses propres étiquettes) plus
 *    quelques lignes de contexte (version, modèle, Android) : rien de la vie privée du reste du
 *    téléphone ;
 * 2. il n'est **jamais** envoyé sans le mode debug — sauf le bouton « envoyer maintenant », qui
 *    est un geste explicite ;
 * 3. il part vers l'Odoo de Geeks4Good, en pièce jointe **privée** d'un ticket de support
 *    (demande du 16/11/2026) : plus de service de collage public, plus de jeton à saisir.
 *    Seule l'équipe de support le lit, et il est effacé au bout de 30 jours.
 */
@Singleton
class LogShipper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Rassemble les journaux récents dans un fichier du cache. */
    suspend fun collect(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, LOGS_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "miam-miam-${Instant.now().epochSecond}.txt")
        file.writeText(header())
        file.appendText(readLogcat())
        file
    }

    /**
     * Envoie les journaux au point d'entrée Odoo de Geeks4Good, **sans compte ni jeton** pour
     * l'utilisateur. Rend le numéro du ticket, à donner au support si besoin.
     *
     * La clé d'application envoyée en en-tête n'est pas un secret (elle est dans le code
     * public) : elle sert seulement à écarter le bruit. Les vraies protections sont côté serveur
     * (taille bornée, nombre d'envois limité, pièce jointe privée).
     */
    suspend fun shipToOdoo(comment: String? = null): LogShipResult = withContext(Dispatchers.IO) {
        val file = collect()
        val payload = JSONObject().apply {
            put("app", context.packageName)
            put("version", versionName())
            put("device", deviceLabel())
            put("install_id", installId())
            comment?.trim()?.takeIf { it.isNotEmpty() }?.let { put("comment", it.take(MAX_COMMENT)) }
            put("content", file.readText().take(MAX_CONTENT))
        }.toString()
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(ODOO_LOGS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Miam-Miam-Logs")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-G4G-App-Key", APP_KEY)
            }
            connection = conn
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code in 200..299 && json?.optBoolean("ok") == true) {
                val ref = json.optString("ref").takeIf { it.isNotBlank() } ?: "reçu"
                return@withContext LogShipResult(reference = ref, file = file)
            }
            val serverReason = json?.optString("error")?.takeIf { it.isNotBlank() }
            val reason = serverReason ?: when (code) {
                403 -> "envoi refusé par le serveur"
                400 -> "envoi mal formé"
                413 -> "journaux trop volumineux"
                429 -> "trop d'envois rapprochés, réessayez dans une heure"
                in 500..599 -> "serveur de support indisponible"
                else -> "serveur HTTP $code"
            }
            LogShipResult(file = file, error = reason)
        } catch (error: Throwable) {
            LogShipResult(file = file, error = error.message ?: "envoi impossible (pas de connexion ?)")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Identifiant aléatoire de cette installation, tiré une fois pour toutes. Il ne dit rien de
     * la personne : il permet seulement au serveur de regrouper les envois d'un même téléphone.
     */
    private fun installId(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    private fun deviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"

    /** Ouvre la feuille de partage d'Android sur le fichier de journaux. */
    suspend fun share(file: File) = withContext(Dispatchers.Main) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Journaux Miam-Miam")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Envoyer les journaux").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** En-tête de contexte : de quoi situer un rapport sans rien demander à personne. */
    private fun header(): String = buildString {
        appendLine("Miam-Miam — journaux")
        appendLine("Date : ${Instant.now()}")
        appendLine("Version : ${versionName()}")
        appendLine("Appareil : ${deviceLabel()}")
        appendLine("Application : ${context.packageName}")
        appendLine("----- journaux -----")
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** Journal du système, restreint aux étiquettes de l'application. */
    private fun readLogcat(): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat", "-d", "-v", "threadtime",
                "-t", MAX_LINES,
                "$APP_TAG:V", "$TIMING_TAG:V", "$CAMAPS_TAG:V", "$CARNET_TAG:V", "$HOME_TAG:V",
            ),
        )
        process.inputStream.bufferedReader().use { it.readText() }.take(MAX_CONTENT)
    }.getOrDefault("(journaux illisibles)")

    private companion object {
        /** Point d'entrée Odoo de Geeks4Good (module g4g, contrat du 16/11/2026). */
        const val ODOO_LOGS_URL = "https://www.geeks4good.fr/g4g/miam-miam/logs"

        /** Clé d'application PUBLIQUE (anti-bruit, pas un secret) fournie par le serveur. */
        const val APP_KEY = "mm-Ao0tpL_g2FryE0hKmStyiuI9JAfOONvW"
        const val PREFS = "miam_logs"
        const val KEY_INSTALL_ID = "install_id"
        const val MAX_COMMENT = 1_000
        const val LOGS_DIR = "logs"
        const val MAX_LINES = "3000"
        const val MAX_CONTENT = 700_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 45_000

        // Étiquettes utilisées par l'application (voir les journaux de chaque module).
        const val APP_TAG = "MealAnnouncer"
        const val TIMING_TAG = "MealAnnouncerTiming"
        const val CAMAPS_TAG = "MealAnnouncerCamaps"
        const val CARNET_TAG = "MealAnnouncerCarnet"
        const val HOME_TAG = "MealAnnouncerHome"
    }
}
