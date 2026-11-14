package com.najah.mealannouncer.feature.assistant

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * La vignette du produit chiffré, avec son origine.
 *
 * Elle n'apparaît qu'une fois l'image réellement chargée : une adresse morte, un fichier qui n'est
 * pas une image ou un réseau absent ne laissent **rien** à l'écran — ni cadre vide, ni image de
 * remplacement qui pourrait passer pour le produit.
 */
@Composable
internal fun ProductThumbnail(image: ProductImage, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = ProductImages.cached(image.url), image.url) {
        if (value == null) value = ProductImages.load(image.url)
    }
    val loaded = bitmap ?: return
    Column(modifier = modifier) {
        Image(
            bitmap = loaded,
            contentDescription = image.origin,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = image.origin,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Téléchargement des vignettes, en HTTP direct comme le client Open Food Facts : une requête GET,
 * HTTPS seulement, une taille plafonnée, et un petit cache en mémoire pour ne pas recharger la
 * même photo à chaque recomposition.
 */
internal object ProductImages {

    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val TARGET_PX = 256
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    private val cache = LruCache<String, ImageBitmap>(32)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    /** L'image décodée, ou `null` pour toute raison : on ne montre alors rien. */
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
