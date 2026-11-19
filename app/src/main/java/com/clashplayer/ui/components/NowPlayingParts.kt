package com.clashplayer.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clashplayer.service.OutputClock
import com.clashplayer.ui.theme.AuroraColors
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Album art laid over a laser-disc platter that peeks out from behind it. The platter spins up and down like a
 * turntable (no instant start/stop); its edge reflection is a separate layer that stays put while it turns.
 */
@Composable
fun DiscArtwork(artwork: Uri?, colors: AuroraColors, title: String, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val angle = remember { mutableFloatStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        var speed = 0f
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val target = if (playing) 200f else 0f // 33⅓ rpm in degrees per second
            speed += (target - speed) * (1 - exp(-(if (playing) 2.2f else 1.1f) * dt))
            angle.floatValue = (angle.floatValue + speed * dt) % 360f
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val art = min(maxWidth.value / 1.2f, maxHeight.value).dp
        val disc = art * 0.94f
        Box(Modifier.size(art * 1.2f, art)) {
            // Platter
            Box(
                Modifier
                    .size(disc)
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { rotationZ = angle.floatValue }
                    .drawBehind { drawPlatter(colors) }
            )
            // Fixed reflection across the platter's exposed edge
            Box(
                Modifier
                    .size(disc)
                    .align(Alignment.CenterEnd)
                    .drawBehind {
                        val r = size.minDimension / 2
                        drawArc(
                            Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent, Color.Transparent), center),
                            -70f, 80f, false, style = Stroke(r * 0.05f), topLeft = Offset(r * 0.03f, r * 0.03f),
                            size = androidx.compose.ui.geometry.Size(r * 1.94f, r * 1.94f)
                        )
                    }
            )
            // Sleeve: the artwork with a cast shadow and a top-edge specular line
            Box(
                Modifier
                    .size(art)
                    .align(Alignment.CenterStart)
                    .drawBehind {
                        for (i in 1..6) drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(i * 2f, i * 3f), size,
                            androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .drawWithContent {
                        drawContent()
                        drawLine(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)), Offset.Zero, Offset(size.width, 0f), 1.5f)
                        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), Offset.Zero, Offset(size.width * 0.5f, size.height * 0.5f)))
                    }
            ) {
                if (artwork != null) {
                    AsyncImage(
                        ImageRequest.Builder(LocalContext.current).data(artwork).crossfade(true).build(), title,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.dominant.copy(alpha = 0.55f), colors.tertiary, Color(0xFF0B0C10)))),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Text(title.take(2).uppercase(), style = HudStyle.copy(fontSize = 54.sp, fontWeight = FontWeight(800), color = Ink.copy(alpha = 0.85f)),
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlatter(colors: AuroraColors) {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Color(0xFF07080B), r, c)
    // Iridescent data layer: a faint spectral sweep that turns with the disc
    drawCircle(
        Brush.sweepGradient(listOf(Phosphor, Color(0xFF6A7CFF), Ember, Color(0xFFE5FF7A), Phosphor).map { it.copy(alpha = 0.30f) }, c),
        r * 0.97f, c
    )
    var rr = r * 0.96f
    var k = 0
    while (rr > r * 0.36f) {
        drawCircle(Color.Black.copy(alpha = if (k % 3 == 0) 0.35f else 0.15f), rr, c, style = Stroke(0.8f))
        rr -= 2.2f; k++
    }
    drawCircle(Color.White.copy(alpha = 0.22f), r * 0.985f, c, style = Stroke(1.5f))
    // Label and spindle
    drawCircle(Brush.radialGradient(listOf(colors.dominant, colors.tertiary), c, r * 0.32f), r * 0.32f, c)
    drawLine(Color.Black.copy(alpha = 0.25f), Offset(c.x - r * 0.3f, c.y), Offset(c.x - r * 0.12f, c.y), 2f, StrokeCap.Round)
    drawCircle(Color(0xFF07080B), r * 0.035f, c)
}

/**
 * The format badge: a status LED (phosphor when bit-perfect, ember when the signal is processed) and the stream's
 * format. Tapping it opens the live DAC clock monitor and Live Signal Path Inspector.
 */
@Composable
fun FormatHud(
    clock: () -> OutputClock,
    modifier: Modifier = Modifier,
    onOpenSignalPath: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val snapshot by produceState(clock()) { while (true) { value = clock(); delay(if (open) 250 else 1000) } }
    Column(modifier) {
        Row(
            Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    onOpenSignalPath?.invoke() ?: run { open = !open }
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val led = if (snapshot.bitPerfect) Phosphor else Ember
            Box(Modifier.size(6.dp).drawBehind {
                drawCircle(led.copy(alpha = 0.3f), size.minDimension)
                drawCircle(led)
            })
            Spacer(Modifier.width(8.dp))
            Text(snapshot.source.uppercase(Locale.US), style = HudStyle.copy(color = Ink))
            Text("  ·  ${snapshot.transport.uppercase(Locale.US)}", style = HudStyle)
            if (snapshot.bitPerfect) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2E240E))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("BIT·XACT", style = HudStyle.copy(color = Color(0xFFFFD700)))
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (snapshot.bitPerfect) Phosphor.copy(alpha = 0.15f) else Ember.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("PATH ➔", style = HudStyle.copy(color = if (snapshot.bitPerfect) Phosphor else Ember, fontSize = 9.sp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "▴" else "▾",
                style = HudStyle.copy(color = InkMuted),
                modifier = Modifier.clickable { open = !open }.padding(horizontal = 4.dp)
            )
        }
        AnimatedVisibility(open, enter = expandVertically(spring(0.8f, 380f)) + fadeIn(), exit = shrinkVertically(spring(0.9f, 500f)) + fadeOut()) {
            ClockMonitor(snapshot, Modifier.padding(top = 8.dp), onOpenSignalPath = onOpenSignalPath)
        }
    }
}

@Composable
private fun ClockMonitor(
    clock: OutputClock,
    modifier: Modifier = Modifier,
    onOpenSignalPath: (() -> Unit)? = null
) {
    // Drift history, newest last; 64 samples at 4 Hz is the last 16 s
    val history = remember { FloatArray(64) { Float.NaN } }
    val head = remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(clock)
    LaunchedEffect(Unit) {
        while (true) {
            history[head.intValue % history.size] = latest.driftPpm?.toFloat() ?: Float.NaN
            head.intValue++
            delay(250)
        }
    }
    val cursor = remember { mutableFloatStateOf(Float.NaN) }
    ObsidianPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DAC CLOCK MONITOR", style = HudStyle.copy(color = Ink))
                Spacer(Modifier.weight(1f))
                Text(if (clock.measuredHz != null) "LIVE" else "NOMINAL", style = HudStyle.copy(color = if (clock.measuredHz != null) Phosphor else InkMuted))
            }
            Spacer(Modifier.height(12.dp))
            val rows = listOf(
                "PATH" to clock.path,
                "SYNC" to clock.sync,
                "FORMAT" to "${clock.bits}-bit · ${clock.channels} ch · ${clock.transport}",
                "NOMINAL" to "%,d Hz".format(Locale.US, clock.nominalHz),
                "MEASURED" to (clock.measuredHz?.let { "%,.2f Hz".format(Locale.US, it) } ?: "awaiting feedback"),
                "DRIFT" to (clock.driftPpm?.let { "%+.1f ppm".format(Locale.US, it) } ?: "—"),
            )
            rows.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(k, style = HudStyle, modifier = Modifier.width(92.dp))
                    Text(v, style = HudStyle.copy(color = Ink, fontWeight = FontWeight(400)))
                }
            }
            if (onOpenSignalPath != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Phosphor.copy(alpha = 0.15f))
                        .clickable(onClick = onOpenSignalPath)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("INSPECT LIVE SIGNAL PATH", style = HudStyle.copy(color = Phosphor))
                }
            }
            Spacer(Modifier.height(12.dp))
            // Drift trace: drag across it to read any moment of the last 16 s
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            cursor.floatValue = down.position.x / size.width
                            drag(down.id) { cursor.floatValue = (it.position.x / size.width).coerceIn(0f, 1f); it.consume() }
                            cursor.floatValue = Float.NaN
                        }
                    }
                    .drawBehind {
                        head.intValue // redraw on new samples
                        val n = history.size
                        val values = FloatArray(n) { history[(head.intValue + it) % n] }
                        val span = maxOf(5f, values.filter { !it.isNaN() }.maxOfOrNull { abs(it) } ?: 0f) * 1.2f
                        val mid = size.height / 2
                        drawLine(InkMuted.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 0.6f)
                        val path = Path()
                        var started = false
                        values.forEachIndexed { i, v ->
                            if (v.isNaN()) return@forEachIndexed
                            val p = Offset(size.width * i / (n - 1), mid - v / span * mid)
                            if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
                        }
                        drawPath(path, Phosphor.copy(alpha = 0.25f), style = Stroke(4.dp.toPx()))
                        drawPath(path, Phosphor, style = Stroke(1.2.dp.toPx()))
                        val cx = cursor.floatValue
                        if (!cx.isNaN()) {
                            val i = (cx * (n - 1)).toInt().coerceIn(0, n - 1)
                            val x = size.width * i / (n - 1)
                            drawLine(Ember, Offset(x, 0f), Offset(x, size.height), 1f)
                            if (!values[i].isNaN()) drawCircle(Ember, 3.dp.toPx(), Offset(x, mid - values[i] / span * mid))
                        }
                    }
            )
            CursorReadout(cursor, history, head)
        }
    }
}

@Composable
private fun CursorReadout(cursor: androidx.compose.runtime.MutableFloatState, history: FloatArray, head: androidx.compose.runtime.MutableIntState) {
    val c = cursor.floatValue
    val text = if (c.isNaN()) "DRAG THE TRACE TO INSPECT" else {
        val n = history.size
        val i = (c * (n - 1)).toInt().coerceIn(0, n - 1)
        val v = history[(head.intValue + i) % n]
        val ago = (n - 1 - i) / 4f
        (if (v.isNaN()) "NO DATA" else "%+.2f PPM".format(Locale.US, v)) + "  @  -%.1f S".format(Locale.US, ago)
    }
    Text(text, style = HudStyle.copy(color = if (c.isNaN()) InkMuted else Ember), modifier = Modifier.padding(top = 6.dp))
}

