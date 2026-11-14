package com.najah.mealannouncer.ui.theme

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Le fond de l'application : une feuille de papier aquarelle (grain « torchon », comme un Canson
 * 300 g), quelques lavis pastel, et des fruits et légumes peints en transparence.
 *
 * Tout est calculé dans le code, sans image embarquée : le grain est une texture de 384 px
 * répétée, fabriquée une fois au démarrage hors du fil de l'interface. Le décor reste discret
 * (le texte doit rester lisible d'un coup d'œil) et ne réagit à rien : il ne fait que décorer.
 */
@Composable
fun PaperBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < 0.3f
    val grain by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { PaperGrain.bitmap }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(base)
            grain?.let { texture ->
                drawRect(
                    brush = ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)),
                    alpha = if (dark) 0.5f else 1f,
                )
            }
            // Lavis : des taches d'eau colorée aux bords un peu plus soutenus, comme sur le papier.
            Washes.forEach { wash ->
                val center = Offset(size.width * wash.x, size.height * wash.y)
                val radius = size.minDimension * wash.radius
                wash.lobes.forEach { (dx, dy, scale) ->
                    val c = center + Offset(dx * radius, dy * radius)
                    val r = radius * scale
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to wash.color.copy(alpha = 0.03f),
                            0.82f to wash.color.copy(alpha = 0.05f),
                            1f to Color.Transparent,
                            center = c,
                            radius = r,
                        ),
                        radius = r,
                        center = c,
                    )
                    drawCircle(wash.color.copy(alpha = 0.05f), radius = r * 0.97f, center = c, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        // Fruits et légumes « peints » : transparents et un peu flous (Android 12 et plus).
        val fruits = remember { Fruits.layout() }
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(0.6.dp) else Modifier),
        ) {
            val cell = 112.dp.toPx()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 34.dp.toPx()
                alpha = if (dark) 46 else 64
                textAlign = Paint.Align.CENTER
            }
            val columns = (size.width / cell).toInt() + 1
            val rows = (size.height / cell).toInt() + 1
            for (row in 0..rows) for (column in 0..columns) {
                val spot = fruits[(row * 7 + column * 3) % fruits.size]
                val x = (column + spot.dx) * cell
                val y = (row + spot.dy) * cell
                rotate(spot.angle, pivot = Offset(x, y)) {
                    drawIntoCanvas { it.nativeCanvas.drawText(spot.emoji, x, y, paint) }
                }
            }
        }
        // Encre par défaut de tout texte posé sur le papier (sans Surface, ce serait du noir pur).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

/** Une tache de lavis : plusieurs lobes qui se chevauchent, pour un bord irrégulier. */
private class Wash(val x: Float, val y: Float, val radius: Float, val color: Color, seed: Int) {
    val lobes: List<Triple<Float, Float, Float>> = Random(seed).let { random ->
        List(6) { Triple(random.nextFloat() * 0.8f - 0.4f, random.nextFloat() * 0.8f - 0.4f, 0.55f + random.nextFloat() * 0.45f) }
    }
}

private val Washes = listOf(
    Wash(0.05f, 0.08f, 0.55f, Color(0xFF8CC63F), seed = 1), // vert pomme
    Wash(0.95f, 0.30f, 0.45f, Color(0xFFE85D4A), seed = 2), // tomate
    Wash(0.10f, 0.62f, 0.40f, Color(0xFFF7C948), seed = 3), // citron
    Wash(0.90f, 0.88f, 0.55f, Color(0xFF9B6BCB), seed = 4), // raisin
    Wash(0.45f, 1.02f, 0.35f, Color(0xFFF39C3C), seed = 5), // abricot
)

private class FruitSpot(val emoji: String, val dx: Float, val dy: Float, val angle: Float)

private object Fruits {
    private val EMOJI = listOf(
        "🍓", "🥕", "🍋", "🥦", "🍇", "🍅", "🍐", "🥑", "🌽", "🍒", "🍎", "🥝",
        "🍊", "🫐", "🥖", "🧀", "🍌", "🫑", "🥬", "🍑", "🍆", "🥔", "🍉", "🧅",
    )

    fun layout(): List<FruitSpot> {
        val random = Random(2026)
        return EMOJI.shuffled(random).map {
            FruitSpot(it, 0.2f + random.nextFloat() * 0.6f, 0.3f + random.nextFloat() * 0.6f, random.nextFloat() * 50f - 25f)
        }
    }
}

/**
 * Le grain du papier : un relief de bruit (plusieurs octaves, répétable sans couture) éclairé de
 * biais, plus quelques fibres. Rendu en ombres et lumières transparentes, il marche sur le crème
 * comme sur le fond sombre.
 */
private object PaperGrain {
    private const val SIZE = 384

    val bitmap: ImageBitmap by lazy { build().asImageBitmap() }

    private fun build(): Bitmap {
        val height = FloatArray(SIZE * SIZE)
        val octaves = listOf(8 to 0.55f, 24 to 0.30f, 64 to 0.15f)
        for ((cells, weight) in octaves) {
            val lattice = Random(cells).let { r -> FloatArray(cells * cells) { r.nextFloat() } }
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                height[y * SIZE + x] += weight * valueNoise(lattice, cells, x * cells / SIZE.toFloat(), y * cells / SIZE.toFloat())
            }
        }
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val left = height[y * SIZE + (x + SIZE - 1) % SIZE]
            val right = height[y * SIZE + (x + 1) % SIZE]
            val up = height[((y + SIZE - 1) % SIZE) * SIZE + x]
            val down = height[((y + 1) % SIZE) * SIZE + x]
            // Lumière venue d'en haut à gauche : les creux s'assombrissent, les bosses s'éclairent.
            val nx = (left - right) * 6f
            val ny = (up - down) * 6f
            val shade = ((nx + ny) / sqrt(nx * nx + ny * ny + 1f)).coerceIn(-1f, 1f)
            val alpha = (kotlin.math.abs(shade) * 34).toInt()
            pixels[y * SIZE + x] = if (shade >= 0) (alpha shl 24) or 0xFFFFFF else (alpha shl 24)
        }
        // Fibres : de courts traits clairs ou foncés, comme dans un papier de coton.
        val random = Random(7)
        repeat(220) {
            var fx = random.nextFloat() * SIZE
            var fy = random.nextFloat() * SIZE
            val angle = random.nextFloat() * Math.PI.toFloat()
            val length = 6 + random.nextInt(18)
            val light = random.nextBoolean()
            val ink = if (light) 0x1EFFFFFF else 0x14000000
            repeat(length) {
                val px = (floor(fx).toInt() % SIZE + SIZE) % SIZE
                val py = (floor(fy).toInt() % SIZE + SIZE) % SIZE
                pixels[py * SIZE + px] = ink
                fx += kotlin.math.cos(angle)
                fy += kotlin.math.sin(angle)
            }
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Bruit de valeur lissé, périodique sur [cells] cases (la texture se répète sans couture). */
    private fun valueNoise(lattice: FloatArray, cells: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = smooth(x - x0)
        val ty = smooth(y - y0)
        fun at(i: Int, j: Int) = lattice[((j % cells + cells) % cells) * cells + ((i % cells + cells) % cells)]
        val top = lerp(at(x0, y0), at(x0 + 1, y0), tx)
        val bottom = lerp(at(x0, y0 + 1), at(x0 + 1, y0 + 1), tx)
        return lerp(top, bottom, ty)
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
