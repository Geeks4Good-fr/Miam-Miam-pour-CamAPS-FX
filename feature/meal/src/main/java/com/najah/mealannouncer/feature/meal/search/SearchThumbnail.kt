package com.najah.mealannouncer.feature.meal.search

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.model.FoodProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * La photo de face d'un produit dans la recherche manuelle (demande du 24/11/2026 : reconnaître
 * d'un coup d'œil le bon produit).
 *
 * Comme la vignette de l'assistant, elle n'apparaît qu'une fois l'image réellement chargée : sans
 * réseau ou sans photo, **rien** — jamais une image de remplacement qui passerait pour le produit.
 */
@Composable
internal fun SearchThumbnail(
    product: FoodProduct,
    imageUrlFor: suspend (FoodProduct) -> String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = product.imageUrl?.let { SearchImages.cached(it) },
        product.id,
    ) {
        if (value == null) {
            val url = imageUrlFor(product) ?: return@produceState
            value = SearchImages.cached(url) ?: SearchImages.load(url)
        }
    }
    val loaded = bitmap ?: return
    Image(
        bitmap = loaded,
        contentDescription = product.name,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

/** Téléchargement HTTPS borné, petit cache en mémoire. */
internal object SearchImages {

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TARGET_PX = 200
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    private val cache = LruCache<String, ImageBitmap>(48)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = download(url) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null
        decoded.asImageBitmap().also { cache.put(url, it) }
    }

    private fun download(url: String): ByteArray? {
        val connection = try {
            URL(url).takeIf { it.protocol == "https" }?.openConnection() as? HttpURLConnection
        } catch (error: IOException) {
            null
        } ?: return null
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            if (connection.contentType?.startsWith("image/") != true) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } catch (error: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
