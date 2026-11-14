package com.najah.mealannouncer.integration.glookoexport

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Un export Glooko trouvé sur le téléphone. */
data class GlookoFile(
    val uri: Uri,
    val name: String,
    val modifiedMs: Long,
)

/**
 * Retrouve et lit le CSV produit par l'application Glooko.
 *
 * Glooko dépose ses exports dans les téléchargements ; c'est là qu'on regarde, par
 * `MediaStore` plutôt qu'en parcourant le disque — pas de permission de stockage à demander,
 * et rien d'autre n'est lu.
 *
 * Le fichier est refusé au-delà de [MAX_BYTES] : un export hebdomadaire pèse un demi-mégaoctet,
 * un fichier de vingt mégaoctets n'est pas un export et ne doit pas être chargé en mémoire.
 */
@Singleton
class GlookoCsvSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Le CSV le plus récent modifié depuis [sinceMs], en préférant ceux que Glooko a nommés. */
    fun newest(sinceMs: Long): GlookoFile? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        val candidates = ArrayList<GlookoFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("%.csv"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && candidates.size < MAX_CANDIDATES) {
                val name = cursor.getString(nameColumn) ?: continue
                val modifiedMs = cursor.getLong(modifiedColumn) * 1_000L
                if (modifiedMs < sinceMs) continue
                val uri = Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn).toString(),
                )
                candidates += GlookoFile(uri, name, modifiedMs)
            }
        }
        if (candidates.isEmpty()) return null
        // Un export Glooko porte presque toujours son nom : on le préfère au CSV du voisin.
        return candidates.firstOrNull { it.name.contains(GLOOKO_HINT, ignoreCase = true) }
            ?: candidates.first()
    }

    /**
     * Lit le fichier en texte, sans dépasser [MAX_BYTES].
     *
     * @throws GlookoCsvTooLargeException si le fichier est déraisonnable.
     */
    fun read(uri: Uri): String = String(readBytes(uri), Charsets.UTF_8)

    /**
     * Lit le fichier **en octets bruts**, sans dépasser [MAX_BYTES] : c'est ainsi qu'il part vers un
     * Nightscout for CamAPS FX. Aucun `Reader`, aucune `String` : le BOM et les fins de ligne
     * restent ceux de Glooko, et l'empreinte de l'aperçu côté serveur est celle du fichier d'origine.
     */
    fun readBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw GlookoCsvUnreadableException("Fichier illisible : $uri")
        stream.use { input ->
            val output = ByteArrayOutputStream(INITIAL_BUFFER)
            val buffer = ByteArray(BUFFER)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_BYTES) {
                    throw GlookoCsvTooLargeException("Export trop volumineux : plus de ${MAX_BYTES / 1_000_000} Mo.")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    companion object {
        const val MAX_BYTES = 8_000_000
        private const val MAX_CANDIDATES = 8
        private const val INITIAL_BUFFER = 64 * 1024
        private const val BUFFER = 16 * 1024
        private const val GLOOKO_HINT = "glooko"
    }
}

/** Le fichier ne peut pas être lu : chemin perdu, permission retirée, fournisseur muet. */
class GlookoCsvUnreadableException(message: String) : Exception(message)

/** Le fichier dépasse la taille acceptée pour un export. */
class GlookoCsvTooLargeException(message: String) : Exception(message)
