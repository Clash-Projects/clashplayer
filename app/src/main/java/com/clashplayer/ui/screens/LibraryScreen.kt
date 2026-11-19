package com.clashplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clashplayer.model.Album
import com.clashplayer.model.Artist
import com.clashplayer.model.Playlist
import com.clashplayer.model.Track
import com.clashplayer.ui.components.ArtworkTile
import com.clashplayer.ui.components.GlyphButton
import com.clashplayer.ui.components.LedgerRow
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import java.util.Locale

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    albums: List<Album>,
    artists: List<Artist>,
    playlists: List<Playlist>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var playlist by remember { mutableStateOf<Playlist?>(null) }

    val shown = remember(tracks, query, playlist) {
        val base = playlist?.let { p -> tracks.filter { it.id in p.trackIds } } ?: tracks
        if (query.isBlank()) base
        else base.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
    }
    // Opening an album, artist or playlist narrows the track ledger to it
    fun focus(q: String, p: Playlist? = null) { query = q; playlist = p; selectedTab = 0 }

    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Library", style = MaterialTheme.typography.displayLarge, color = Ink, modifier = Modifier.weight(1f))
            Text("${tracks.size} TR · ${albums.size} AL · ${artists.size} AR", style = HudStyle, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        SearchLine(query, { query = it; playlist = null })
        playlist?.let {
            Text("PLAYLIST · ${it.name.uppercase(Locale.US)}", style = HudStyle.copy(color = Phosphor),
                modifier = Modifier.padding(top = 10.dp).clickable { playlist = null })
        }
        TickSelector(listOf("Tracks", "Albums", "Artists", "Playlists"), selectedTab, { selectedTab = it }, Modifier.padding(top = 6.dp))

        val bottom = PaddingValues(bottom = 170.dp)
        when (selectedTab) {
            0 -> LazyColumn(contentPadding = bottom) {
                if (shown.isEmpty()) item { Text("Nothing matches.", style = MaterialTheme.typography.bodyLarge, color = InkDim, modifier = Modifier.padding(top = 24.dp)) }
                itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> LedgerRow(i, t, t.id == currentTrack?.id, isPlaying, { onTrackClick(t) }) }
            }
            1 -> LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(top = 16.dp, bottom = 170.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(albums, key = { it.id }) { album ->
                    Column(Modifier.clickable { focus(album.title) }) {
                        ArtworkTile(album.albumArtUri, album.title, Modifier.fillMaxWidth().aspectRatio(1f), monogramSize = 28)
                        Spacer(Modifier.height(8.dp))
                        Text(album.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${album.artist.uppercase(Locale.US)} · ${album.trackCount} TR", style = HudStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            2 -> LazyColumn(contentPadding = bottom) {
                itemsIndexed(artists, key = { _, a -> a.id }) { i, a -> IndexRow(i, a.name, "${a.albumCount} AL · ${a.trackCount} TR") { focus(a.name) } }
            }
            else -> LazyColumn(contentPadding = bottom) {
                if (playlists.isEmpty()) item { Text("No playlists yet.", style = MaterialTheme.typography.bodyLarge, color = InkDim, modifier = Modifier.padding(top = 24.dp)) }
                itemsIndexed(playlists, key = { _, p -> p.id }) { i, p -> IndexRow(i, p.name, "${p.trackCount} TR") { focus("", p) } }
            }
        }
    }
}

/** Underlined search: no box, just a rule that warms to phosphor while focused. */
@Composable
private fun SearchLine(query: String, onChange: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().drawBehind {
            val y = size.height
            drawLine(if (focused) Brush.horizontalGradient(listOf(Phosphor, Phosphor.copy(alpha = 0.1f))) else SolidColor(Color.White.copy(alpha = 0.12f)),
                Offset(0f, y), Offset(size.width, y), (if (focused) 1.5f else 1f).dp.toPx())
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (focused) Phosphor else InkMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (query.isEmpty()) Text("Title, artist or album", style = MaterialTheme.typography.bodyLarge, color = InkMuted)
            BasicTextField(query, onChange, singleLine = true, interactionSource = source,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink), cursorBrush = SolidColor(Phosphor), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) GlyphButton(Icons.Rounded.Close, "Clear", { onChange("") }, size = 36.dp)
    }
}

@Composable
private fun IndexRow(index: Int, title: String, meta: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("%02d".format(Locale.US, index + 1), style = HudStyle.copy(color = InkMuted), modifier = Modifier.width(30.dp))
        ArtworkTile(null, title, Modifier.size(42.dp), monogramSize = 10)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(meta, style = HudStyle)
    }
}
