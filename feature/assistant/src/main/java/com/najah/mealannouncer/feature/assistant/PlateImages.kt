package com.najah.mealannouncer.feature.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Le dossier du cache où l'appareil photo écrit, et lui seul. */
private const val PLATE_FOLDER = "plate"

/** Une photo prise il y a plus longtemps que cela est un reste d'application interrompue. */
private const val STALE_TARGET_MS = 60L * 60L * 1000L

/**
 * Prépare les photos d'assiette avant de les envoyer à Mistral.
 *
 * Deux sources, deux traitements. Une photo **prise** par l'application est écrite dans le
 * cache (jamais dans la galerie) et supprimée dès qu'elle a été lue : elle n'existe que le
 * temps de l'analyse. Une photo **choisie** dans le téléphone n'est jamais copiée ni touchée :
 * on la lit en lecture seule, et le chargeur ne connaît que ses propres fichiers — il ne peut
 * donc pas effacer ceux de quelqu'un d'autre, même par erreur.
 */
@Singleton
class PlateImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Les fichiers temporaires créés ici, par URI : c'est la liste de ce qu'on a le droit d'effacer. */
    private val targets: MutableMap<String, File> =
        Collections.synchronizedMap(mutableMapOf<String, File>())

    /**
     * Un fichier vide où l'appareil photo écrira, exposé par le fournisseur de fichiers de
     * l'application : aucune autorisation de stockage n'est demandée pour cela.
     */
    fun newTarget(): Uri {
        val folder = File(context.cacheDir, PLATE_FOLDER)
        folder.mkdirs()
        sweep(folder)
        val file = File(folder, "assiette-${System.currentTimeMillis()}$EXTENSION_SUFFIXE")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
        targets[uri.toString()] = file
        return uri
    }

    /** Efface le fichier temporaire d'une photo prise par l'application — et seulement celui-là. */
    fun forget(uri: Uri) {
        val file = targets.remove(uri.toString()) ?: return
        runCatching { file.delete() }
    }

    /**
     * La photo, réduite et compressée en JPEG, prête à être envoyée.
     *
     * Rend `null` quand l'image ne peut pas être lue (fichier disparu, format inconnu) :
     * l'appelant le dit à l'écran plutôt que d'envoyer autre chose.
     */
    suspend fun jpeg(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, PLATE_MAX_SIDE)
        }
        val decoded = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@withContext null

        val longer = maxOf(decoded.width, decoded.height)
        val scaled = if (longer > PLATE_MAX_SIDE) {
            val ratio = PLATE_MAX_SIDE.toFloat() / longer
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).roundToInt().coerceAtLeast(1),
                (decoded.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, PLATE_JPEG_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        out.toByteArray()
    }

    private fun openStream(uri: Uri) =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    /** Efface les fichiers qu'une application interrompue aurait laissés. */
    private fun sweep(folder: File) {
        val limit = System.currentTimeMillis() - STALE_TARGET_MS
        runCatching {
            folder.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < limit) file.delete()
            }
        }
    }

    private companion object {
        const val EXTENSION_SUFFIXE = ".jpg"
    }
}

/** Côté long de l'image envoyée : au-delà, le modèle ne voit rien de plus. */
internal const val PLATE_MAX_SIDE = 1024

/** Qualité JPEG : lisible pour le modèle, légère pour le réseau. */
internal const val PLATE_JPEG_QUALITY = 80
