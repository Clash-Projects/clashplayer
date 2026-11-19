package com.clashplayer.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.CornerRadius
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
 * Clean, modern square cover artwork with ambient depth lighting, smooth rounded corners,
 * and high-fidelity elevation. No spinning CD platters or artificial turntable discs.
 */
@Composable
fun DiscArtwork(artwork: Uri?, colors: AuroraColors, title: String, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.94f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
        label = "playScale"
    )

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val size = min(maxWidth.value, maxHeight.value).dp
        val cornerRadius = 18.dp

        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
                // Ambient colorful glow behind the artwork
                .drawBehind {
                    val cr = cornerRadius.toPx()
                    drawRoundRect(
                        color = colors.dominant.copy(alpha = 0.28f),
                        topLeft = Offset(0f, 16.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(this.size.width, this.size.height),
                        cornerRadius = CornerRadius(cr + 4f, cr + 4f)
                    )
                    // Deep elevation drop shadow
                    for (i in 1..4) {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.20f),
                            topLeft = Offset(0f, i * 3f),
                            size = androidx.compose.ui.geometry.Size(this.size.width, this.size.height),
                            cornerRadius = CornerRadius(cr, cr)
                        )
                    }
                }
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color(0xFF0F1117))
                .drawWithContent {
                    drawContent()
                    // Refined top-edge specular glass rim
                    drawLine(
                        Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.40f),
                                Color.White.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        ),
                        Offset.Zero,
                        Offset(this.size.width, 0f),
                        1.5.dp.toPx()
                    )
                    // Subtle perimeter border
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.08f),
                        topLeft = Offset.Zero,
                        size = this.size,
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(1.dp.toPx())
                    )
                }
        ) {
            if (artwork != null) {
                AsyncImage(
                    ImageRequest.Builder(LocalContext.current)
                        .data(artwork)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    colors.dominant.copy(alpha = 0.65f),
                                    colors.tertiary.copy(alpha = 0.45f),
                                    Color(0xFF090A0E)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            title.take(2).uppercase(Locale.US),
                            style = HudStyle.copy(
                                fontSize = 48.sp,
                                fontWeight = FontWeight(800),
                                color = Ink.copy(alpha = 0.9f)
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "HI-RES LOSSLESS",
                            style = HudStyle.copy(
                                fontSize = 10.sp,
                                color = Phosphor.copy(alpha = 0.8f)
                            )
                        )
                    }
                }
            }
        }
    }
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
                    Text("BIT·PERFECT", style = HudStyle.copy(color = Color(0xFFFFD700)))
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

