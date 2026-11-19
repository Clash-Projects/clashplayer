package com.clashplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.model.Album
import com.clashplayer.model.Artist
import com.clashplayer.model.MediaLibrary
import com.clashplayer.model.Playlist
import com.clashplayer.model.Track
import com.clashplayer.service.PlaybackService
import com.clashplayer.ui.components.MiniPlayerBar
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.theme.AmbientMeshBackdrop
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Obsidian
import com.clashplayer.ui.theme.ObsidianRaised
import com.clashplayer.ui.theme.rememberArtworkColors
import java.util.Locale

enum class NavTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    EQUALIZER("Studio", Icons.Default.Equalizer),
    VISUALIZER("Visualizer", Icons.Rounded.GraphicEq)
}

@Composable
fun MainAppContainer(
    service: PlaybackService,
    audioEngine: AudioEngine,
    tracks: List<Track>,
    albums: List<Album>,
    artists: List<Artist>,
    playlists: List<Playlist>,
    modifier: Modifier = Modifier,
    library: MediaLibrary? = null
) {
    var selectedTab by remember { mutableStateOf(NavTab.HOME) }
    var isNowPlayingExpanded by remember { mutableStateOf(false) }
    var isSignalPathExpanded by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(service.isShuffle) }
    var repeatMode by remember { mutableIntStateOf(service.repeatMode) }

    val currentTrack by service.currentTrack.collectAsState()
    val isPlaying by service.isPlaying.collectAsState()
    val currentPositionMs by service.currentPositionMs.collectAsState()
    val durationMs by service.durationMs.collectAsState()
    val driverType by service.outputManager.selectedDriver.collectAsState()
    val currentLyrics by service.currentLyrics.collectAsState()
    val colors = rememberArtworkColors(currentTrack?.albumArtUri)
    val energy = remember(audioEngine) { { audioEngine.energy() } }
    val progressFraction = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(modifier.fillMaxSize()) {
        AmbientMeshBackdrop(colors, energy, animate = isPlaying && !isNowPlayingExpanded, modifier = Modifier.fillMaxSize())
        when (selectedTab) {
            NavTab.HOME -> FlowHomeScreen(
                recentTracks = tracks,
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                driverType = driverType,
                onTrackClick = { t -> service.setQueue(tracks, tracks.indexOfFirst { it.id == t.id }) },
                onOpenSearch = { selectedTab = NavTab.LIBRARY }
            )
            NavTab.LIBRARY -> LibraryScreen(
                tracks = tracks,
                albums = albums,
                artists = artists,
                playlists = playlists,
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                onTrackClick = { service.playTrack(it) },
                library = library,
                onPlayQueue = { list, i -> service.setQueue(list, i) }
            )
            NavTab.EQUALIZER -> EqualizerStudio(
                audioEngine = audioEngine,
                currentDriver = driverType,
                onDriverSelected = { service.switchAudioDriver(it) },
                onBack = { selectedTab = NavTab.HOME },
                settings = service.settings,
                onReplayGainChanged = service::refreshReplayGain,
                outputControls = remember(service) {
                    OutputControls(service.currentOutput, service.bluetoothCodecResult, service::profileFor, service::setProfile, service::applyOutputProfile)
                },
                onOpenSignalPath = { isSignalPathExpanded = true }
            )
            NavTab.VISUALIZER -> VisualizerScreen(
                audioEngine = audioEngine,
                onBack = { selectedTab = NavTab.HOME }
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Obsidian.copy(alpha = 0.85f),
                            Obsidian
                        )
                    )
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                MiniPlayerBar(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    progressFraction = progressFraction,
                    driverType = driverType,
                    onPlayPause = { if (isPlaying) service.pause() else service.resume() },
                    onSkipNext = { service.next() },
                    onClick = { isNowPlayingExpanded = true },
                    onOpenSignalPath = { isSignalPathExpanded = true },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                BottomRail(selectedTab, { selectedTab = it })
            }
        }

        NowPlayingSheet(
            expanded = isNowPlayingExpanded,
            track = currentTrack,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            lyrics = currentLyrics,
            colors = colors,
            outputClock = service::outputClock,
            energy = energy,
            shuffle = shuffle,
            repeatMode = repeatMode,
            onPlayPause = { if (isPlaying) service.pause() else service.resume() },
            onSkipNext = { service.next() },
            onSkipPrevious = { service.previous() },
            onSeekTo = { service.seekTo(it) },
            onToggleShuffle = { shuffle = !shuffle; service.isShuffle = shuffle },
            onCycleRepeat = { repeatMode = (repeatMode + 1) % 3; service.repeatMode = repeatMode },
            onDismiss = { isNowPlayingExpanded = false },
            onOpenEqualizer = {
                isNowPlayingExpanded = false
                selectedTab = NavTab.EQUALIZER
            },
            onOpenSignalPath = { isSignalPathExpanded = true }
        )

        SignalPathInspectorSheet(
            expanded = isSignalPathExpanded,
            track = currentTrack,
            clock = service.outputClock(),
            audioEngine = audioEngine,
            settings = service.settings,
            onDismiss = { isSignalPathExpanded = false }
        )
    }
}

/** Bottom rail: glyphs at rest; the selected one is lit with its name over an ember notch. */
@Composable
internal fun BottomRail(selectedTab: NavTab, onTabSelected: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    ObsidianPanel(modifier.fillMaxWidth().height(60.dp), backgroundColor = ObsidianRaised) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                Row(
                    Modifier
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onTabSelected(tab) }
                        .drawBehind {
                            if (selected) drawRect(Ember, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(tab.icon, tab.title, tint = if (selected) Ink else InkMuted, modifier = Modifier.size(22.dp))
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Text(tab.title.uppercase(Locale.US), style = HudStyle.copy(color = Ink))
                    }
                }
            }
        }
    }
}
