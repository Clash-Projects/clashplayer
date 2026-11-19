package com.clashplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clashplayer.model.Track
import com.clashplayer.service.AudioDriverType
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.ObsidianRaised
import com.clashplayer.ui.theme.Phosphor
import java.util.Locale

/** Docked player: tap or flick up to open; a phosphor hairline along the top edge is the playhead. */
@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    progressFraction: Float,
    driverType: AudioDriverType,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSignalPath: (() -> Unit)? = null
) {
    if (track == null) return
    ObsidianPanel(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(onDragStart = { pulled = 0f }, onDragEnd = { if (pulled < -24.dp.toPx()) onClick() }) { c, dy -> pulled += dy; c.consume() }
            }
            .drawWithContent {
                drawContent()
                val p = progressFraction.coerceIn(0f, 1f)
                val x = size.width * p
                drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Phosphor), 0f, x.coerceAtLeast(1f)), Offset(0f, 0.75f), Offset(x, 0.75f), 1.5.dp.toPx(), StrokeCap.Round)
                drawCircle(Phosphor.copy(alpha = 0.35f), 4.dp.toPx(), Offset(x, 0.75f))
                drawCircle(Phosphor, 1.6.dp.toPx(), Offset(x, 0.75f))
            },
        backgroundColor = ObsidianRaised
    ) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF15171C))) {
                if (track.albumArtUri != null) {
                    AsyncImage(ImageRequest.Builder(LocalContext.current).data(track.albumArtUri).crossfade(true).build(), track.album,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Ember.copy(alpha = 0.7f), Color(0xFF15171C)))), contentAlignment = Alignment.Center) {
                        Text(track.title.take(1).uppercase(Locale.US), style = HudStyle.copy(color = Ink))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val direct = driverType == AudioDriverType.BIT_PERFECT_DIRECT
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (direct) Color(0xFF2E240E) else Phosphor.copy(alpha = 0.15f))
                            .clickable { onOpenSignalPath?.invoke() ?: onClick() }
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            if (direct) "BIT·XACT" else "PATH",
                            style = HudStyle.copy(fontSize = 9.sp, color = if (direct) Color(0xFFFFD700) else Phosphor)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            GlyphButton(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", onPlayPause, size = 44.dp, lit = true)
            GlyphButton(Icons.Rounded.SkipNext, "Next", onSkipNext, size = 40.dp)
        }
    }
}
