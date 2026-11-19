package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import kotlin.math.roundToInt

/**
 * Lays its children on a shallow smile-shaped arc: the centre (most used) control sits lowest, nearest the
 * resting thumb, and the outer ones rise toward where the thumb sweeps. An engraved groove runs through them.
 */
@Composable
fun ControlArc(modifier: Modifier = Modifier, sag: Dp = 22.dp, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = modifier.drawBehind {
            val s = sag.toPx()
            val base = (size.height - s) / 2 // same curve the children are centred on
            val path = Path()
            val steps = 48
            for (i in 0..steps) {
                val u = i / steps.toFloat() * 2 - 1
                val p = Offset(size.width * (u + 1) / 2, base + s * (1 - u * u))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val fade = Brush.horizontalGradient(listOf(Color.Transparent, InkMuted.copy(alpha = 0.45f), InkMuted.copy(alpha = 0.45f), Color.Transparent))
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(2.dp.toPx()))
            drawPath(path, fade, style = Stroke(0.6.dp.toPx()))
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val s = sag.roundToPx()
        val tallest = placeables.maxOfOrNull { it.height } ?: 0
        val w = constraints.maxWidth
        val h = tallest + s
        layout(w, h) {
            val n = placeables.size
            placeables.forEachIndexed { i, p ->
                val cx = w * (i + 0.5f) / n
                val u = cx / w * 2 - 1
                val cy = tallest / 2f + s * (1 - u * u)
                p.place((cx - p.width / 2f).roundToInt(), (cy - p.height / 2f).roundToInt())
            }
        }
    }
}

/** Borderless glyph control: dims at rest, lights when [active] (with an ember notch beneath), sinks when pressed. */
@Composable
fun GlyphButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = 48.dp, lit: Boolean = active) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val sink = remember { Animatable(1f) }
    LaunchedEffect(pressed) { sink.animateTo(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f)) }
    Box(
        modifier
            .size(size)
            .clickable(source, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .graphicsLayer { scaleX = sink.value; scaleY = sink.value }
            .drawBehind {
                if (active) drawCircle(Ember, 2.dp.toPx(), Offset(this.size.width / 2, this.size.height - 6.dp.toPx()))
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = if (lit) Ink else InkDim, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The play control: a brushed obsidian disc whose glyph morphs between a triangle and two bars on a spring,
 * ringed by a halo that breathes with the music's [energy]. Everything animates in the draw phase.
 */
@Composable
fun MorphPlayButton(isPlaying: Boolean, onClick: () -> Unit, energy: () -> Float, modifier: Modifier = Modifier, size: Dp = 78.dp, accent: Color = Phosphor) {
    val morph = remember { Animatable(if (isPlaying) 1f else 0f) }
    LaunchedEffect(isPlaying) { morph.animateTo(if (isPlaying) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying) { if (isPlaying) while (true) withFrameNanos { frame.longValue = it } }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val sink = remember { Animatable(1f) }
    LaunchedEffect(pressed) { sink.animateTo(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f)) }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier
            .size(size)
            .clickable(source, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
            .graphicsLayer { scaleX = sink.value; scaleY = sink.value }
            .drawBehind {
                frame.longValue // redraw each frame while playing
                val r = this.size.minDimension / 2
                val c = center
                val e = if (isPlaying) energy() else 0f
                // Halo: energy-driven bloom
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f + 0.35f * e), Color.Transparent), c, r * (1.25f + 0.3f * e)), r * (1.25f + 0.3f * e), c)
                // Brushed disc: concentric lathe rings
                drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2D33), Color(0xFF15171B), Color(0xFF0B0C0F)), c, r), r, c)
                var rr = r * 0.95f
                var k = 0
                while (rr > r * 0.2f) {
                    drawCircle(Color.White.copy(alpha = if (k % 2 == 0) 0.025f else 0.012f), rr, c, style = Stroke(0.7f))
                    rr -= 1.6f; k++
                }
                // Rim specular: light from the top-left
                drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.6f)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)), r - 0.75f, c, style = Stroke(1.5f))
                drawCircle(accent.copy(alpha = 0.55f + 0.45f * e), r * 0.84f, c, style = Stroke(1.dp.toPx()))
                // Glyph morph: each half of the triangle becomes one bar
                val m = morph.value
                val gh = r * 0.72f; val top = c.y - gh / 2; val bot = c.y + gh / 2
                val left = c.x - gh * 0.38f + gh * 0.06f * (1 - m)
                val right = c.x + gh * 0.44f + gh * 0.06f * (1 - m)
                val mid = (left + right) / 2
                val bar = gh * 0.27f
                fun lerp(a: Float, b: Float) = a + (b - a) * m
                val glyph = Path().apply {
                    moveTo(left, top); lineTo(lerp(mid, left + bar), lerp(top + gh / 4, top))
                    lineTo(lerp(mid, left + bar), lerp(bot - gh / 4, bot)); lineTo(left, bot); close()
                    moveTo(lerp(mid, right - bar), lerp(top + gh / 4, top)); lineTo(right, lerp(c.y, top))
                    lineTo(right, lerp(c.y, bot)); lineTo(lerp(mid, right - bar), lerp(bot - gh / 4, bot)); close()
                }
                drawPath(glyph, Brush.verticalGradient(listOf(Ink, accent), top, bot))
            }
    )
}

