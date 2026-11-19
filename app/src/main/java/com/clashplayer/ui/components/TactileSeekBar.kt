package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/**
 * Hand-drawn scrubber. The trail behind the playhead glows, the thumb swells with drag speed (and settles on a
 * spring when the finger slows), and crossing each of [detents] steps ticks the haptic motor.
 * [progress] is read only while drawing; [onScrub] reports the live position (null when released).
 */
@Composable
fun TactileSeekBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Float?) -> Unit = {},
    detents: Int = 20,
    accent: Color = Phosphor,
    height: Dp = 36.dp,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scrub = remember { mutableFloatStateOf(Float.NaN) }
    val swell = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val seek by rememberUpdatedState(onSeek)
    val report by rememberUpdatedState(onScrub)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(detents) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val tracker = VelocityTracker()
                    fun at(x: Float) = (x / w).coerceIn(0f, 1f)
                    var lastDetent = floor(at(down.position.x) * detents)
                    scrub.floatValue = at(down.position.x)
                    report(scrub.floatValue)
                    scope.launch { press.animateTo(1f, spring(stiffness = Spring.StiffnessHigh)) }
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    horizontalDrag(down.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val f = at(change.position.x)
                        scrub.floatValue = f
                        report(f)
                        val d = floor(f * detents)
                        if (d != lastDetent) { lastDetent = d; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        // ~2.5 widths/s of drag reads as a full swell
                        val v = abs(tracker.calculateVelocity().x) / (w * 2.5f)
                        scope.launch { swell.animateTo(v.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 900f)) }
                        change.consume()
                    }
                    seek(scrub.floatValue)
                    scrub.floatValue = Float.NaN
                    report(null)
                    scope.launch { swell.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
                    scope.launch { press.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                }
            }
            .drawBehind {
                val s = scrub.floatValue
                val p = (if (s.isNaN()) progress() else s).coerceIn(0f, 1f)
                val cy = size.height / 2
                val w = size.width
                val x = w * p
                val track = 2.dp.toPx()
                // Engraved groove: a dark cut with a hairline of light under it
                drawLine(Color.Black.copy(alpha = 0.55f), Offset(0f, cy), Offset(w, cy), track + 1.dp.toPx(), StrokeCap.Round)
                drawLine(InkMuted.copy(alpha = 0.35f), Offset(0f, cy + 1.dp.toPx()), Offset(w, cy + 1.dp.toPx()), 0.6.dp.toPx())
                for (i in 1 until detents) {
                    val tx = w * i / detents
                    val major = i % 5 == 0
                    drawLine(InkMuted.copy(alpha = if (tx < x) 0f else if (major) 0.5f else 0.22f),
                        Offset(tx, cy - (if (major) 5 else 3).dp.toPx()), Offset(tx, cy - track), 1.dp.toPx())
                }
                // Glowing trail: stacked translucent strokes fake a bloom without a blur pass
                val trail = Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent), startX = 0f, endX = x.coerceAtLeast(1f))
                for ((wd, a) in arrayOf(10f to 0.07f, 6f to 0.12f, 3.5f to 0.25f)) {
                    drawLine(trail, Offset(0f, cy), Offset(x, cy), wd.dp.toPx(), StrokeCap.Round, alpha = a + 0.1f * press.value)
                }
                drawLine(trail, Offset(0f, cy), Offset(x, cy), track, StrokeCap.Round)
                // Thumb: grows with velocity and press, with a halo ring
                val r = (4f + 3f * press.value + 7f * swell.value).dp.toPx()
                drawCircle(accent.copy(alpha = 0.18f + 0.2f * swell.value), r * 2.1f, Offset(x, cy))
                drawCircle(Brush.radialGradient(listOf(Ink, accent), Offset(x - r * 0.3f, cy - r * 0.3f), r * 1.4f), r, Offset(x, cy))
            }
    )
}
