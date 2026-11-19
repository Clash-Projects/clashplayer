package com.clashplayer.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clashplayer.model.Track
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Artwork, or a monogram sleeve when there is none: obsidian stock with a single tinted band whose hue is
 * derived from the title, so each record keeps a stable identity across the app.
 */
@Composable
fun ArtworkTile(uri: Uri?, title: String, modifier: Modifier = Modifier, corner: Dp = 4.dp, monogramSize: Int = 22) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color(0xFF101217))) {
        if (uri != null && uri != Uri.EMPTY) {
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build(), title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val hue = (abs(title.hashCode()) % 360).toFloat()
            val band = Color.hsv(hue, 0.55f, 0.75f)
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val y = size.height * (0.28f + (abs(title.hashCode() shr 8) % 40) / 100f)
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, band.copy(alpha = 0.55f), Color.Transparent), y - size.height * 0.22f, y + size.height * 0.22f))
                    drawLine(band, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.18f), Offset.Zero, Offset(size.width, 0f), 1f)
                }
            )
            Text(
                title.filter { it.isLetterOrDigit() }.take(2).uppercase(Locale.US),
                style = HudStyle.copy(fontSize = monogramSize.sp, color = Ink.copy(alpha = 0.9f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }
    }
}

/** Three level bars that dance while [playing]; drawn from the frame clock, no recomposition. */
@Composable
fun LevelBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Phosphor) {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) { if (playing) while (true) withFrameNanos { frame.longValue = it } }
    Box(modifier.size(14.dp).drawBehind {
        val t = frame.longValue / 1e9f
        val w = size.width / 5
        for (i in 0..2) {
            val h = if (playing) 0.3f + 0.7f * abs(sin(t * (5.1f + i * 1.7f) + i * 1.3f)) else 0.3f
            drawRect(color, Offset(i * 2 * w, size.height * (1 - h)), Size(w, size.height * h))
        }
    })
}

/**
 * A ruled ledger line for a track: mono index (level bars when it's the one playing), small sleeve, title over
 * artist, duration in mono. No card: rows are separated by a hairline, like entries in a log.
 */
@Composable
fun LedgerRow(index: Int, track: Track, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(30.dp)) {
            if (isCurrent) LevelBars(isPlaying)
            else Text("%02d".format(Locale.US, index + 1), style = HudStyle.copy(color = InkMuted))
        }
        ArtworkTile(track.albumArtUri, track.title, Modifier.size(42.dp), monogramSize = 10)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) Phosphor else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatMs(track.durationMs), style = HudStyle.copy(color = if (isCurrent) Ember else InkMuted))
    }
}

/** Mono section heading with a long rule: "01 — SPOTLIGHT ─────". */
@Composable
fun SectionRule(number: Int, title: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(Locale.US, number), style = HudStyle.copy(color = Ember))
        Text("  —  ${title.uppercase(Locale.US)}", style = HudStyle.copy(color = Ink))
        Box(Modifier.weight(1f).padding(start = 12.dp).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
    }
}

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}
