package com.clashplayer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.clashplayer.dsp.model.EqualizerBand
import com.clashplayer.dsp.model.FilterType
import com.clashplayer.model.Lyrics
import com.clashplayer.model.Track
import com.clashplayer.service.AudioDriverType
import com.clashplayer.service.OutputClock
import com.clashplayer.ui.components.EqualizerSliders
import com.clashplayer.ui.components.MiniPlayerBar
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.components.RotaryKnob
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.screens.NowPlayingSheet
import com.clashplayer.ui.theme.AmbientMeshBackdrop
import com.clashplayer.ui.theme.AuroraColors
import com.clashplayer.ui.theme.ClashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotSmokeTest {
    @get:Rule val rule = createComposeRule()

    private val track = Track(1, "Glass Cathedral", "Mira Voss", "Afterlight Sessions", 254_000, Uri.EMPTY, sampleRate = 96000, bitDepth = 24)
    private val clock = OutputClock("FLAC 24/96", "USB UAC2 direct", 96000, 96000.42, 24, 2, "Async feedback · High-speed", true, "PCM")
    private val lyrics = Lyrics.parseLrc(
        """
        [00:20.00]We built the night out of mirrors
        [00:24.50]and every one of them was looking back
        [00:29.00]Hold the light a little longer
        [00:41.00]Glass remembers what we said
        """.trimIndent()
    )

    private fun shoot(name: String, ms: Long = 1200, content: @Composable () -> Unit, act: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent { ClashTheme { content() } }
        rule.mainClock.advanceTimeBy(ms)
        act()
        rule.mainClock.advanceTimeBy(ms)
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Composable
    private fun Sheet(position: Long = 96_000) = NowPlayingSheet(
        expanded = true, track = track, isPlaying = true, currentPositionMs = position, durationMs = 254_000, lyrics = lyrics,
        colors = AuroraColors.Default, outputClock = { clock }, energy = { 0.45f }, shuffle = true, repeatMode = 1,
        onPlayPause = {}, onSkipNext = {}, onSkipPrevious = {}, onSeekTo = {}, onToggleShuffle = {}, onCycleRepeat = {},
        onDismiss = {}, onOpenEqualizer = {}
    )

    @Test fun nowPlaying() = shoot("now_playing", content = { Sheet() })

    @Test fun nowPlayingHud() = shoot("now_playing_hud", content = { Sheet() }, act = { rule.onNodeWithText("FLAC 24/96").performClick() })

    @Test fun nowPlayingLyrics() = shoot("now_playing_lyrics", content = { Sheet(26_000) }, act = { rule.onNodeWithText("LYRICS").performClick() })

    @Test fun console() = shoot("console", content = {
        val bands = EqualizerBand.DEFAULT_10_BANDS.mapIndexed { i, f ->
            EqualizerBand(FilterType.PEAKING.id, f, 1.414f).also { it.setGainDb(floatArrayOf(5f, 4f, 2f, 0f, -2f, -3f, 0f, 2.5f, 4f, 3f)[i]) }
        }
        Box(Modifier.fillMaxSize()) {
            AmbientMeshBackdrop(AuroraColors.Default, { 0.3f }, animate = false, modifier = Modifier.fillMaxSize())
            Column(Modifier.padding(20.dp)) {
                TickSelector(listOf("Graphic EQ", "Tone & Space", "AutoEQ", "Output"), 0, {})
                ObsidianPanel(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    EqualizerSliders(bands, { _, _ -> }, Modifier.padding(16.dp))
                }
                ObsidianPanel(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Row(Modifier.padding(16.dp)) {
                        RotaryKnob("Preamp", -3.5f, -15f, 15f, "dB", onValueChange = {}, modifier = Modifier.weight(1f))
                        RotaryKnob("Bass", 6f, -12f, 12f, "dB", onValueChange = {}, modifier = Modifier.weight(1f))
                        RotaryKnob("Width", 1.4f, 0f, 2f, "x", onValueChange = {}, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    })

    @Test fun miniPlayer() = shoot("mini_player", content = {
        Box(Modifier.fillMaxSize()) {
            AmbientMeshBackdrop(AuroraColors.Default, { 0.6f }, animate = false, modifier = Modifier.fillMaxSize())
            MiniPlayerBar(track, true, 0.38f, AudioDriverType.BIT_PERFECT_DIRECT, {}, {}, {}, Modifier.padding(16.dp))
        }
    })

    private val library = listOf(
        track,
        Track(2, "Low Orbit", "Kade Mercer", "Night Ferry", 201_000, Uri.EMPTY),
        Track(3, "Salt and Signal", "Oona Hale", "Coastline", 318_000, Uri.EMPTY),
        Track(4, "Paper Lanterns", "The Quiet Fields", "Coastline", 233_000, Uri.EMPTY),
    )

    @Test fun home() = shoot("home", content = {
        Box(Modifier.fillMaxSize()) {
            AmbientMeshBackdrop(AuroraColors.Default, { 0.3f }, animate = false, modifier = Modifier.fillMaxSize())
            com.clashplayer.ui.screens.FlowHomeScreen(library, track, true, AudioDriverType.BIT_PERFECT_DIRECT, {}, {})
        }
    })

    @Test fun libraryScreen() = shoot("library", content = {
        Box(Modifier.fillMaxSize()) {
            AmbientMeshBackdrop(AuroraColors.Default, { 0.3f }, animate = false, modifier = Modifier.fillMaxSize())
            com.clashplayer.ui.screens.LibraryScreen(library, com.clashplayer.model.LocalMediaRepository.buildAlbums(library),
                com.clashplayer.model.LocalMediaRepository.buildArtists(library), emptyList(), track, true, {})
        }
    })
}
