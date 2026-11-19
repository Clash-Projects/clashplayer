package com.clashplayer.ui.theme

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class AuroraColors(val dominant: Color, val secondary: Color, val tertiary: Color) {
    companion object {
        val Default = AuroraColors(Phosphor, Ember, EmberDeep)
    }
}

/** Ambient colours lifted from the artwork (vibrant / muted / dark swatches), or the house palette without art. */
@Composable
fun rememberArtworkColors(artwork: Uri?): AuroraColors {
    val context = LocalContext.current
    val colors by produceState(AuroraColors.Default, artwork) {
        value = artwork?.let { uri ->
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(uri).size(128).allowHardware(false).build()
                ) as? SuccessResult ?: return@runCatching null
                val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching null
                withContext(Dispatchers.Default) {
                    val p = Palette.from(bmp).maximumColorCount(16).generate()
                    fun sw(s: Palette.Swatch?, fallback: Color) = s?.let { Color(it.rgb) } ?: fallback
                    AuroraColors(
                        sw(p.vibrantSwatch ?: p.lightVibrantSwatch, Phosphor),
                        sw(p.mutedSwatch ?: p.darkVibrantSwatch, Ember),
                        sw(p.darkMutedSwatch, EmberDeep)
                    )
                }
            }.getOrNull()
        } ?: AuroraColors.Default
    }
    return colors
}

private fun grainBitmap(size: Int = 96): Bitmap {
    val rnd = Random(7)
    val px = IntArray(size * size) {
        val v = rnd.nextInt(256)
        (rnd.nextInt(60) shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * Multi-layer ambient mesh: four light pools drift on slow Lissajous orbits and swell with the music's
 * [energy] (0..1), under a tiled film-grain layer. All motion happens in the draw phase from the frame clock,
 * so nothing recomposes; when [animate] is false the scene freezes and stops drawing new frames.
 */
@Composable
fun AmbientMeshBackdrop(
    colors: AuroraColors,
    energy: () -> Float,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val dominant = animateColorAsState(colors.dominant, tween(1600), label = "dominant")
    val secondary = animateColorAsState(colors.secondary, tween(1600), label = "secondary")
    val tertiary = animateColorAsState(colors.tertiary, tween(1600), label = "tertiary")
    val clock = remember { mutableLongStateOf(0L) }
    val smoothedEnergy = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                clock.longValue += (dt * 1e9f).toLong()
                // Fast attack, slow release: pools bloom on hits and relax between them
                val e = energy().coerceIn(0f, 1f)
                val k = if (e > smoothedEnergy.floatValue) 12f else 1.6f
                smoothedEnergy.floatValue += (e - smoothedEnergy.floatValue) * (1f - kotlin.math.exp(-k * dt))
            }
        }
    }

    Box(modifier.drawWithCache {
        val grain = ShaderBrush(ImageShader(grainBitmap().asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        onDrawBehind {
            drawRect(Obsidian)
            val t = clock.longValue / 1e9f
            val e = smoothedEnergy.floatValue
            val w = size.width; val h = size.height
            val pools = arrayOf(
                Triple(dominant.value, 0.55f, floatArrayOf(0.30f, 0.22f, 0.050f, 0.037f, 0.0f)),
                Triple(secondary.value, 0.40f, floatArrayOf(0.74f, 0.62f, 0.041f, 0.053f, 1.7f)),
                Triple(tertiary.value, 0.50f, floatArrayOf(0.20f, 0.84f, 0.033f, 0.045f, 3.1f)),
                Triple(dominant.value.copy(alpha = 0.6f), 0.32f, floatArrayOf(0.86f, 0.18f, 0.060f, 0.029f, 4.4f)),
            )
            for ((color, alpha, o) in pools) {
                val cx = w * (o[0] + 0.14f * sin(o[2] * t * 6.283f + o[4]))
                val cy = h * (o[1] + 0.10f * cos(o[3] * t * 6.283f + o[4]))
                val r = maxOf(w, h) * (0.48f + 0.16f * e)
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = alpha * (0.55f + 0.45f * e) * color.alpha), Color.Transparent), Offset(cx, cy), r),
                    r, Offset(cx, cy)
                )
            }
            // Vignette pulls the eye to the centre and keeps edges obsidian
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Obsidian.copy(alpha = 0.85f)), Offset(w / 2, h * 0.42f), maxOf(w, h) * 0.75f))
            // Grain shifts by whole tiles' worth of pseudo-random offset each frame: shimmer without new noise
            val seed = (t * 24).toInt()
            translate(((seed * 37) % 96).toFloat(), ((seed * 59) % 96).toFloat()) {
                drawRect(grain, Offset(-96f, -96f), androidx.compose.ui.geometry.Size(w + 192f, h + 192f), alpha = 0.55f)
            }
        }
    })
}
