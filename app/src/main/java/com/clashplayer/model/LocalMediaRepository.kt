package com.clashplayer.model

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.util.Locale

object LocalMediaRepository {
    /** Extensions played by the app, including DSD, SACD ISO, and DTS-HD Master Audio. */
    val AUDIO_EXTENSIONS = setOf(
        "flac", "wav", "mp3", "m4a", "aac", "alac", "ogg", "oga", "opus",
        "aiff", "aif", "wma", "ape", "wv", "dsf", "dff", "iso", "sacd", "dts", "dtshd", "mka"
    )

    fun formatOf(fileName: String?, mime: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        return when {
            ext == "dsf" || ext == "dff" -> "DSD"
            ext == "iso" || ext == "sacd" -> "SACD ISO"
            ext == "dts" || ext == "dtshd" -> "DTS-HD MA"
            ext.isNotEmpty() && ext in AUDIO_EXTENSIONS -> ext.uppercase(Locale.US).replace("M4A", "AAC/ALAC")
            mime != null -> mime.substringAfter('/').uppercase(Locale.US)
            else -> "AUDIO"
        }
    }

    /** Everything Android's media library has indexed as music. */
    fun queryLocalTracks(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
        )
        val artBaseUri = Uri.parse("content://media/external/audio/albumart")
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val mime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val track = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val year = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                while (c.moveToNext()) {
                    val fileName = c.getString(name)
                    tracks += Track(
                        id = c.getLong(id),
                        title = c.getString(title) ?: fileName ?: "Unknown Title",
                        artist = c.getString(artist)?.takeUnless { it == "<unknown>" } ?: "Unknown Artist",
                        album = c.getString(album) ?: "Unknown Album",
                        durationMs = c.getLong(duration),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                        albumArtUri = ContentUris.withAppendedId(artBaseUri, c.getLong(albumId)),
                        format = formatOf(fileName, c.getString(mime)),
                        trackNumber = c.getInt(track) % 1000, // MediaStore encodes disc * 1000 + track
                        year = c.getInt(year),
                        fileName = fileName.orEmpty(),
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalMedia", "MediaStore query failed", e)
        }
        return tracks
    }

    /**
     * Every audio file under a folder the user picked (Storage Access Framework), tags read with the platform
     * retriever. Catches what the media scanner skips, such as DSF/DFF.
     */
    fun scanFolder(context: Context, tree: Uri): List<Track> {
        val out = mutableListOf<Track>()
        val cr = context.contentResolver
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            runCatching {
                cr.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { pending += docId; continue }
                        if (name.substringAfterLast('.', "").lowercase(Locale.US) !in AUDIO_EXTENSIONS) continue
                        out += fileTrack(context, DocumentsContract.buildDocumentUriUsingTree(tree, docId), name)
                    }
                }
            }
        }
        return out
    }

    private fun fileTrack(context: Context, uri: Uri, name: String): Track {
        val mmr = MediaMetadataRetriever()
        fun tag(key: Int) = runCatching { mmr.extractMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }
        val ok = runCatching { mmr.setDataSource(context, uri) }.isSuccess
        val track = Track(
            id = stableId(uri),
            title = (if (ok) tag(MediaMetadataRetriever.METADATA_KEY_TITLE) else null) ?: name.substringBeforeLast('.'),
            artist = (if (ok) tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) else null) ?: "Unknown Artist",
            album = (if (ok) tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) else null) ?: "Unknown Album",
            durationMs = (if (ok) tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() else null) ?: 0L,
            uri = uri,
            format = formatOf(name, if (ok) tag(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) else null),
            trackNumber = (if (ok) tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) else null)?.substringBefore('/')?.toIntOrNull() ?: 0,
            year = (if (ok) tag(MediaMetadataRetriever.METADATA_KEY_YEAR) else null)?.take(4)?.toIntOrNull() ?: 0,
            fileName = name,
        )
        runCatching { mmr.release() }
        return track
    }

    /** Ids for files outside MediaStore: negative, so they never collide with MediaStore's. */
    fun stableId(uri: Uri): Long = -(uri.toString().hashCode().toLong() and 0x7FFFFFFFL) - 1

    /** Playlists other apps saved in Android's media library (read-only here). */
    @Suppress("DEPRECATION") // still the only way to read existing system playlists
    fun queryDevicePlaylists(context: Context): List<Playlist> {
        val out = mutableListOf<Playlist>()
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME), null, null, MediaStore.Audio.Playlists.NAME)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val members = mutableListOf<Long>()
                    context.contentResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", id),
                        arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID), null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER)?.use { m ->
                        while (m.moveToNext()) members += m.getLong(0)
                    }
                    out += Playlist(id, c.getString(1) ?: "Playlist", members.size, members, readOnly = true)
                }
            }
        }
        return out
    }

    /**
     * Resolves M3U/M3U8 entries to library tracks by file name (paths in playlists rarely match on another device).
     * Returns the matched track ids in playlist order and the count of entries that matched nothing.
     */
    fun matchM3u(text: String, tracks: List<Track>): Pair<List<Long>, Int> {
        val byName = tracks.filter { it.fileName.isNotEmpty() }.groupBy { it.fileName.lowercase(Locale.US) }
        val ids = mutableListOf<Long>()
        var missing = 0
        text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { entry ->
            val name = Uri.decode(entry.replace('\\', '/').substringAfterLast('/')).lowercase(Locale.US)
            val hit = byName[name]?.firstOrNull()
            if (hit != null) ids += hit.id else missing++
        }
        return ids to missing
    }

    fun buildAlbums(tracks: List<Track>): List<Album> {
        return tracks.groupBy { it.album }.entries.mapIndexed { index, (albumName, albumTracks) ->
            Album(
                id = index.toLong() + 1L,
                title = albumName,
                artist = albumTracks.firstOrNull()?.artist ?: "Unknown Artist",
                albumArtUri = albumTracks.firstOrNull()?.albumArtUri,
                trackCount = albumTracks.size
            )
        }
    }

    fun buildArtists(tracks: List<Track>): List<Artist> {
        return tracks.groupBy { it.artist }.entries.mapIndexed { index, (artistName, artistTracks) ->
            val albumCount = artistTracks.map { it.album }.distinct().size
            Artist(
                id = index.toLong() + 1L,
                name = artistName,
                trackCount = artistTracks.size,
                albumCount = albumCount
            )
        }
    }
}
