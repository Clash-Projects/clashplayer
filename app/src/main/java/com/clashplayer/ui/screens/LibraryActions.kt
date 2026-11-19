package com.clashplayer.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clashplayer.model.Playlist
import com.clashplayer.model.Track
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.ObsidianRaised
import com.clashplayer.ui.theme.Phosphor
import java.util.Locale

/** Mono caps text button, matching the HUD labels. */
@Composable
internal fun HudButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(text.uppercase(Locale.US), style = HudStyle.copy(color = if (enabled) Phosphor else InkMuted),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp))
}

@Composable
private fun ClashDialog(title: String, onDismiss: () -> Unit, confirm: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianRaised,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = content,
        confirmButton = { confirm?.invoke() },
        dismissButton = { HudButton("Close", onDismiss) },
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    ClashDialog(title, onDismiss, confirm = { HudButton("Save", { onDone(name.trim()) }, enabled = name.isNotBlank()) }) {
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Phosphor, cursorColor = Phosphor))
    }
}

@Composable
internal fun AddToPlaylistDialog(track: Track, playlists: List<Playlist>, inPlaylist: Playlist?, onDismiss: () -> Unit,
                                 onAdd: (Playlist) -> Unit, onNew: () -> Unit, onRemove: () -> Unit) {
    ClashDialog(track.title, onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (inPlaylist != null && !inPlaylist.readOnly) HudButton("Remove from ${inPlaylist.name}", onRemove)
            HudButton("+ New playlist with this track", onNew)
            Text("ADD TO", style = HudStyle.copy(color = InkMuted), modifier = Modifier.padding(top = 8.dp))
            val own = playlists.filter { !it.readOnly }
            if (own.isEmpty()) Text("No playlists yet.", color = InkDim, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(own, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onAdd(p) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (track.id in p.trackIds) "ADDED" else "${p.trackCount} TR", style = HudStyle)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistDialog(playlist: Playlist, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    ClashDialog(playlist.name, onDismiss) {
        Column {
            if (playlist.readOnly) Text("Saved by another app; it can be exported but not edited here.", color = InkDim, style = MaterialTheme.typography.bodySmall)
            HudButton("Export as M3U", onExport)
            if (!playlist.readOnly) {
                HudButton("Rename", onRename)
                Text("DELETE", style = HudStyle.copy(color = Ember), modifier = Modifier.clickable(onClick = onDelete).padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
internal fun FoldersDialog(folders: List<Uri>, onDismiss: () -> Unit, onAdd: () -> Unit, onRemove: (Uri) -> Unit) {
    ClashDialog("Music folders", onDismiss, confirm = { HudButton("+ Add folder", onAdd) }) {
        Column {
            Text("Scanned in addition to Android's media library. Use this for files it misses, such as DSF and DFF.",
                color = InkDim, style = MaterialTheme.typography.bodySmall)
            if (folders.isEmpty()) Text("None added.", color = InkMuted, modifier = Modifier.padding(top = 12.dp))
            folders.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Uri.decode(f.lastPathSegment ?: f.toString()).substringAfter(':'), color = Ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("REMOVE", style = HudStyle.copy(color = Ember), modifier = Modifier.clickable { onRemove(f) }.padding(start = 12.dp))
                }
            }
        }
    }
}
