package com.clashplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioXRayAndFidelityTest {

    @Test
    fun testPadded16BitDetection() {
        val analyzer = AudioXRayAnalyzer()
        val buffer = FloatArray(32768)

        // Generate a 16-bit sine wave quantized to 16-bit PCM word and normalized to float
        for (i in buffer.indices) {
            val analog = sin(2.0 * PI * 440.0 * i / 44100.0) * 0.75
            val pcm16 = (analog * 32767.0).toInt().coerceIn(-32768, 32767)
            buffer[i] = pcm16 / 32768.0f
        }

        analyzer.feed(buffer, 0, buffer.size)
        val metrics = analyzer.metrics.value

        assertTrue("Expected isPadded16Bit to be true for 16-bit PCM", metrics.isPadded16Bit)
        assertEquals("Expected effectiveBitDepth to be 16", 16, metrics.effectiveBitDepth)
        assertEquals("Expected bottom 8 bits of activeBitsMask to be zero", 0, metrics.activeBitsMask and 0x0000FF)
        for (bit in 0..7) {
            assertFalse("Bit $bit should be inactive (dead) in padded 16-bit", metrics.bitGrid[bit])
        }
        var activeUpperBits = 0
        for (bit in 8..23) {
            if (metrics.bitGrid[bit]) activeUpperBits++
        }
        assertTrue("Upper bits (8..23) must have active bits, found $activeUpperBits", activeUpperBits >= 14)
    }

    @Test
    fun testTrue24BitDetection() {
        val analyzer = AudioXRayAnalyzer()
        val buffer = FloatArray(32768)

        // Generate a 24-bit signal with dither/activity down to LSB
        for (i in buffer.indices) {
            val analog = sin(2.0 * PI * 440.0 * i / 44100.0) * 0.75
            val pcm24 = (analog * 8388607.0).toInt().coerceIn(-8388608, 8388607)
            // Add non-zero activity in lower 8 bits
            val dithered24 = pcm24 xor (i and 0xFF)
            buffer[i] = dithered24 / 8388608.0f
        }

        analyzer.feed(buffer, 0, buffer.size)
        val metrics = analyzer.metrics.value

        assertFalse("Expected isPadded16Bit to be false for 24-bit PCM", metrics.isPadded16Bit)
        assertEquals("Expected effectiveBitDepth to be 24", 24, metrics.effectiveBitDepth)
        assertTrue("Expected bottom 8 bits to be active", (metrics.activeBitsMask and 0x0000FF) != 0)
        var activeLowerBits = 0
        for (bit in 0..7) {
            if (metrics.bitGrid[bit]) activeLowerBits++
        }
        assertTrue("Lower bits (0..7) should have active bits", activeLowerBits > 0)
    }

    @Test
    fun testDcOffsetAndDynamicCrestFactor() {
        val analyzer = AudioXRayAnalyzer()
        val buffer = FloatArray(32768)

        // Sine wave (crest factor ~3.01 dB) with +2.0% DC offset
        val dcTarget = 0.02f
        for (i in buffer.indices) {
            val s = 0.5f * sin(2.0 * PI * 1000.0 * i / 44100.0).toFloat() + dcTarget
            buffer[i] = s
        }

        analyzer.feed(buffer, 0, buffer.size)
        val metrics = analyzer.metrics.value

        assertEquals("DC offset should be ~2.0%", 2.0f, metrics.dcOffsetPercent, 0.05f)
        assertEquals("Sine wave crest factor should be ~3.01 dB", 3.01f, metrics.crestFactorDb, 0.5f)
    }

    @Test
    fun testTruePeakDbtpCalculation() {
        val fidelity = FidelityAnalyzer(sampleRate = 44100)
        // 11025 Hz sine wave at 44100 Hz (quarter of sample rate), with 45-degree phase offset
        // Consecutive samples land at sin(45) = 0.7071 (-3.01 dBFS), but continuous peak is 1.0 (0.00 dBTP)
        val frames = 8192
        val buffer = FloatArray(frames * 2)
        val phase = PI / 4.0
        for (i in 0 until frames) {
            val s = sin(2.0 * PI * 11025.0 * i / 44100.0 + phase).toFloat()
            buffer[i * 2] = s     // L
            buffer[i * 2 + 1] = s // R
        }

        fidelity.feed(buffer, 0, buffer.size)
        val metrics = fidelity.metricsFlow.value

        // Sample peak is only ~0.7071 (-3.01 dBFS). True peak must interpolate above sample peak
        assertTrue(
            "True-Peak dBTP (${metrics.truePeakDb}) should be well above sample peak -3.01 dBFS",
            metrics.truePeakDb > -1.5f
        )
    }

    @Test
    fun testTrueClippingDetection() {
        val fidelity = FidelityAnalyzer(sampleRate = 44100)
        val frames = 4096
        val buffer = FloatArray(frames * 2)

        // Hot master exceeding 0 dBFS (peak = 1.15)
        for (i in 0 until frames) {
            val s = 1.15f * sin(2.0 * PI * 1000.0 * i / 44100.0).toFloat()
            buffer[i * 2] = s
            buffer[i * 2 + 1] = s
        }

        fidelity.feed(buffer, 0, buffer.size)
        val metrics = fidelity.metricsFlow.value

        assertTrue("True clipping count must be > 0", metrics.trueClippingCount > 0)
        assertTrue("True-peak dBTP must be > 0.0 dBTP", metrics.truePeakDb > 0.0f)
    }

    @Test
    fun testFakeHiResSpectralCutoff() {
        val fidelity = FidelityAnalyzer(sampleRate = 96000)
        val frames = 8192
        val buffer = FloatArray(frames * 2)

        // Low frequency band-limited content (100 Hz) without ultrasonic high band energy
        for (i in 0 until frames) {
            val s = 0.5f * sin(2.0 * PI * 100.0 * i / 96000.0).toFloat()
            buffer[i * 2] = s
            buffer[i * 2 + 1] = s
        }

        fidelity.feed(buffer, 0, buffer.size)
        val metrics = fidelity.metricsFlow.value

        assertFalse("Expected fake hi-res upscaled detection for band-limited 96k stream", metrics.isAuthenticHiRes)
        assertTrue("Expected fake hi-res warning", metrics.fakeHiResWarning != null)
    }

    @Test
    fun testPerformanceOverheadUnderHalfPercentCpu() {
        val xray = AudioXRayAnalyzer()
        val fidelity = FidelityAnalyzer(sampleRate = 192000)

        // 1 second of 192kHz stereo = 384,000 samples
        val sampleRate = 192000
        val totalSamples = sampleRate * 2
        val buffer = FloatArray(totalSamples) { (sin(it.toDouble() * 0.05) * 0.5).toFloat() }

        val chunkSize = 1024

        // Warm up JIT compiler
        repeat(5) {
            var p = 0
            while (p < totalSamples) {
                val n = minOf(chunkSize, totalSamples - p)
                xray.feed(buffer, p, n)
                fidelity.feed(buffer, p, n)
                p += n
            }
        }
        xray.reset()
        fidelity.reset()

        // Measure time for 1 full second of 192kHz audio across both analyzers
        val startNano = System.nanoTime()
        var pos = 0
        while (pos < totalSamples) {
            val count = minOf(chunkSize, totalSamples - pos)
            xray.feed(buffer, pos, count)
            fidelity.feed(buffer, pos, count)
            pos += count
        }
        val elapsedNano = System.nanoTime() - startNano
        val elapsedMs = elapsedNano / 1_000_000.0

        // 1 second has 1000 ms. 0.5% CPU overhead means <= 5.0 ms.
        println("Processing 1 sec of 192kHz stereo audio: ${elapsedMs} ms (Limit: 5.0 ms for <0.5% CPU)")
        assertTrue(
            "Combined analysis overhead (${elapsedMs} ms) must be < 5.0 ms (<0.5% CPU)",
            elapsedMs < 5.0
        )
    }
}
