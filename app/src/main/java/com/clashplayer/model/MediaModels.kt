package com.clashplayer.model

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri? = null,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val format: String = "FLAC",
    val bitrateKbps: Int = 1411,
    val trackNumber: Int = 1,
    val year: Int = 0,
    /** File name with extension; used to match playlist entries. */
    val fileName: String = ""
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val albumArtUri: Uri? = null
)

data class Artist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val albumCount: Int
)

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val trackIds: List<Long> = emptyList(),
    /** System playlists made by other apps are shown but not edited here. */
    val readOnly: Boolean = false
)
