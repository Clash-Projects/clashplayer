package com.clashplayer.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START = 135f
private const val SWEEP = 270f
private const val TICKS = 31

/**
 * Mixing-console rotary: a brushed-metal cap (its sheen stays fixed to the light as the cap turns), a knurled
 * rim, and a 31-notch scale lit from the zero point. Drag vertically to turn, with a haptic click per notch and
 * a sticky detent at 0 on bipolar ranges; double-tap to recentre.
 */
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String = "",
    color: Color = Phosphor,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val bipolar = min < 0 && max > 0
    val home = if (bipolar) 0f else min
    fun norm(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(Locale.US), style = HudStyle)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(size)
                .pointerInput(min, max) {
                    detectTapGestures(onDoubleTap = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); change(home) })
                }
                .pointerInput(min, max) {
                    var raw = 0f
                    detectVerticalDragGestures(onDragStart = { raw = norm(current) }) { c, dy ->
                        c.consume()
                        raw = (raw - dy / (this.size.height * 3.2f)).coerceIn(0f, 1f) // ~3 knob heights for full travel
                        var v = min + raw * (max - min)
                        if (bipolar && abs(v) < (max - min) * 0.015f) v = 0f
                        val before = (norm(current) * (TICKS - 1)).roundToInt()
                        val after = (norm(v) * (TICKS - 1)).roundToInt()
                        if (before != after) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(v)
                    }
                }
                .drawBehind { drawKnob(norm(current), norm(home), color) }
        )
        Spacer(Modifier.height(4.dp))
        val sign = if (value > 0 && bipolar) "+" else ""
        Text("$sign${"%.1f".format(Locale.US, value)}${if (unit.isNotEmpty()) " $unit" else ""}", style = HudStyle.copy(color = Ink))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKnob(n: Float, home: Float, accent: Color) {
    val c = center
    val outer = size.minDimension / 2
    fun polar(r: Float, deg: Float): Offset {
        val a = deg * PI.toFloat() / 180f
        return Offset(c.x + r * cos(a), c.y + r * sin(a))
    }
    // Scale notches, lit between home and the current value
    val lo = minOf(n, home); val hi = maxOf(n, home)
    for (i in 0 until TICKS) {
        val f = i / (TICKS - 1f)
        val deg = START + SWEEP * f
        val major = i == 0 || i == TICKS - 1 || i == TICKS / 2
        val lit = f >= lo - 0.001f && f <= hi + 0.001f && hi - lo > 0.001f
        drawLine(
            if (lit) accent else InkMuted.copy(alpha = if (major) 0.8f else 0.4f),
            polar(outer * (if (major) 0.80f else 0.85f), deg), polar(outer * 0.95f, deg),
            (if (major) 1.6f else 1f).dp.toPx(), StrokeCap.Round
        )
    }
    val r = outer * 0.70f
    // Drop shadow and knurled rim
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), c + Offset(0f, r * 0.12f), r * 1.18f), r * 1.18f, c + Offset(0f, r * 0.12f))
    drawCircle(Color(0xFF1A1C20), r, c)
    for (i in 0 until 60) {
        val deg = i * 6f
        drawLine(if (i % 2 == 0) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.5f), polar(r * 0.90f, deg), polar(r, deg), 1.2f)
    }
    // Brushed cap: an anisotropic sweep (two bright bands opposite each other) plus fine lathe rings
    val cap = r * 0.86f
    drawCircle(
        Brush.sweepGradient(
            listOf(0xFF3B3E44, 0xFF9AA0A8, 0xFF44474D, 0xFF202226, 0xFF3B3E44, 0xFF8C9199, 0xFF3E4147, 0xFF1E2024, 0xFF3B3E44).map { Color(it) }, c
        ),
        cap, c
    )
    var rr = cap
    while (rr > cap * 0.1f) { drawCircle(Color.White.copy(alpha = 0.018f), rr, c, style = Stroke(0.6f)); rr -= 1.8f }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), c - Offset(cap * 0.35f, cap * 0.45f), cap * 0.9f), cap, c)
    drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.6f)), c - Offset(cap, cap), c + Offset(cap, cap)), cap, c, style = Stroke(1.2f))
    // Indicator notch with phosphor glow
    val deg = START + SWEEP * n
    drawLine(accent.copy(alpha = 0.3f), polar(cap * 0.38f, deg), polar(cap * 0.9f, deg), 5.dp.toPx(), StrokeCap.Round)
    drawLine(accent, polar(cap * 0.38f, deg), polar(cap * 0.9f, deg), 2.dp.toPx(), StrokeCap.Round)
}
