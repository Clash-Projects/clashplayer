package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashplayer.dsp.BiquadFilter
import com.clashplayer.dsp.model.EqualizerBand
import com.clashplayer.dsp.model.FilterType
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val F_LO = 20.0
private const val F_HI = 20_000.0
private const val POINTS = 160

/**
 * Console EQ: the summed response of the real filters drawn on a logarithmic axis above a row of faders.
 * Moving a band sends a local ripple through the curve around that band that decays as it settles.
 * [revision] must change whenever any band changes (bands are mutable).
 */
@Composable
fun EqualizerSliders(
    bands: List<EqualizerBand>,
    onBandGainChanged: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
    gainRangeDb: Float = 15f,
    revision: Int = 0,
    sampleRate: Int = 48_000,
) {
    val gains = bands.map { it.getGainDb() }
    val curve = remember(revision, bands.size, sampleRate) { responseCurve(bands, sampleRate) }
    val ripples = remember(bands.size) { List(bands.size) { Animatable(0f) } }
    val previous = remember(bands.size) { FloatArray(bands.size) { Float.NaN } }
    val active = remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        gains.forEachIndexed { i, g ->
            if (!previous[i].isNaN() && previous[i] != g) {
                scope.launch { ripples[i].snapTo(1f); ripples[i].animateTo(0f, tween(1100, easing = FastOutSlowInEasing)) }
            }
            previous[i] = g
        }
    }

    Column(modifier) {
        ResponsePlot(curve, bands, gainRangeDb, ripples, active)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            bands.forEachIndexed { i, band ->
                Fader(
                    gainDb = gains[i], rangeDb = gainRangeDb, label = formatFrequency(band.frequency),
                    onChange = { onBandGainChanged(i, it) }, onActive = { active.intValue = if (it) i else -1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun responseCurve(bands: List<EqualizerBand>, sampleRate: Int): FloatArray {
    val filter = BiquadFilter(sampleRate)
    val out = FloatArray(POINTS)
    for (b in bands) {
        if (!b.enabled) continue
        filter.configure(FilterType.fromId(b.type), b.frequency, b.getGainDb(), b.q)
        for (i in 0 until POINTS) out[i] += filter.magnitudeDb(freqAt(i)).toFloat()
    }
    return out
}

private fun freqAt(i: Int) = F_LO * (F_HI / F_LO).pow(i / (POINTS - 1.0))
private fun xOf(f: Double, w: Float) = (log10(f / F_LO) / log10(F_HI / F_LO)).toFloat() * w

@Composable
private fun ResponsePlot(curve: FloatArray, bands: List<EqualizerBand>, rangeDb: Float, ripples: List<Animatable<Float, *>>, active: androidx.compose.runtime.MutableIntState) {
    val measurer = rememberTextMeasurer()
    val label = HudStyle.copy(fontSize = 8.sp, color = InkMuted)
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .drawBehind {
                val w = size.width; val h = size.height
                val mid = h / 2
                fun y(db: Float) = mid - db / rangeDb * (h / 2 - 10.dp.toPx())
                // Inset screen
                drawRect(Brush.verticalGradient(listOf(Color(0xFF05060A), Color(0xFF0B0D12))))
                for (f in doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
                    val x = xOf(f, w)
                    val decade = f == 100.0 || f == 1000.0 || f == 10000.0
                    drawLine(Color.White.copy(alpha = if (decade) 0.07f else 0.035f), Offset(x, 0f), Offset(x, h), 1f)
                    if (decade) drawText(measurer, if (f >= 1000) "${(f / 1000).toInt()}K" else "${f.toInt()}", Offset(x + 3.dp.toPx(), h - 13.dp.toPx()), label)
                }
                for (db in floatArrayOf(-12f, -6f, 6f, 12f)) {
                    if (abs(db) > rangeDb) continue
                    drawLine(Color.White.copy(alpha = 0.035f), Offset(0f, y(db)), Offset(w, y(db)), 1f)
                    drawText(measurer, "%+d".format(Locale.US, db.toInt()), Offset(3.dp.toPx(), y(db) - 11.dp.toPx()), label)
                }
                drawLine(InkMuted.copy(alpha = 0.5f), Offset(0f, mid), Offset(w, mid), 1f)

                // Curve with ripples: a decaying travelling wave localized around each moved band
                val pts = Array(POINTS) { i ->
                    val lf = log10(freqAt(i))
                    var db = curve[i]
                    bands.forEachIndexed { b, band ->
                        val r = ripples[b].value
                        if (r > 0.001f) {
                            val d = (lf - log10(band.frequency.toDouble())).toFloat()
                            db += r * 2.4f * sin(d * 18f - (1 - r) * 16f) * exp(-(d / 0.22f) * (d / 0.22f))
                        }
                    }
                    Offset(w * i / (POINTS - 1), y(db.coerceIn(-rangeDb * 1.4f, rangeDb * 1.4f)))
                }
                val line = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts) lineTo(p.x, p.y) }
                val fill = Path().apply { addPath(line); lineTo(w, mid); lineTo(0f, mid); close() }
                // Boost glows phosphor, cut glows ember, both strongest a few dB from 0 where most curves live
                drawPath(fill, Brush.verticalGradient(0.2f to Phosphor.copy(alpha = 0.38f), 0.5f to Phosphor.copy(alpha = 0.10f), 0.5f to Ember.copy(alpha = 0.10f), 0.8f to Ember.copy(alpha = 0.38f)))
                drawPath(line, Phosphor.copy(alpha = 0.18f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                drawPath(line, Phosphor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                // Band nodes; the one under the finger turns ember
                bands.forEachIndexed { b, band ->
                    val x = xOf(band.frequency.toDouble(), w)
                    val i = ((x / w) * (POINTS - 1)).roundToInt().coerceIn(0, POINTS - 1)
                    val hot = active.intValue == b
                    drawCircle(Color(0xFF05060A), (if (hot) 6 else 4).dp.toPx(), Offset(x, pts[i].y))
                    drawCircle(if (hot) Ember else Ink.copy(alpha = 0.8f), (if (hot) 6 else 4).dp.toPx(), Offset(x, pts[i].y), style = Stroke(1.2.dp.toPx()))
                }
            }
    )
}

@Composable
private fun Fader(gainDb: Float, rangeDb: Float, label: String, onChange: (Float) -> Unit, onActive: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(gainDb)
    val change by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%+.1f".format(Locale.US, gainDb), style = HudStyle.copy(fontSize = 9.sp, color = if (gainDb == 0f) InkMuted else Ink))
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .pointerInput(rangeDb) { detectTapGestures(onDoubleTap = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); change(0f) }) }
                .pointerInput(rangeDb) {
                    var raw = 0f
                    detectVerticalDragGestures(
                        onDragStart = { raw = current; onActive(true) },
                        onDragEnd = { onActive(false) }, onDragCancel = { onActive(false) }
                    ) { c, dy ->
                        c.consume()
                        val travel = size.height - 28.dp.toPx()
                        raw = (raw - dy / travel * rangeDb * 2).coerceIn(-rangeDb, rangeDb)
                        // Sticky 0 dB, then 0.5 dB steps
                        val v = if (abs(raw) < 0.6f) 0f else (raw * 2).roundToInt() / 2f
                        if (v != current && ((v / 3f).roundToInt() != (current / 3f).roundToInt() || v == 0f)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (v != current) change(v)
                    }
                }
                .drawBehind {
                    val cx = size.width / 2
                    val pad = 14.dp.toPx()
                    val travel = size.height - pad * 2
                    fun y(db: Float) = pad + travel * (1 - (db + rangeDb) / (2 * rangeDb))
                    // Scale: every 3 dB, 0 dB in ember
                    var db = -rangeDb
                    while (db <= rangeDb + 0.01f) {
                        val zero = abs(db) < 0.01f
                        drawLine(if (zero) Ember else InkMuted.copy(alpha = 0.5f), Offset(cx - (if (zero) 11 else 8).dp.toPx(), y(db)), Offset(cx - 4.dp.toPx(), y(db)), 1f)
                        db += 3f
                    }
                    // Slot: dark cut with a lit lower lip
                    drawLine(Color.Black, Offset(cx, pad), Offset(cx, size.height - pad), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(cx + 1.5.dp.toPx(), pad), Offset(cx + 1.5.dp.toPx(), size.height - pad), 0.7f)
                    // Phosphor fill from 0 dB to the cap
                    val capY = y(gainDb)
                    drawLine(if (gainDb >= 0) Phosphor else Ember, Offset(cx, y(0f)), Offset(cx, capY), 1.5.dp.toPx())
                    // Brushed fader cap with a glowing centre line
                    val cw = minOf(size.width * 0.72f, 30.dp.toPx()); val ch = 14.dp.toPx()
                    val tl = Offset(cx - cw / 2, capY - ch / 2)
                    drawRoundRect(Color.Black.copy(alpha = 0.6f), tl + Offset(0f, 3f), Size(cw, ch), CornerRadius(3.dp.toPx()))
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9AA0A8), Color(0xFF4A4E55), Color(0xFF2A2D32), Color(0xFF5A5E66)), tl.y, tl.y + ch), tl, Size(cw, ch), CornerRadius(3.dp.toPx()))
                    for (k in 1..3) drawLine(Color.Black.copy(alpha = 0.35f), Offset(tl.x + 3, tl.y + ch * k / 4.5f), Offset(tl.x + cw - 3, tl.y + ch * k / 4.5f), 0.6f)
                    drawLine(Phosphor.copy(alpha = 0.35f), Offset(tl.x + 2, capY), Offset(tl.x + cw - 2, capY), 4f)
                    drawLine(Phosphor, Offset(tl.x + 2, capY), Offset(tl.x + cw - 2, capY), 1.4f)
                }
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = HudStyle.copy(fontSize = 9.sp), modifier = Modifier.padding(horizontal = 1.dp))
    }
}

private fun formatFrequency(freqHz: Int): String = when {
    freqHz >= 1000 -> if (freqHz % 1000 == 0) "${freqHz / 1000}K" else "%.1fK".format(Locale.US, freqHz / 1000f)
    else -> "$freqHz"
}
