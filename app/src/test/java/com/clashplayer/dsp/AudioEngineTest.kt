package com.clashplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioEngineTest {

    @Test
    fun testLimiterKeepsSignalsBelowCeiling() {
        val limiter = LimiterProcessor(sampleRate = 48000)
        limiter.threshold = 0.95f

        for (i in 0 until 500) {
            val limited = limiter.process(2.5f)
            assertTrue("Sample $limited should be <= 1.0f", abs(limited) <= 1.0f)
        }
    }

    @Test
    fun testStereoProcessorMonoMixdown() {
        val stereo = StereoProcessor()
        stereo.forceMono = true

        val output = FloatArray(2)
        stereo.process(1.0f, 0.0f, output)

        // For mono mixdown: each channel is (L + R) * 0.5f
        assertEquals(0.5f, output[0], 0.001f)
        assertEquals(0.5f, output[1], 0.001f)
    }

    @Test
    fun testDvcHeadroomVolumeCurve() {
        val engine = AudioEngine(sampleRate = 48000)
        engine.dvcEnabled = true

        engine.volume = 1.0f
        val maxScale = engine.computeEffectiveVolumeGain()
        assertTrue("DVC at 1.0f should have gain >= 1.0", maxScale >= 1.0f)

        engine.volume = 0.5f
        val midScale = engine.computeEffectiveVolumeGain()
        assertTrue("DVC at 0.5f should be less than maxScale", midScale < maxScale)

        engine.volume = 0.0f
        val zeroScale = engine.computeEffectiveVolumeGain()
        assertEquals(0.0f, zeroScale, 0.0001f)
    }

    @Test
    fun testReverbGeneratesDecayTail() {
        val reverb = ReverbProcessor(sampleRate = 48000)
        reverb.enabled = true
        reverb.roomSize = 0.8f
        reverb.damping = 0.3f
        reverb.width = 1.0f
        reverb.wetMix = 1.0f
        reverb.dryMix = 0.0f

        val out = FloatArray(2)
        // Feed impulse
        reverb.process(1.0f, 1.0f, out)

        var tailEnergy = 0f
        for (i in 0 until 4000) {
            reverb.process(0.0f, 0.0f, out)
            tailEnergy += abs(out[0]) + abs(out[1])
        }

        assertTrue("Reverb should produce reverberant energy after impulse", tailEnergy > 0.01f)
    }

    @Test
    fun testLimiterKneeIsContinuousAtThreshold() {
        val below = LimiterProcessor(48000).process(0.9499f)
        val above = LimiterProcessor(48000).process(0.9501f)
        assertEquals(below, above, 0.001f)
    }

    @Test
    fun testDvcCurveNeverBoostsAndHeadroomAppliesWithoutDvc() {
        val engine = AudioEngine(sampleRate = 48000)
        engine.volume = 1.0f
        assertEquals(1.0f, engine.computeEffectiveVolumeGain(), 1e-6f)
        engine.volume = 0.01f // lowest step on the media curve: -58 dB
        assertEquals(0.001259f, engine.computeEffectiveVolumeGain(), 0.00001f)
        engine.volume = 0.6f // curve knee: -17 dB
        assertEquals(0.1413f, engine.computeEffectiveVolumeGain(), 0.0001f)
        engine.dvcEnabled = false
        assertEquals(0.501f, engine.computeEffectiveVolumeGain(), 0.001f) // -6 dB headroom
    }

    @Test
    fun testBitPerfectBypassesAllProcessing() {
        val engine = AudioEngine(sampleRate = 48000)
        engine.bitPerfect = true
        engine.volume = 0.3f
        engine.bassDb = 10f
        engine.updateAllFilters()
        val buf = floatArrayOf(0.123f, -0.456f, 0.999f, -1.0f)
        val copy = buf.copyOf()
        engine.processInterleavedStereo(buf, 0, buf.size)
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun testVolumeChangeIsRampedNotStepped() {
        val engine = AudioEngine(sampleRate = 48000)
        engine.limiterProcessor.enabled = false
        engine.processInterleavedStereo(FloatArray(512) { 0.5f }, 0, 512) // settle at unity
        engine.volume = 0.2f // -40 dB
        val buf = FloatArray(512) { 0.5f }
        engine.processInterleavedStereo(buf, 0, 512)
        assertTrue("first frame should still be near the old gain", buf[0] > 0.45f)
        assertEquals(0.005f, buf[510], 0.0005f) // last frame reaches -40 dB
    }
}
