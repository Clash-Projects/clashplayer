package com.clashplayer.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The music library: Android's media index, plus folders the user added, plus playlists (the app's own, saved here,
 * and read-only system ones). Scans run off the main thread; [state] publishes the result.
 */
class MediaLibrary(private val context: Context) {
    data class State(
        val tracks: List<Track> = emptyList(),
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val folders: List<Uri> = emptyList(),
        val scanning: Boolean = false,
        val message: String? = null,
    )

    private data class UserPlaylist(val id: Long, val name: String, val uris: List<String>)

    private val prefs = context.getSharedPreferences("clash_library", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(State(folders = loadFolders()))
    val state: StateFlow<State> = _state.asStateFlow()
    private var userPlaylists = loadPlaylists()
    private var devicePlaylists = emptyList<Playlist>()

    /** Rescans everything. */
    fun refresh() {
        _state.value = _state.value.copy(scanning = true)
        scope.launch {
            val indexed = LocalMediaRepository.queryLocalTracks(context)
            // shortcut: a folder file is taken as a duplicate of an indexed one by file name alone; fine for real libraries
            val known = indexed.mapTo(HashSet()) { it.fileName.lowercase(Locale.US) }
            val extra = _state.value.folders.flatMap { LocalMediaRepository.scanFolder(context, it) }
                .filter { it.fileName.lowercase(Locale.US) !in known }
                .distinctBy { it.id }
            val tracks = (indexed + extra).sortedBy { it.title.lowercase(Locale.US) }
            devicePlaylists = LocalMediaRepository.queryDevicePlaylists(context)
            publish(tracks, scanning = false)
        }
    }

    @Synchronized
    private fun publish(tracks: List<Track> = _state.value.tracks, scanning: Boolean = _state.value.scanning, message: String? = _state.value.message) {
        val byUri = tracks.associateBy { it.uri.toString() }
        val own = userPlaylists.map { p -> val ids = p.uris.mapNotNull { byUri[it]?.id }; Playlist(p.id, p.name, ids.size, ids) }
        _state.value = _state.value.copy(
            tracks = tracks,
            albums = LocalMediaRepository.buildAlbums(tracks),
            artists = LocalMediaRepository.buildArtists(tracks),
            playlists = own + devicePlaylists,
            scanning = scanning,
            message = message,
        )
    }

    fun addFolder(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val folders = (_state.value.folders + tree).distinct()
        saveFolders(folders)
        _state.value = _state.value.copy(folders = folders)
        refresh()
    }

    fun removeFolder(tree: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val folders = _state.value.folders - tree
        saveFolders(folders)
        _state.value = _state.value.copy(folders = folders)
        refresh()
    }

    @Synchronized
    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Long {
        val id = System.currentTimeMillis()
        userPlaylists = userPlaylists + UserPlaylist(id, name.ifBlank { "Playlist" }, tracks.map { it.uri.toString() })
        savePlaylists(); publish()
        return id
    }

    fun addToPlaylist(id: Long, track: Track) = editPlaylist(id) { if (track.uri.toString() in it) it else it + track.uri.toString() }

    fun removeFromPlaylist(id: Long, track: Track) = editPlaylist(id) { it - track.uri.toString() }

    @Synchronized
    fun renamePlaylist(id: Long, name: String) {
        userPlaylists = userPlaylists.map { if (it.id == id) it.copy(name = name) else it }
        savePlaylists(); publish()
    }

    @Synchronized
    fun deletePlaylist(id: Long) {
        userPlaylists = userPlaylists.filter { it.id != id }
        savePlaylists(); publish()
    }

    @Synchronized
    private fun editPlaylist(id: Long, edit: (List<String>) -> List<String>) {
        userPlaylists = userPlaylists.map { if (it.id == id) it.copy(uris = edit(it.uris)) else it }
        savePlaylists(); publish()
    }

    /** Imports an M3U/M3U8 file as a new playlist, matching entries to the library by file name. */
    fun importM3u(file: Uri) {
        scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(file)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null) { publish(message = "Couldn't read that playlist file"); return@launch }
            val name = context.contentResolver.query(file, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.substringBeforeLast('.') ?: "Imported"
            val tracks = _state.value.tracks
            val (ids, missing) = LocalMediaRepository.matchM3u(text, tracks)
            val byId = tracks.associateBy { it.id }
            createPlaylist(name, ids.mapNotNull(byId::get))
            publish(message = "Imported \"$name\": ${ids.size} tracks" + if (missing > 0) ", $missing not found in the library" else "")
        }
    }

    /** Writes [playlist] as M3U8 (file names, one per line) to [target]. */
    fun exportM3u(playlist: Playlist, target: Uri) {
        scope.launch {
            val byId = _state.value.tracks.associateBy { it.id }
            val body = buildString {
                appendLine("#EXTM3U")
                playlist.trackIds.mapNotNull(byId::get).forEach { t ->
                    appendLine("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}")
                    appendLine(t.fileName.ifEmpty { t.title })
                }
            }
            val ok = runCatching { context.contentResolver.openOutputStream(target)?.use { it.write(body.toByteArray()) } }.isSuccess
            publish(message = if (ok) "Exported \"${playlist.name}\"" else "Couldn't write the playlist file")
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    private fun loadFolders(): List<Uri> = runCatching {
        JSONArray(prefs.getString("folders", "[]")).let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } }
    }.getOrDefault(emptyList())

    private fun saveFolders(f: List<Uri>) = prefs.edit().putString("folders", JSONArray(f.map(Uri::toString)).toString()).apply()

    private fun loadPlaylists(): List<UserPlaylist> = runCatching {
        JSONArray(prefs.getString("playlists", "[]")).let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val u = o.getJSONArray("uris")
                UserPlaylist(o.getLong("id"), o.getString("name"), (0 until u.length()).map(u::getString))
            }
        }
    }.getOrDefault(emptyList())

    private fun savePlaylists() = prefs.edit().putString("playlists", JSONArray(userPlaylists.map {
        JSONObject().put("id", it.id).put("name", it.name).put("uris", JSONArray(it.uris))
    }).toString()).apply()
}
