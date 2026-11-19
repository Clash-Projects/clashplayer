package com.clashplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clashplayer.model.Track
import com.clashplayer.service.AudioDriverType
import com.clashplayer.ui.components.ArtworkTile
import com.clashplayer.ui.components.GlyphButton
import com.clashplayer.ui.components.LedgerRow
import com.clashplayer.ui.components.SectionRule
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.Phosphor

@Composable
fun FlowHomeScreen(
    recentTracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    driverType: AudioDriverType,
    onTrackClick: (Track) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 170.dp)
    ) {
        item {
            // Masthead: heavy wordmark, then an honest one-line readout of the selected output
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Clash", style = MaterialTheme.typography.displayLarge, color = Ink)
                    val direct = driverType == AudioDriverType.BIT_PERFECT_DIRECT
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("●  ", style = HudStyle.copy(color = if (direct) Phosphor else Ember))
                        Text(
                            (if (direct) "DIRECT USB OUTPUT SELECTED" else "SYSTEM MIXER OUTPUT") + "  ·  ${recentTracks.size} TRACKS",
                            style = HudStyle
                        )
                    }
                }
                GlyphButton(Icons.Rounded.Search, "Search", onOpenSearch, size = 44.dp, lit = true)
            }
            Spacer(Modifier.height(36.dp))
        }

        if (recentTracks.isEmpty()) {
            item {
                Text("No music on this device yet.", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text("Copy audio files to the phone and they'll appear here.", style = MaterialTheme.typography.bodyLarge, color = InkDim)
            }
            return@LazyColumn
        }

        item {
            SectionRule(1, "Spotlight")
            Spacer(Modifier.height(16.dp))
            LazyRow(
                Modifier.fillMaxWidth().height(232.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(end = 24.dp)
            ) {
                itemsIndexed(recentTracks.take(10), key = { _, t -> t.id }) { i, track ->
                    Sleeve(track, track.id == currentTrack?.id, { onTrackClick(track) }, Modifier.offset(y = if (i % 2 == 1) 22.dp else 0.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            SectionRule(2, "All tracks")
            Spacer(Modifier.height(4.dp))
        }

        itemsIndexed(recentTracks, key = { _, t -> t.id }) { i, track ->
            LedgerRow(i, track, track.id == currentTrack?.id, isPlaying, { onTrackClick(track) })
        }
    }
}

/** Staggered record sleeve; the current one carries an ember notch along its top edge. */
@Composable
private fun Sleeve(track: Track, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(148.dp).clickable(onClick = onClick)) {
        ArtworkTile(
            track.albumArtUri, track.title,
            Modifier.size(148.dp).drawBehind {
                if (current) drawRect(Ember, Offset(0f, -6.dp.toPx()), Size(size.width * 0.3f, 2.dp.toPx()))
            }
        )
        Spacer(Modifier.height(10.dp))
        Text(track.title, style = MaterialTheme.typography.titleMedium, color = if (current) Phosphor else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
