package com.clashplayer.dsp

import com.clashplayer.dsp.model.EqualizerPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DspFeaturesTest {

    private fun sine(freq: Double, rate: Int, frames: Int, amp: Double = 0.5) =
        FloatArray(frames * 2) { (amp * sin(2 * PI * freq * (it / 2) / rate)).toFloat() }

    /** SNR in dB of channel 0 against the least-squares sine at [freq], skipping [skip] frames of start-up. */
    private fun snrDb(x: FloatArray, freq: Double, rate: Int, skip: Int): Double {
        val n = x.size / 2 - skip
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var xs = 0.0; var xc = 0.0
        for (i in 0 until n) {
            val w = 2 * PI * freq * i / rate; val s = sin(w); val c = cos(w); val v = x[2 * (i + skip)]
            ss += s * s; cc += c * c; sc += s * c; xs += v * s; xc += v * c
        }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det
        val b = (xc * ss - xs * sc) / det
        var sig = 0.0; var noise = 0.0
        for (i in 0 until n) {
            val w = 2 * PI * freq * i / rate; val m = a * sin(w) + b * cos(w)
            sig += m * m; val e = x[2 * (i + skip)] - m; noise += e * e
        }
        return 10 * log10(sig / noise)
    }

    private fun resampleAll(src: FloatArray, r: Resampler): FloatArray {
        val out = ArrayList<Float>()
        val buf = FloatArray(r.maxOutFrames(1024) * 2)
        var i = 0
        while (i < src.size) {
            val n = minOf(2048, src.size - i)
            val w = r.process(src.copyOfRange(i, i + n), n, buf)
            for (k in 0 until w) out += buf[k]
            i += n
        }
        return out.toFloatArray()
    }

    @Test fun resamplerUpAndDownIsClean() {
        for ((from, to) in listOf(44100 to 48000, 96000 to 44100, 44100 to 88200)) {
            val out = resampleAll(sine(1000.0, from, from / 2), Resampler(from, to, 2))
            assertEquals("length $from->$to", from / 2 * to.toDouble() / from, out.size / 2.0, 80.0)
            val snr = snrDb(out, 1000.0, to, 200)
            assertTrue("SNR $from->$to = $snr", snr > 90)
        }
    }

    @Test fun resamplerRemovesContentAboveNewNyquist() {
        // 30 kHz at 96k must vanish when going to 44.1k
        val out = resampleAll(sine(30000.0, 96000, 48000), Resampler(96000, 44100, 2))
        val rms = sqrt(out.drop(400).map { it * it.toDouble() }.average())
        assertTrue("leak $rms", 20 * log10(rms / (0.5 / sqrt(2.0))) < -90)
    }

    @Test fun pickRatePrefersCleanMultiples() {
        assertEquals(88200, Resampler.pickRate(44100, listOf(48000, 88200, 96000)))
        assertEquals(48000, Resampler.pickRate(44100, listOf(48000, 96000)))
        assertEquals(96000, Resampler.pickRate(192000, listOf(48000, 96000)))
        assertEquals(44100, Resampler.pickRate(44100, listOf(44100, 48000)))
        assertNull(Resampler.pickRate(44100, emptyList()))
    }

    @Test fun ditherLandsOnTheGridAndNoneIsUntouched() {
        val x = sine(997.0, 44100, 4096, 0.3)
        val none = x.copyOf().also { Ditherer(DitherType.NONE).process(it, it.size, 2, 16, 44100) }
        assertTrue(none.contentEquals(x))
        for (t in DitherType.entries - DitherType.NONE) {
            val y = x.copyOf().also { Ditherer(t).process(it, it.size, 2, 16, 44100) }
            for (v in y) assertEquals("$t off grid", Math.round(v * 32768.0).toDouble(), v * 32768.0, 1e-6)
            val err = y.indices.maxOf { abs(y[it] - x[it]) * 32768 }
            assertTrue("$t error $err LSB", err < if (t.filterFor(44100) != null) 60f else 1.6f)
        }
    }

    @Test fun noiseShapingMovesNoiseOutOfTheMidrange() {
        // Error power at midrange frequencies, where hearing is most sensitive
        fun midNoise(t: DitherType): Double {
            val x = FloatArray(1 shl 15) { 0.001f * sin(it * 0.01).toFloat() }
            val y = x.copyOf().also { Ditherer(t).process(it, it.size, 1, 16, 44100) }
            return listOf(1000.0, 2000.0, 3000.0, 4000.0).sumOf { f ->
                var re = 0.0; var im = 0.0
                for (i in x.indices) { val e = (y[i] - x[i]) * 32768.0; val w = 2 * PI * f * i / 44100; re += e * cos(w); im += e * sin(w) }
                re * re + im * im
            }
        }
        val flat = midNoise(DitherType.TRIANGULAR)
        for (t in listOf(DitherType.F_WEIGHTED, DitherType.MODIFIED_E, DitherType.IMPROVED_E, DitherType.SHIBATA, DitherType.LOW_SHIBATA)) {
            val db = 10 * log10(midNoise(t) / flat)
            assertTrue("$t midrange noise $db dB", db < if (t == DitherType.LOW_SHIBATA) -3 else -8)
        }
    }

    @Test fun athShapingFollowsHearingAtAnyRate() {
        for (rate in listOf(44100, 96000)) {
            val h = DitherType.SHIBATA.filterFor(rate)!!
            fun ntfDb(f: Double): Double { var re = 1.0; var im = 0.0; for (k in h.indices) { val w = 2 * PI * f / rate * (k + 1); re -= h[k] * cos(w); im += h[k] * sin(w) }; return 10 * log10(re * re + im * im) }
            // Quiet where hearing is most sensitive, loud where it is deaf
            assertTrue("$rate", ntfDb(3500.0) < ntfDb(18000.0) - 15)
            assertTrue("$rate", ntfDb(3500.0) < ntfDb(500.0))
        }
    }

    @Test fun compressorCurveAndAction() {
        val c = CompressorProcessor(48000).apply { thresholdDb = -20f; ratio = 4f; kneeDb = 0f; enabled = true }
        assertEquals(0f, c.gainReductionDb(-30f), 1e-4f)
        assertEquals(-7.5f, c.gainReductionDb(-10f), 1e-4f) // 10 dB over at 4:1 leaves 2.5
        val out = FloatArray(2)
        repeat(48000) { c.process(0.5f, 0.5f, out) } // -6 dBFS, 14 dB over
        assertEquals(20f * log10(0.5f) - 10.5f, 20f * log10(out[0]), 0.1f)
    }

    private fun flacWithComments(vararg kv: String): ByteArray {
        val c = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        c.putInt(3).put("enc".toByteArray()).putInt(kv.size)
        kv.forEach { c.putInt(it.length).put(it.toByteArray()) }
        val comment = c.array().copyOf(c.position())
        val streamInfo = ByteArray(34)
        return "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf((0x80 or 4).toByte(), 0, (comment.size shr 8).toByte(), comment.size.toByte()) + comment
    }

    private fun reader(b: ByteArray): (Long, Int) -> ByteArray = { at, n -> b.copyOfRange(at.toInt().coerceAtMost(b.size), (at + n).toInt().coerceAtMost(b.size)) }

    @Test fun replayGainFromFlac() {
        val f = flacWithComments("TITLE=x", "replaygain_track_gain=-7.25 dB", "REPLAYGAIN_TRACK_PEAK=0.98", "REPLAYGAIN_ALBUM_GAIN=-6.00 dB")
        val rg = ReplayGainInfo.read(reader(f))
        assertEquals(-7.25f, rg.trackGain!!, 1e-4f)
        assertEquals(0.98f, rg.trackPeak!!, 1e-4f)
        assertEquals(-6f, rg.gainDb(ReplayGainMode.ALBUM, 0f, 0f, true), 1e-4f)
        assertEquals(-7.25f, rg.gainDb(ReplayGainMode.TRACK, 0f, 0f, true), 1e-4f)
        assertEquals(0f, rg.gainDb(ReplayGainMode.OFF, 3f, -2f, true), 0f)
    }

    @Test fun replayGainFromId3v24AndClipGuard() {
        fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
        fun txxx(k: String, v: String): ByteArray { val body = byteArrayOf(3) + k.toByteArray() + 0 + v.toByteArray(); return "TXXX".toByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body }
        val frames = txxx("REPLAYGAIN_TRACK_GAIN", "+4.50 dB") + txxx("REPLAYGAIN_TRACK_PEAK", "0.891251")
        val tag = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frames.size) + frames + ByteArray(64)
        val rg = ReplayGainInfo.read(reader(tag))
        assertEquals(4.5f, rg.trackGain!!, 1e-4f)
        // Peak 0.891 is -1 dBFS, so +4.5 dB is held to +1 dB with clip prevention
        assertEquals(1f, rg.gainDb(ReplayGainMode.TRACK, 0f, 0f, true), 0.01f)
        assertEquals(4.5f, rg.gainDb(ReplayGainMode.TRACK, 0f, 0f, false), 1e-4f)
        assertEquals(-3f, ReplayGainInfo().gainDb(ReplayGainMode.TRACK, 0f, -3f, true), 0f)
    }

    @Test fun tempoKeepsPitchAndScalesLength() {
        val rate = 44100
        val src = sine(440.0, rate, rate * 2)
        for (tempo in listOf(2f, 0.5f)) {
            val tp = TempoProcessor(rate).apply { this.tempo = tempo }
            val out = ArrayList<Float>()
            val buf = FloatArray(tp.maxOut(2048))
            var i = 0
            while (i < src.size) { val n = minOf(2048, src.size - i); val w = tp.process(src.copyOfRange(i, i + n), n, buf); for (k in 0 until w) out += buf[k]; i += n }
            val y = out.toFloatArray()
            assertEquals("length at $tempo", rate * 2 / tempo.toDouble(), y.size / 2.0, rate * 0.1)
            val snr = snrDb(y.copyOfRange(rate / 5 * 2, y.size - rate / 5 * 2), 440.0, rate, 0)
            assertTrue("pitch at $tempo, SNR $snr", snr > 15)
        }
    }

    @Test fun fadeRampsToSilence() {
        val e = AudioEngine(48000).apply { dvcEnabled = false; dvcHeadroomDb = 0f }
        e.fadeTo(0f, 10) // 480 frames
        val buf = FloatArray(2048) { 0.5f }
        e.processInterleavedStereo(buf, 0, buf.size)
        assertTrue(e.fadeDone)
        assertTrue(buf[2] > 0.4f)
        assertEquals(0f, buf[2 * 600], 1e-6f)
    }

    @Test fun autoHeadroomStopsBoostsClippingAtFullVolume() {
        val e = AudioEngine(48000).apply { volume = 1f; limiterProcessor.enabled = false }
        e.applyPreset(EqualizerPreset.BUILT_IN_PRESETS.first { it.name == "Rock" })
        e.preampDb = 0f
        val buf = sine(60.0, 48000, 48000, 1.0)
        e.processInterleavedStereo(buf, 0, buf.size)
        val peak = buf.drop(48000).maxOf { abs(it) }
        assertTrue("peak $peak", peak <= 1.001f)
    }
}
