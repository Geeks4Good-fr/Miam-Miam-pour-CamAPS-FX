package com.najah.mealannouncer.feature.assistant.portions

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import kotlin.math.abs

/**
 * La carte « portion » : la photo de la portion la plus proche de la quantité actuelle, et un
 * bouton par poids pesé. Toucher un bouton met la ligne à ce poids, en grammes.
 *
 * Le principe (l'image change avec le poids choisi) vient des applis de comptage de glucides ;
 * les photos sont les nôtres (`docs/PORTIONS.md`).
 */
@Composable
internal fun PortionPicker(
    options: List<PortionOption>,
    currentGrams: Double?,
    onPick: ((Double) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val shown = options.minByOrNull { abs(it.grams - (currentGrams ?: options[options.size / 2].grams)) } ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        shown.imagePath?.let { path ->
            val bitmap by produceState<ImageBitmap?>(initialValue = cache.get(path), path) {
                if (value == null) value = withContext(Dispatchers.IO) { load(path) }
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = shown.label ?: "${shown.grams.toInt()} g",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Text(
            text = "Quelle portion ressemble à la vôtre ?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = currentGrams != null && abs(option.grams - currentGrams) < 0.5,
                    onClick = { onPick?.invoke(option.grams) },
                    enabled = onPick != null,
                    label = { Text("${option.grams.toInt()} g") },
                )
            }
        }
        shown.label?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val cache = LruCache<String, ImageBitmap>(24)

private fun load(path: String): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 800) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()?.also { cache.put(path, it) }
} catch (error: Exception) {
    null
}
