package com.clashplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clashplayer.model.Lyrics
import com.clashplayer.model.Track
import com.clashplayer.service.OutputClock
import com.clashplayer.service.PlaybackService
import com.clashplayer.ui.components.ControlArc
import com.clashplayer.ui.components.DiscArtwork
import com.clashplayer.ui.components.FormatHud
import com.clashplayer.ui.components.GlyphButton
import com.clashplayer.ui.components.MorphPlayButton
import com.clashplayer.ui.components.SyncedLyricsView
import com.clashplayer.ui.components.TactileSeekBar
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.theme.AmbientMeshBackdrop
import com.clashplayer.ui.theme.AuroraColors
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.InkMuted
import kotlinx.coroutines.launch
import java.util.Locale

private val SheetSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 300f)
private val LyricWeights = listOf(FontWeight.Light to FontWeight.Light, FontWeight.Bold to FontWeight.Medium, FontWeight.Black to FontWeight.Bold)

/**
 * Full-screen player. It rides on a spring (stiffness 300, damping 0.85): drag it down and it follows the finger,
 * releasing past a quarter of the screen (or with a downward flick) dismisses it, otherwise it springs home.
 * The artwork morphs into the corner when lyrics take the stage. All motion is in graphicsLayer.
 */
@Composable
fun NowPlayingSheet(
    expanded: Boolean,
    track: Track?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    lyrics: Lyrics?,
    colors: AuroraColors,
    outputClock: () -> OutputClock,
    energy: () -> Float,
    shuffle: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onDismiss: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (track == null) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = with(LocalDensity.current) { maxHeight.toPx() }
        val offset = remember { Animatable(if (expanded) 0f else height) }
        var shown by remember { mutableStateOf(expanded) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(expanded) {
            if (expanded) shown = true
            offset.animateTo(if (expanded) 0f else height, SheetSpring)
            if (!expanded) shown = false
        }
        BackHandler(expanded) { onDismiss() }
        if (!shown) return@BoxWithConstraints

        var lyricsMode by rememberSaveable { mutableStateOf(false) }
        var weightIndex by rememberSaveable { mutableIntStateOf(1) }
        val stage = remember { Animatable(0f) }
        LaunchedEffect(lyricsMode) { stage.animateTo(if (lyricsMode) 1f else 0f, SheetSpring) }
        val scrub = remember { mutableFloatStateOf(Float.NaN) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = offset.value
                    // The sheet's top corners round off as it is pulled away
                    val d = (offset.value / height).coerceIn(0f, 1f)
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = (28 * d * 4).coerceAtMost(28f).dp, topEnd = (28 * d * 4).coerceAtMost(28f).dp)
                    clip = d > 0f
                }
                .pointerInput(height) {
                    val tracker = VelocityTracker()
                    var crossed = false
                    var pos = 0f // tracked here, so fast events never read a stale animated value
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking(); crossed = false; pos = offset.value },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            val dismiss = offset.value > height * 0.25f || v > 1800f
                            scope.launch {
                                if (dismiss) { offset.animateTo(height, SheetSpring, v); onDismiss() }
                                else offset.animateTo(0f, SheetSpring, v)
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, SheetSpring) } }
                    ) { change, dy ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        // Rubber-band above the top: resistance grows as it stretches
                        pos = (pos + if (pos + dy < 0) dy * 0.18f else dy).coerceAtLeast(-60f)
                        val target = pos
                        scope.launch { offset.snapTo(target) }
                        val past = pos > height * 0.25f
                        if (past != crossed) { crossed = past; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                }
        ) {
            AmbientMeshBackdrop(colors, energy, animate = isPlaying && expanded, modifier = Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
            ) {
                // Grabber notch and header: left-weighted label, controls hung on the right
                Box(Modifier.fillMaxWidth().height(18.dp).drawBehind {
                    drawLine(InkMuted, Offset(size.width / 2 - 18.dp.toPx(), size.height / 2), Offset(size.width / 2 + 18.dp.toPx(), size.height / 2), 3.dp.toPx(), StrokeCap.Round)
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NOW PLAYING", style = HudStyle)
                    Text("  /  ${track.album.uppercase(Locale.US)}", style = HudStyle.copy(color = InkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    GlyphButton(Icons.Rounded.Tune, "Audio studio", onOpenEqualizer, size = 40.dp, lit = true)
                }

                // Stage: artwork, or lyrics with the artwork tucked into the corner
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DiscArtwork(
                        track.albumArtUri, colors, track.title, isPlaying,
                        Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp)
                            .graphicsLayer {
                                val m = stage.value
                                val drag = (offset.value / height).coerceIn(0f, 1f)
                                val s = (1f - 0.72f * m) * (1f - 0.1f * drag)
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(0f, 0.5f - 0.5f * m)
                                alpha = 1f - 0.15f * m
                            }
                    )
                    if (lyricsMode || stage.value > 0.01f) {
                        val pair = LyricWeights[weightIndex]
                        if (lyrics != null && lyrics.lines.isNotEmpty()) {
                            SyncedLyricsView(
                                lyrics, currentPositionMs, onSeekTo,
                                Modifier
                                    .fillMaxSize()
                                    .padding(top = 64.dp)
                                    .graphicsLayer { alpha = stage.value; translationY = (1 - stage.value) * 48.dp.toPx() },
                                isPlaying = isPlaying, activeWeight = pair.first, idleWeight = pair.second
                            )
                        } else {
                            Text(
                                "No synced lyrics for this track.",
                                style = MaterialTheme.typography.bodyLarge, color = InkDim,
                                modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { alpha = stage.value }
                            )
                        }
                    }
                }

                // Title block: heavy title over a light artist line, format HUD under it
                Text(track.title, style = MaterialTheme.typography.headlineLarge, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FormatHud(outputClock, Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(8.dp))
                val fraction = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
                TactileSeekBar(
                    progress = { fraction },
                    onSeek = { onSeekTo((it * durationMs).toLong()) },
                    onScrub = { scrub.floatValue = it ?: Float.NaN },
                )
                Row {
                    val s = scrub.floatValue
                    val shownMs = if (s.isNaN()) currentPositionMs else (s * durationMs).toLong()
                    Text(formatDuration(shownMs), style = HudStyle.copy(color = if (s.isNaN()) InkDim else Ember))
                    Spacer(Modifier.weight(1f))
                    Text("−" + formatDuration((durationMs - shownMs).coerceAtLeast(0)), style = HudStyle)
                }

                ControlArc(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    GlyphButton(Icons.Rounded.Shuffle, "Shuffle", onToggleShuffle, active = shuffle)
                    GlyphButton(Icons.Rounded.SkipPrevious, "Previous", onSkipPrevious, size = 56.dp, lit = true)
                    MorphPlayButton(isPlaying, onPlayPause, energy)
                    GlyphButton(Icons.Rounded.SkipNext, "Next", onSkipNext, size = 56.dp, lit = true)
                    GlyphButton(
                        if (repeatMode == PlaybackService.REPEAT_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat", onCycleRepeat, active = repeatMode != PlaybackService.REPEAT_OFF
                    )
                }

                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TickSelector(listOf("Artwork", "Lyrics"), if (lyricsMode) 1 else 0, { lyricsMode = it == 1 })
                    Spacer(Modifier.weight(1f))
                    if (lyricsMode) {
                        Text(
                            "Aa ${listOf("LIGHT", "REGULAR", "HEAVY")[weightIndex]}",
                            style = HudStyle.copy(color = Ink),
                            modifier = Modifier
                                .clickable(remember { MutableInteractionSource() }, indication = null) { weightIndex = (weightIndex + 1) % LyricWeights.size }
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}

