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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Résultat d'un envoi de journaux.
 *
 * [gistUrl] n'est renseignée qu'en cas d'envoi réussi ; sans jeton, l'application retombe sur le
 * partage d'Android, qui rend la main à l'utilisateur.
 */
data class LogShipResult(
    val gistUrl: String? = null,
    val file: File? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Journaux de l'application, et leur envoi au développeur (point 6 de la demande du
 * 14/11/2026, inspiré de « Filmage »).
 *
 * Deux précautions :
 * 1. le fichier ne contient **que** les journaux de Miam-Miam (ses propres étiquettes) plus
 *    quelques lignes de contexte (version, modèle, Android) : rien de la vie privée du reste du
 *    téléphone ;
 * 2. il n'est **jamais** envoyé sans le mode debug — sauf le bouton « envoyer maintenant », qui
 *    est un geste explicite.
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
     * Envoie le fichier dans un « Gist » privé (le journal reste privé : Gist secret, non
     * listé). Rend l'adresse en cas de succès, sinon la raison.
     */
    suspend fun shipToGist(token: String): LogShipResult = withContext(Dispatchers.IO) {
        val file = collect()
        val payload = JSONObject().apply {
            put("description", "Miam-Miam — journaux du ${Instant.now()}")
            put("public", false)
            put(
                "files",
                JSONObject().put(
                    file.name,
                    JSONObject().put("content", file.readText().take(MAX_CONTENT)),
                ),
            )
        }.toString()
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(GISTS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Miam-Miam-Logs")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection = conn
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val reason = when (code) {
                    401 -> "jeton GitHub refusé"
                    404 -> "dépôt Gist indisponible"
                    422 -> "contenu refusé"
                    else -> "serveur HTTP $code"
                }
                return@withContext LogShipResult(
                    file = file,
                    error = if (detail.isBlank()) reason else "$reason (${detail.take(160)})",
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val url = runCatching { JSONObject(body).optString("html_url") }.getOrDefault("")
            LogShipResult(gistUrl = url.takeIf { it.isNotBlank() } ?: "Gist créé", file = file)
        } catch (error: Throwable) {
            LogShipResult(file = file, error = error.message ?: "envoi impossible")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Envoie les journaux **sans aucune clé, sans compte** (demande du 15/11/2026).
     *
     * L'utilisateur ne doit rien avoir à créer : le fichier est déposé sur un service de collage
     * public qui rend une adresse, et cette adresse est affichée (elle est aussi copiée dans le
     * presse-papier par l'écran). Deux services sont essayés l'un après l'autre : le premier
     * suffit dans la très grande majorité des cas, le second prend le relais s'il tombe.
     *
     * L'adresse est aléatoire et le dépôt expire : ce n'est pas un hébergement, c'est un
     * transport. Le contenu reste celui des journaux, déjà annoncés par le mode debug.
     */
    suspend fun shipToPasteBin(): LogShipResult = withContext(Dispatchers.IO) {
        val file = collect()
        val content = file.readText().take(MAX_CONTENT)
        val failures = mutableListOf<String>()

        // 1. dpaste : accepte de gros contenus, rend l'adresse en clair, expire en 7 jours.
        runCatching { postForm(DPASTE_URL, "content" to content, "expiry_days" to "7") }
            .onSuccess { url -> return@withContext LogShipResult(gistUrl = url, file = file) }
            .onFailure { failures.add("dpaste : ${it.message}") }

        // 2. paste.rs : corps brut, plus petit mais sans paramètre à encoder.
        runCatching { postRaw(PASTE_RS_URL, content) }
            .onSuccess { url -> return@withContext LogShipResult(gistUrl = url, file = file) }
            .onFailure { failures.add("paste.rs : ${it.message}") }

        LogShipResult(file = file, error = failures.joinToString(" ; "))
    }

    /** POST de formulaire ; rend le texte de la réponse (l'adresse du dépôt). */
    private fun postForm(url: String, vararg fields: Pair<String, String>): String {
        val body = fields.joinToString("&") { (name, value) ->
            "${java.net.URLEncoder.encode(name, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        return post(
            url = url,
            body = body,
            contentType = "application/x-www-form-urlencoded; charset=utf-8",
        )
    }

    /** POST d'un corps texte brut ; rend le texte de la réponse. */
    private fun postRaw(url: String, body: String): String = post(
        url = url,
        body = body,
        contentType = "text/plain; charset=utf-8",
    )

    private fun post(url: String, body: String, contentType: String): String {
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Miam-Miam-Logs")
                setRequestProperty("Content-Type", contentType)
            }
            connection = conn
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("serveur HTTP $code")
            val answer = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            // La réponse d'un service de collage EST l'adresse.
            val link = answer.lineSequence().firstOrNull { it.startsWith("http") } ?: answer
            if (!link.startsWith("http")) throw IllegalStateException("réponse inattendue")
            return link
        } finally {
            connection?.disconnect()
        }
    }

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
        appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
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
        const val GISTS_URL = "https://api.github.com/gists"
        const val DPASTE_URL = "https://dpaste.com/api/v2/"
        const val PASTE_RS_URL = "https://paste.rs"
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
