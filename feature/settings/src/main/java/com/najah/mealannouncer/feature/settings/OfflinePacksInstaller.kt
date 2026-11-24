package com.najah.mealannouncer.feature.settings

import android.content.Context
import android.net.Uri
import com.najah.mealannouncer.data.openfoodfacts.OffDatabase
import com.najah.mealannouncer.feature.assistant.VoskModelStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import com.najah.mealannouncer.feature.assistant.portions.PortionBank
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Les quatre paquets hors ligne de Miam-Miam (`docs/OFF-LOCAL.md`, `docs/VOIX-LOCALE.md`,
 * `docs/MIAM-MIAM.md`).
 *
 * Téléchargés et décompressés par l'application elle-même dans son dossier externe
 * (`getExternalFilesDir(null)`), ils appartiennent d'office à l'application : aucun ordinateur,
 * aucun `adb push` et aucun `chmod` ne sont nécessaires.
 */
enum class OfflinePack(
    val id: String,
    val title: String,
    val description: String,
    val archiveName: String,
    val targetSubdir: String,
    val zipBytes: Long,
    val uncompressedBytes: Long,
    val sha256: String,
    val required: Boolean,
) {
    KROKO_FR(
        id = "kroko-fr",
        title = "Dictée vocale hors ligne (Kroko)",
        description = "Transcrit vos phrases sur le téléphone en ~1 seconde, sans Internet.",
        archiveName = "kroko-fr.zip",
        targetSubdir = "sherpa/kroko-fr",
        zipBytes = 54_801_798L,
        uncompressedBytes = 71_052_319L,
        sha256 = "cd15504402ace945f7a05f59ad0550b7920422d42c2b933890ee7626e55a1950",
        required = true,
    ),
    OFF_BASE(
        id = "off-base",
        title = "Base Open Food Facts hors ligne (3,1 M de produits)",
        description = "Trouve instantanément les glucides et les portions des produits du commerce, même sans réseau.",
        archiveName = "off-base.db.zip",
        targetSubdir = "",
        zipBytes = 325_517_619L,
        uncompressedBytes = 684_322_816L,
        sha256 = "f0ba8b1ebaf336188ff6a32cd5a4e2cc3276e05b7061c5b406d2cebea0571bf9",
        required = true,
    ),
    VOSK_FR(
        id = "vosk-model-fr",
        title = "Mot-clé « Miam-miam » (Vosk français)",
        description = "Permet de réveiller l'application à la voix en disant « Miam-miam ».",
        archiveName = "vosk-model-fr.zip",
        targetSubdir = "vosk-model-fr",
        zipBytes = 42_204_142L,
        uncompressedBytes = 68_483_807L,
        sha256 = "0e13217a6b5516fb32475451c5373b0eb3cdce1196fbf17b06442eb84b322dc7",
        required = true,
    ),
    PIPER_UPMC(
        id = "piper-fr-upmc",
        title = "Voix naturelle hors ligne (Piper upmc)",
        description = "Facultatif : voix française calculée sur le téléphone (sans clé Mistral).",
        archiveName = "piper-fr-upmc.zip",
        targetSubdir = "sherpa/piper-fr-upmc",
        zipBytes = 80_250_240L,
        uncompressedBytes = 94_680_432L,
        sha256 = "f3757ac083e6834b130b8b497837864e3f6f0b3329fe6d72cd40266b88bee9e7",
        required = false,
    ),
    PORTIONS(
        id = "portions-modele",
        title = "Portions en images (862 aliments, 2 195 photos)",
        description = "Les photos de portions pesées (assiettes, bols) et les valeurs Ciqual des aliments sans marque.",
        archiveName = "modele-portions.zip",
        targetSubdir = PortionBank.DOWNLOADED_DIR,
        // Archive publiée le 23/11/2026 (crédits CC BY : Geeks4Good, Patrick Blan, Alexandre Roschewitz).
        zipBytes = 65_843_851L,
        uncompressedBytes = 67_192_795L,
        sha256 = "edb10860b4a9b0620961c761adad244320dc17ce7c2028484e207c3e9230fc51",
        required = true,
    );

    fun downloadUrls(): List<String> = listOf(
        "https://github.com/Geeks4Good-fr/Miam-Miam-pour-CamAPS-FX/releases/download/v0.14.0/$archiveName",
        "https://github.com/Geeks4Good-fr/Miam-Miam-pour-CamAPS-FX/releases/download/v0.12.0/$archiveName",
        "https://github.com/Geeks4Good-fr/Miam-Miam-pour-CamAPS-FX/releases/latest/download/$archiveName",
    )
}

/** Progression d'installation d'un paquet hors ligne. */
sealed interface PackInstallProgress {
    data class Downloading(
        val pack: OfflinePack,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : PackInstallProgress

    data class Verifying(val pack: OfflinePack) : PackInstallProgress

    data class Extracting(
        val pack: OfflinePack,
        val extractedBytes: Long,
        val totalUncompressedBytes: Long,
    ) : PackInstallProgress
}

/**
 * Vérifie, télécharge, contrôle (SHA-256) et extrait les paquets hors ligne avec un tampon
 * mémoire borné à 64 Ko afin de ne jamais concurrencer CamAPS FX en RAM.
 */
@Singleton
class OfflinePacksInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offDatabase: OffDatabase,
    private val voskStorage: VoskModelStorage,
    private val portionBank: PortionBank,
) {

    /** Un seul téléchargement à la fois, qu'il vienne de l'assistant d'installation ou de la tâche de fond. */
    private val installLock = kotlinx.coroutines.sync.Mutex()

    fun rootFilesDir(): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun targetDir(pack: OfflinePack): File {
        val base = rootFilesDir()
        return if (pack.targetSubdir.isEmpty()) base else File(base, pack.targetSubdir)
    }

    /** Vrai si le paquet est présent et complet sur le téléphone. */
    fun isInstalled(pack: OfflinePack): Boolean = when (pack) {
        OfflinePack.KROKO_FR -> {
            val dir = targetDir(pack)
            KROKO_REQUIRED_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0L } }
        }
        OfflinePack.OFF_BASE -> {
            val db = offDatabase.file()
            db.isFile && db.length() > 1_000_000L
        }
        OfflinePack.VOSK_FR -> voskStorage.available
        OfflinePack.PIPER_UPMC -> {
            val dir = targetDir(pack)
            val hasOnnx = dir.listFiles()?.any { it.isFile && it.name.endsWith(".onnx") && it.length() > 0L } == true
            hasOnnx && File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data").isDirectory
        }
        OfflinePack.PORTIONS -> File(targetDir(pack), "portions.json").let { it.isFile && it.length() > 0L }
    }

    /** Vrai si les trois paquets principaux (Kroko, Open Food Facts, Vosk) sont installés. */
    fun allRequiredInstalled(): Boolean =
        OfflinePack.entries.filter { it.required }.all(::isInstalled)

    /**
     * Télécharge [pack] depuis GitHub Releases, vérifie son empreinte SHA-256 et le décompresse
     * dans son dossier cible.
     */
    suspend fun downloadAndInstall(
        pack: OfflinePack,
        onProgress: (PackInstallProgress) -> Unit,
    ): Result<Unit> = installLock.withLock {
        // L'autre chemin (tâche de fond ou assistant) a pu l'installer pendant l'attente du verrou.
        if (isInstalled(pack)) Result.success(Unit) else downloadAndInstallUnlocked(pack, onProgress)
    }

    private suspend fun downloadAndInstallUnlocked(
        pack: OfflinePack,
        onProgress: (PackInstallProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "offline-packs").apply { mkdirs() }
        val tempZip = File(cacheDir, "${pack.archiveName}.part")
        try {
            downloadWithFallback(pack, tempZip, onProgress)
            onProgress(PackInstallProgress.Verifying(pack))
            val actualSha = sha256Hex(tempZip)
            if (!actualSha.equals(pack.sha256, ignoreCase = true)) {
                tempZip.delete()
                return@withContext Result.failure(
                    IllegalStateException(
                        "Empreinte SHA-256 incorrecte pour ${pack.archiveName} (reçu ${actualSha.take(12)}…).",
                    ),
                )
            }
            extractZipToTarget(pack, tempZip, onProgress)
            tempZip.delete()
            if (pack == OfflinePack.OFF_BASE) {
                offDatabase.reload()
            }
            if (pack == OfflinePack.PORTIONS) {
                portionBank.reload()
            }
            if (!isInstalled(pack)) {
                return@withContext Result.failure(
                    IllegalStateException("Fichiers incomplets après extraction de ${pack.title}."),
                )
            }
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            tempZip.delete()
            throw cancel
        } catch (error: Throwable) {
            tempZip.delete()
            Result.failure(error)
        }
    }

    /**
     * Installe un paquet `.zip` choisi sur le téléphone (pratique si l'archive a été copiée sur
     * clé USB ou téléchargée à part).
     */
    suspend fun installFromUri(
        pack: OfflinePack,
        uri: Uri,
        onProgress: (PackInstallProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "offline-packs").apply { mkdirs() }
        val tempZip = File(cacheDir, "${pack.archiveName}.import.part")
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Fichier illisible."))
            stream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(PackInstallProgress.Downloading(pack, copied, pack.zipBytes))
                    }
                }
            }
            extractZipToTarget(pack, tempZip, onProgress)
            tempZip.delete()
            if (pack == OfflinePack.OFF_BASE) {
                offDatabase.reload()
            }
            if (pack == OfflinePack.PORTIONS) {
                portionBank.reload()
            }
            if (!isInstalled(pack)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Ce fichier .zip ne contient pas les fichiers attendus pour « ${pack.title} ».",
                    ),
                )
            }
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            tempZip.delete()
            throw cancel
        } catch (error: Throwable) {
            tempZip.delete()
            Result.failure(error)
        }
    }

    private suspend fun downloadWithFallback(
        pack: OfflinePack,
        destFile: File,
        onProgress: (PackInstallProgress) -> Unit,
    ) {
        var lastError: Throwable? = null
        for (url in pack.downloadUrls()) {
            try {
                downloadUrlToFile(url, pack, destFile, onProgress)
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Téléchargement impossible pour ${pack.archiveName}")
    }

    private suspend fun downloadUrlToFile(
        urlString: String,
        pack: OfflinePack,
        destFile: File,
        onProgress: (PackInstallProgress) -> Unit,
    ) {
        var currentUrl = urlString
        var redirects = 0
        var connection: HttpURLConnection? = null
        try {
            while (redirects < 6) {
                coroutineContext.ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Miam-Miam-Installer/0.14.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        throw IllegalStateException("Redirection HTTP $code sans destination")
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    throw IllegalStateException(
                        if (code == 404) {
                            "Archive ${pack.archiveName} absente de la publication GitHub (404)"
                        } else {
                            "Serveur HTTP $code pour ${pack.archiveName}"
                        },
                    )
                }
                connection = conn
                break
            }
            val activeConn = connection ?: throw IllegalStateException("Trop de redirections HTTP")
            val total = activeConn.contentLengthLong.takeIf { it > 0L } ?: pack.zipBytes
            activeConn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastReportMs = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= PROGRESS_INTERVAL_MS || downloaded == total) {
                            lastReportMs = now
                            onProgress(PackInstallProgress.Downloading(pack, downloaded, total))
                        }
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun extractZipToTarget(
        pack: OfflinePack,
        zipFile: File,
        onProgress: (PackInstallProgress) -> Unit,
    ) {
        val destRoot = targetDir(pack).apply { mkdirs() }
        FileInputStream(zipFile).use { fis ->
            extractZipStream(
                input = fis,
                destRoot = destRoot,
                totalUncompressedBytes = pack.uncompressedBytes,
                onExtractedBytes = { bytes ->
                    coroutineContext.ensureActive()
                    onProgress(PackInstallProgress.Extracting(pack, bytes, pack.uncompressedBytes))
                },
            )
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_INTERVAL_MS = 120L

        internal val KROKO_REQUIRED_FILES = listOf(
            "encoder.onnx",
            "decoder.onnx",
            "joiner.onnx",
            "tokens.txt",
        )

        /** Calcule l'empreinte SHA-256 hexadécimale d'un fichier avec un tampon de 64 Ko. */
        internal fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Extrait un flux ZIP dans [destRoot] en protégeant contre toute traversée de chemin
         * (`..`) et en écrivant chaque fichier dans un `.part` avant de le renommer.
         *
         * Si toutes les entrées partagent un dossier racine connu (ex. `vosk-model-small-fr-0.22/`),
         * ce préfixe est retiré afin que `am/`, `conf/`, `graph/` tombent directement dans [destRoot].
         */
        internal inline fun extractZipStream(
            input: InputStream,
            destRoot: File,
            totalUncompressedBytes: Long = 0L,
            onExtractedBytes: (Long) -> Unit = {},
        ) {
            val canonicalRoot = destRoot.canonicalFile
            val canonicalPrefix = canonicalRoot.path + File.separator
            val buffer = ByteArray(BUFFER_SIZE)
            var totalExtracted = 0L
            var lastReportMs = 0L

            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val cleanName = stripKnownTopFolder(entry.name.replace('\\', '/').trimStart('/'))
                    if (cleanName.isEmpty()) {
                        zis.closeEntry()
                        continue
                    }
                    val outFile = File(canonicalRoot, cleanName)
                    val canonicalOut = outFile.canonicalFile
                    if (canonicalOut != canonicalRoot && !canonicalOut.path.startsWith(canonicalPrefix)) {
                        throw SecurityException("Entrée ZIP hors du dossier cible : ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        canonicalOut.mkdirs()
                    } else {
                        canonicalOut.parentFile?.mkdirs()
                        val partFile = File(canonicalOut.parentFile, "${canonicalOut.name}.part")
                        FileOutputStream(partFile).use { fos ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read < 0) break
                                fos.write(buffer, 0, read)
                                totalExtracted += read
                                val now = System.currentTimeMillis()
                                if (now - lastReportMs >= PROGRESS_INTERVAL_MS || totalExtracted >= totalUncompressedBytes) {
                                    lastReportMs = now
                                    onExtractedBytes(totalExtracted)
                                }
                            }
                        }
                        if (canonicalOut.exists()) {
                            canonicalOut.delete()
                        }
                        if (!partFile.renameTo(canonicalOut)) {
                            partFile.copyTo(canonicalOut, overwrite = true)
                            partFile.delete()
                        }
                    }
                    zis.closeEntry()
                }
            }
            onExtractedBytes(totalExtracted)
        }

        internal fun stripKnownTopFolder(entryPath: String): String {
            for (prefix in KNOWN_WRAPPER_PREFIXES) {
                if (entryPath == prefix.trimEnd('/')) return ""
                if (entryPath.startsWith(prefix)) return entryPath.removePrefix(prefix)
            }
            return entryPath
        }

        private val KNOWN_WRAPPER_PREFIXES = listOf(
            "vosk-model-small-fr-0.22/",
            "vosk-model-fr/",
            "sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/",
            "kroko-fr/",
            "vits-piper-fr_FR-upmc-medium/",
            "piper-fr-upmc/",
        )
    }
}
