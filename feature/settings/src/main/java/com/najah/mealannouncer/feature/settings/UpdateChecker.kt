package com.najah.mealannouncer.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Une version publiée, telle que la page des versions la donne.
 *
 * [versionCode] est `null` quand le nom de version n'est pas lisible : on ne propose alors rien
 * plutôt que de proposer une mise à jour au hasard.
 */
data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

/**
 * Mise à jour de l'application depuis ses propres publications (point 6 de la demande du
 * 14/11/2026, inspiré de l'application « Filmage »).
 *
 * Deux différence avec un magasin d'applications : rien n'est imposé (la vérification se coupe
 * dans « À propos ») et rien n'est envoyé à un tiers — seulement une lecture de la page des
 * versions, sur GitHub, là où l'APK est de toute façon publié.
 *
 * La mise à jour ne s'installe pas toute seule : Android affiche son écran d'installation, et
 * l'utilisateur valide. Une application qui se remplacerait en douce sur un téléphone qui
 * pilote une boucle fermée serait difficile à défendre.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Dernière version publiée, ou `null` s'il n'y en a pas de plus récente que [currentVersion]. */
    suspend fun latest(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@withContext null
        val tag = release.optString("tag_name").ifBlank { return@withContext null }
        val version = tag.removePrefix("v").removePrefix("V").trim()
        if (version.isEmpty()) return@withContext null
        if (!isNewer(version, currentVersion)) return@withContext null
        val assets = release.optJSONArray("assets") ?: return@withContext null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            // arm64 uniquement : c'est le seul ABI que l'application publie.
            if (!name.endsWith(".apk") || !name.contains("arm64")) continue
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            return@withContext UpdateInfo(
                tag = tag,
                versionName = version,
                apkUrl = url,
                sizeBytes = asset.optLong("size", 0L),
                notes = release.optString("body").take(4_000),
            )
        }
        null
    }

    /**
     * Télécharge l'APK dans le cache de l'application.
     *
     * Le fichier vit sous `cache/updates/`, l'un des rares dossiers que le fournisseur de
     * fichiers partage — et seulement avec le programme d'installation d'Android.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "Miam-Miam-${info.versionName}.apk")
            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Miam-Miam-Updater")
                }
                connection = conn
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Serveur HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0L } ?: info.sizeBytes
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                target.delete()
                throw cancel
            } catch (error: Throwable) {
                target.delete()
                throw error
            } finally {
                connection?.disconnect()
            }
            if (!target.isFile || target.length() < MIN_APK_BYTES) {
                target.delete()
                throw IllegalStateException("Fichier téléchargé incomplet")
            }
            target
        }

    /** Ouvre l'écran d'installation d'Android sur l'APK téléchargé. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Vrai si l'utilisateur a autorisé l'installation d'applications de source inconnue. */
    fun canInstall(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Ouvre l'écran Android « Installer des applications inconnues » pour Miam-Miam.
     *
     * Sans ce réglage, l'écran d'installation refuse de s'afficher : mieux vaut y conduire
     * l'utilisateur que de lui laisser un bouton sans effet.
     */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun fetchLatestRelease(): org.json.JSONObject {
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Miam-Miam-Updater")
            }
            connection = conn
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Serveur HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return org.json.JSONObject(body)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Compare deux versions « majeure.mineure.correctif ».
     *
     * Une version publiée sans numéro lisible ne compte pas comme plus récente : mieux vaut ne
     * rien proposer qu'une mise à jour douteuse.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val left = parse(candidate) ?: return false
        val right = parse(current) ?: return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val parts = version.trim().split('.', '-')
        if (parts.isEmpty()) return null
        val numbers = parts.mapNotNull { it.toIntOrNull() }
        return numbers.ifEmpty { null }
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Geeks4Good-fr/Miam-Miam-pour-CamAPS-FX/releases/latest"
        const val UPDATES_DIR = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MIN_APK_BYTES = 1_000_000L
    }
}
