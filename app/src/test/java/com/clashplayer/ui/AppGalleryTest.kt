package com.clashplayer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.model.EqualizerPreset
import com.clashplayer.model.LocalMediaRepository
import com.clashplayer.model.Lyrics
import com.clashplayer.model.Playlist
import com.clashplayer.model.Track
import com.clashplayer.service.AudioDriverType
import com.clashplayer.service.OutputClock
import com.clashplayer.ui.components.MiniPlayerBar
import com.clashplayer.ui.screens.BottomRail
import com.clashplayer.ui.screens.EqualizerStudio
import com.clashplayer.ui.screens.FlowHomeScreen
import com.clashplayer.ui.screens.LibraryScreen
import com.clashplayer.ui.screens.NavTab
import com.clashplayer.ui.screens.NowPlayingSheet
import com.clashplayer.ui.screens.VisualizerScreen
import com.clashplayer.ui.theme.AmbientMeshBackdrop
import com.clashplayer.ui.theme.AuroraColors
import com.clashplayer.ui.theme.ClashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Renders the app's main areas to build/gallery for a visual tour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class AppGalleryTest {
    @get:Rule val rule = createComposeRule()

    private val tracks = listOf(
        Track(1, "Glass Cathedral", "Mira Voss", "Afterlight Sessions", 254_000, Uri.EMPTY, sampleRate = 96000, bitDepth = 24),
        Track(2, "Low Orbit", "Kade Mercer", "Night Ferry", 201_000, Uri.EMPTY),
        Track(3, "Salt and Signal", "Oona Hale", "Coastline", 318_000, Uri.EMPTY),
        Track(4, "Paper Lanterns", "The Quiet Fields", "Coastline", 233_000, Uri.EMPTY),
        Track(5, "Velvet Static", "Mira Voss", "Afterlight Sessions", 276_000, Uri.EMPTY),
        Track(6, "Northbound", "Ilse Varga", "Tundra Lines", 342_000, Uri.EMPTY),
        Track(7, "Heliograph", "Kade Mercer", "Night Ferry", 189_000, Uri.EMPTY),
        Track(8, "Small Hours", "The Quiet Fields", "Porchlight", 265_000, Uri.EMPTY),
        Track(9, "Copper Moon", "Ilse Varga", "Tundra Lines", 298_000, Uri.EMPTY),
    )
    private val now = tracks[0]
    private val lyrics = Lyrics.parseLrc(
        """
        [00:20.00]We built the night out of mirrors
        [00:24.50]and every one of them was looking back
        [00:29.00]Hold the light a little longer
        [00:41.00]Glass remembers what we said
        [00:45.50]Even when the room goes quiet
        """.trimIndent()
    )
    private val usbClock = OutputClock("FLAC 24/96", "USB UAC2 direct", 96000, 96000.42, 24, 2, "Async feedback · High-speed", true, "PCM")
    private val dsdClock = OutputClock("DSD64", "USB UAC2 direct", 176400, 176400.18, 24, 2, "Async feedback · High-speed", true, "DoP")

    private fun shoot(name: String, content: @Composable () -> Unit, act: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent { ClashTheme { content() } }
        rule.mainClock.advanceTimeBy(1500)
        act()
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage("build/gallery/$name.png")
    }

    @Composable
    private fun Stage(energy: Float = 0.35f, content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) {
        AmbientMeshBackdrop(AuroraColors.Default, { energy }, animate = false, modifier = Modifier.fillMaxSize())
        content()
    }

    @Composable
    private fun Chrome(tab: NavTab) = Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            MiniPlayerBar(now, true, 0.38f, AudioDriverType.BIT_PERFECT_DIRECT, {}, {}, {}, Modifier.padding(bottom = 8.dp))
            BottomRail(tab, {})
        }
    }

    @Composable
    private fun Sheet(position: Long, clock: OutputClock = usbClock) = NowPlayingSheet(
        expanded = true, track = now, isPlaying = true, currentPositionMs = position, durationMs = now.durationMs, lyrics = lyrics,
        colors = AuroraColors.Default, outputClock = { clock }, energy = { 0.5f }, shuffle = false, repeatMode = 1,
        onPlayPause = {}, onSkipNext = {}, onSkipPrevious = {}, onSeekTo = {}, onToggleShuffle = {}, onCycleRepeat = {},
        onDismiss = {}, onOpenEqualizer = {}
    )

    private fun engine() = AudioEngine(96000).apply {
        applyPreset(EqualizerPreset.BUILT_IN_PRESETS.first { it.name == "Rock" })
        // A few seconds of a chord with a pulsing kick and some air, so the analyzers have something to show
        val rnd = Random(3)
        val buf = FloatArray(4096)
        for (block in 0 until 40) {
            for (i in 0 until buf.size / 2) {
                val t = (block * buf.size / 2 + i) / 96000.0
                val kick = if ((t * 2) % 1 < 0.12) sin(2 * PI * 55 * t) * 0.6 else 0.0
                val s = (0.22 * sin(2 * PI * 220 * t) + 0.16 * sin(2 * PI * 277 * t) + 0.12 * sin(2 * PI * 330 * t) + kick + 0.04 * (rnd.nextDouble() - 0.5)).toFloat()
                buf[2 * i] = s; buf[2 * i + 1] = s * 0.92f
            }
            processInterleavedStereo(buf, 0, buf.size)
        }
    }

    private val playlists = listOf(Playlist(1, "Late Drive", 4, tracks.take(4).map { it.id }), Playlist(2, "Morning Light", 3, tracks.drop(5).map { it.id }))

    @Test fun g01_home() = shoot("01_home", { Stage { FlowHomeScreen(tracks, now, true, AudioDriverType.BIT_PERFECT_DIRECT, {}, {}); Chrome(NavTab.HOME) } })

    @Test fun g02_libraryTracks() = shoot("02_library_tracks", { Stage { LibraryScreen(tracks, LocalMediaRepository.buildAlbums(tracks), LocalMediaRepository.buildArtists(tracks), playlists, now, true, {}); Chrome(NavTab.LIBRARY) } })

    @Test fun g03_libraryAlbums() = shoot("03_library_albums",
        { Stage { LibraryScreen(tracks, LocalMediaRepository.buildAlbums(tracks), LocalMediaRepository.buildArtists(tracks), playlists, now, true, {}); Chrome(NavTab.LIBRARY) } },
        { rule.onNodeWithText("ALBUMS").performClick() })

    @Test fun g04_libraryArtists() = shoot("04_library_artists",
        { Stage { LibraryScreen(tracks, LocalMediaRepository.buildAlbums(tracks), LocalMediaRepository.buildArtists(tracks), playlists, now, true, {}); Chrome(NavTab.LIBRARY) } },
        { rule.onNodeWithText("ARTISTS").performClick() })

    @Test fun g05_nowPlaying() = shoot("05_now_playing", { Sheet(96_000) })

    @Test fun g06_clockMonitor() = shoot("06_dac_clock_monitor", { Sheet(96_000) }, { rule.onNodeWithText("FLAC 24/96").performClick() })

    @Test fun g07_dsd() = shoot("07_dsd_dop_monitor", { Sheet(140_000, dsdClock) }, { rule.onNodeWithText("DSD64").performClick() })

    @Test fun g08_lyrics() = shoot("08_lyrics", { Sheet(26_000) }, { rule.onNodeWithText("LYRICS").performClick() })

    @Test fun g09_interlude() = shoot("09_lyrics_interlude", { Sheet(36_500) }, { rule.onNodeWithText("LYRICS").performClick() })

    @Test fun g10_studioEq() = shoot("10_studio_graphic_eq", { Stage { EqualizerStudio(engine(), AudioDriverType.BIT_PERFECT_DIRECT, {}, {}) } })

    @Test fun g11_studioTone() = shoot("11_studio_tone_space", { Stage { EqualizerStudio(engine(), AudioDriverType.BIT_PERFECT_DIRECT, {}, {}) } },
        { rule.onNodeWithText("TONE & SPACE").performClick() })

    @Test fun g12_studioOutput() = shoot("12_studio_output", { Stage { EqualizerStudio(engine(), AudioDriverType.BIT_PERFECT_DIRECT, {}, {}) } },
        { rule.onNodeWithText("OUTPUT").performClick() })

    @Test fun g13_visualizer() = shoot("13_visualizer", { Stage(0.7f) { VisualizerScreen(engine(), {}) } })

    @Test fun g14_miniPlayer() = shoot("14_home_scrolled_chrome", { Stage(0.6f) { Chrome(NavTab.HOME) } })
}
