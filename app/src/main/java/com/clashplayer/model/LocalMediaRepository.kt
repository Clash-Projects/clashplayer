package com.clashplayer.model

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

object LocalMediaRepository {

    fun queryLocalTracks(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                val artBaseUri = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Title"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val albumId = cursor.getLong(albumIdCol)
                    val duration = cursor.getLong(durationCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val albumArtUri = ContentUris.withAppendedId(artBaseUri, albumId)

                    tracks.add(
                        Track(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = duration,
                            uri = contentUri,
                            albumArtUri = albumArtUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return tracks
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
