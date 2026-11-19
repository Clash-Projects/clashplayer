package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.clashplayer.model.Lyrics
import com.clashplayer.model.LyricsLine
import com.clashplayer.ui.theme.Grotesk
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.Phosphor
import kotlinx.coroutines.flow.filter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Time-synced lyrics. The active line is lit by a luminous sweep that flows through it character by character
 * (wrapping across its visual lines), the list glides to each new line on a soft spring, and interludes show
 * three breathing dots that count down to the next line. Position is interpolated per frame between the
 * player's coarse updates, so the sweep is continuous; all of it runs in the draw phase.
 */
@Composable
fun SyncedLyricsView(
    lyrics: Lyrics,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    activeWeight: FontWeight = FontWeight.Bold,
    idleWeight: FontWeight = FontWeight.Medium,
    accent: Color = Phosphor,
) {
    val listState = rememberLazyListState()
    val active = lyrics.currentLineIndex(currentPositionMs)

    // Smooth clock: anchor on each coarse update and extrapolate while playing
    val anchorPos = remember { mutableLongStateOf(currentPositionMs) }
    val anchorNanos = remember { mutableLongStateOf(System.nanoTime()) }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentPositionMs) { anchorPos.longValue = currentPositionMs; anchorNanos.longValue = System.nanoTime() }
    LaunchedEffect(isPlaying) { if (isPlaying) while (true) withFrameNanos { frame.longValue = it } }
    val playing by rememberUpdatedState(isPlaying)
    val smoothPos = {
        frame.longValue
        if (!playing) anchorPos.longValue
        else anchorPos.longValue + ((System.nanoTime() - anchorNanos.longValue) / 1_000_000L).coerceIn(0L, 600L)
    }

    // Glide to the active line unless the user scrolled in the last 3 s
    var userScrolledAt by remember { mutableStateOf(0L) }
    // Set while the view drives the list itself, so its own scrolling isn't mistaken for the user's
    val autoScrolling = remember { booleanArrayOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect {
            if (!autoScrolling[0]) userScrolledAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(active) {
        if (active < 0 || System.currentTimeMillis() - userScrolledAt < 3000) return@LaunchedEffect
        val info = listState.layoutInfo
        val target = info.viewportSize.height * 0.32f
        val item = info.visibleItemsInfo.firstOrNull { it.index == active }
        autoScrolling[0] = true
        try {
            if (item == null) listState.scrollToItem((active - 2).coerceAtLeast(0))
            val now = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active } ?: return@LaunchedEffect
            // Low stiffness and a touch of overshoot: the text eases in and settles rather than snapping
            listState.animateScrollBy(now.offset - listState.layoutInfo.viewportStartOffset - target, spring(dampingRatio = 0.78f, stiffness = 110f))
        } finally {
            autoScrolling[0] = false
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 120.dp, bottom = 280.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { i, l -> "$i-${l.timeMs}" }) { index, line ->
            val next = lyrics.lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 5000)
            val distance = if (active < 0) 3 else abs(index - active)
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Lines dissolve into the top and bottom edges; read here so scrolling only re-layers
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                        alpha = if (item == null || info.viewportSize.height == 0) 1f else {
                            val h = info.viewportSize.height.toFloat()
                            // offset is measured from the content start (after top padding), not the viewport edge
                            val mid = (item.offset - info.viewportStartOffset + item.size / 2f) / h
                            minOf(((mid - 0.02f) / 0.16f), ((0.98f - mid) / 0.22f)).coerceIn(0f, 1f)
                        }
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSeek(line.timeMs) }
                    .padding(horizontal = 4.dp, vertical = 10.dp)
            ) {
                if (line.isInterlude) {
                    InterludeDots(line.timeMs, next, smoothPos, accent, visible = index == active)
                } else {
                    LyricLine(line, next, index == active, distance, smoothPos, activeWeight, idleWeight, accent)
                }
            }
        }
    }
}

@Composable
private fun LyricLine(
    line: LyricsLine, nextMs: Long, isActive: Boolean, distance: Int, pos: () -> Long,
    activeWeight: FontWeight, idleWeight: FontWeight, accent: Color,
) {
    val emphasis = remember { Animatable(if (isActive) 1f else 0f) }
    LaunchedEffect(isActive) { emphasis.animateTo(if (isActive) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 220f)) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        line.text,
        style = TextStyle(fontFamily = Grotesk, fontWeight = if (isActive) activeWeight else idleWeight, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em, color = Ink.copy(alpha = 0.42f)),
        onTextLayout = { layout = it },
        modifier = Modifier
            .graphicsLayer {
                val e = emphasis.value
                // Idle lines recede with distance; the active one leans forward
                alpha = if (isActive || e > 0.01f) 0.42f + 0.58f * e else (0.42f - 0.08f * (distance - 1)).coerceAtLeast(0.12f)
                val s = 0.94f + 0.06f * e
                scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .drawWithContent {
                drawContent()
                val tl = layout ?: return@drawWithContent
                val e = emphasis.value
                if (e < 0.01f) return@drawWithContent
                // Luminous flow: map time to a character position, then light every visual line up to it
                val p = ((pos() - line.timeMs).toFloat() / (nextMs - line.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                val chars = line.text.length * p
                val soft = 28.dp.toPx()
                for (l in 0 until tl.lineCount) {
                    val start = tl.getLineStart(l); val end = tl.getLineEnd(l)
                    val top = tl.getLineTop(l); val bottom = tl.getLineBottom(l)
                    val left = tl.getLineLeft(l); val right = tl.getLineRight(l)
                    val x = when {
                        chars >= end -> right + soft
                        chars <= start -> left - soft
                        else -> {
                            val ci = chars.toInt().coerceIn(start, end - 1)
                            val box = tl.getBoundingBox(ci)
                            box.left + box.width * (chars - ci)
                        }
                    }
                    val edge = x - soft / 2
                    if (edge > 0f) clipRect(0f, top, edge, bottom) { drawText(tl, color = Ink.copy(alpha = e)) }
                    clipRect(edge.coerceAtLeast(0f), top, x + soft / 2, bottom) {
                        drawText(tl, brush = Brush.horizontalGradient(listOf(Ink, accent.copy(alpha = 0.6f), Color.Transparent), edge, x + soft / 2), alpha = e)
                    }
                }
            }
    )
}

/**
 * Three dots that breathe like a slow inhale: each is a damped spring chasing a smooth breath curve (so it lags,
 * overshoots and settles rather than looping mechanically), lights in turn as the interlude counts down, and all
 * three swell and release just before the next line.
 */
@Composable
private fun InterludeDots(startMs: Long, endMs: Long, pos: () -> Long, accent: Color, visible: Boolean) {
    val scale = remember { FloatArray(3) { 1f } }
    val velocity = remember { FloatArray(3) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.033f)
            last = now
            val t = (pos() - startMs) / 1000f
            val p = ((pos() - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            for (i in 0..2) {
                val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * (t - i * 0.22f) / 2.6f)
                val finale = ((p - 0.9f) / 0.1f).coerceIn(0f, 1f)
                val target = 0.82f + 0.32f * breath + 0.5f * finale
                val a = 140f * (target - scale[i]) - 16f * velocity[i] // stiffness 140, damping ratio ~0.68
                velocity[i] += a * dt
                scale[i] += velocity[i] * dt
            }
            tick.longValue = now
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .drawBehind {
                tick.longValue
                val p = ((pos() - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                val r = 5.dp.toPx()
                for (i in 0..2) {
                    val lit = ((p * 3f) - i).coerceIn(0f, 1f) // each dot fills over its third of the countdown
                    val c = Offset(r * 2 + i * r * 4.2f, size.height / 2)
                    val s = scale[i]
                    drawCircle(accent.copy(alpha = 0.12f * lit), r * 2.6f * s, c)
                    drawCircle(Ink.copy(alpha = 0.22f + 0.78f * lit), r * s, c)
                }
            }
    )
}

