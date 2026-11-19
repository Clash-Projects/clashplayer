package com.clashplayer.service

import androidx.test.core.app.ApplicationProvider
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.DitherType
import com.clashplayer.dsp.ReplayGainMode
import com.clashplayer.dsp.model.EqualizerPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun firstRunHasNothing() {
        assertNull(SettingsStore(context).load(AudioEngine(), PlaybackSettings()))
    }

    @Test fun everythingSurvivesARestart() {
        val e = AudioEngine().apply {
            applyPreset(EqualizerPreset.BUILT_IN_PRESETS.first { it.name == "Rock" })
            dvcEnabled = false; volume = 0.42f; smoothGains = false
            stereoProcessor.forceMono = true
            compressor.enabled = true; compressor.ratio = 6f
            ditherer.type = DitherType.SHIBATA
            tempoProcessor.tempo = 1.25f
        }
        val s = PlaybackSettings().apply {
            crossfadeMs = 4000; replayGain = ReplayGainMode.ALBUM; replayGainPreampDb = -2.5f
            dsdRemaster = true; remasterMultiple = 128; btCodecRate = -1; gapless = false
        }
        val store = SettingsStore(context)
        store.profiles["usb:DAC X"] = OutputProfile(noDvc = true, allowDop = false)
        store.save(e, s, AudioDriverType.AAUDIO)

        val e2 = AudioEngine(); val s2 = PlaybackSettings()
        val store2 = SettingsStore(context)
        assertEquals(AudioDriverType.AAUDIO, store2.load(e2, s2))
        assertEquals("Rock", e2.equalizerStateFlow.value.name)
        assertEquals(e.graphicBands.map { it.getGainDb() }, e2.graphicBands.map { it.getGainDb() })
        assertEquals(false, e2.dvcEnabled)
        assertEquals(0.42f, e2.volume, 1e-6f)
        assertTrue(e2.stereoProcessor.forceMono)
        assertEquals(6f, e2.compressor.ratio, 0f)
        assertEquals(DitherType.SHIBATA, e2.ditherer.type)
        assertEquals(1.25f, e2.tempoProcessor.tempo, 0f)
        assertEquals(4000, s2.crossfadeMs)
        assertEquals(ReplayGainMode.ALBUM, s2.replayGain)
        assertEquals(-2.5f, s2.replayGainPreampDb, 0f)
        assertTrue(s2.dsdRemaster)
        assertEquals(128, s2.remasterMultiple)
        assertEquals(-1, s2.btCodecRate)
        assertEquals(false, s2.gapless)
        assertEquals(OutputProfile(noDvc = true, allowDop = false), store2.profiles["usb:DAC X"])
    }
}
