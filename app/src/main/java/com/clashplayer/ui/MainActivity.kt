package com.clashplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.clashplayer.model.Album
import com.clashplayer.model.Artist
import com.clashplayer.model.LocalMediaRepository
import com.clashplayer.model.Playlist
import com.clashplayer.model.Track
import com.clashplayer.service.PlaybackService
import com.clashplayer.ui.screens.MainAppContainer
import com.clashplayer.ui.theme.ClashTheme
import com.clashplayer.ui.theme.Phosphor

class MainActivity : ComponentActivity() {

    private var playbackService by mutableStateOf<PlaybackService?>(null)
    private var isBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? PlaybackService.LocalBinder
            playbackService = localBinder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        loadMediaLibrary()
    }

    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var albums by mutableStateOf<List<Album>>(emptyList())
    private var artists by mutableStateOf<List<Artist>>(emptyList())
    private var playlists by mutableStateOf<List<Playlist>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        checkPermissionsAndLoadMedia()

        setContent {
            ClashTheme {
                val service = playbackService
                if (service != null) {
                    MainAppContainer(
                        service = service,
                        audioEngine = service.audioEngine,
                        tracks = tracks,
                        albums = albums,
                        artists = artists,
                        playlists = playlists
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Phosphor)
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndLoadMedia() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadMediaLibrary()
        } else {
            permissionLauncher.launch(permission)
            loadMediaLibrary()
        }
    }

    private fun loadMediaLibrary() {
        val loadedTracks = LocalMediaRepository.queryLocalTracks(this)
        tracks = loadedTracks
        albums = LocalMediaRepository.buildAlbums(loadedTracks)
        artists = LocalMediaRepository.buildArtists(loadedTracks)
        playlists = listOf(
            Playlist(1L, "Audiophile Masters", loadedTracks.size, loadedTracks.map { it.id }),
            Playlist(2L, "Hi-Res FLAC Favorites", loadedTracks.take(5).size, loadedTracks.take(5).map { it.id })
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
