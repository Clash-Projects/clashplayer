package com.clashplayer.dsp

import com.clashplayer.dsp.model.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class BiquadFilterTest {

    private val sampleRate = 48000

    @Test
    fun testPeakingFilterUnityAtZeroGain() {
        val filter = BiquadFilter(sampleRate)
        filter.configure(
            type = FilterType.PEAKING,
            frequencyHz = 1000,
            gainDb = 0.0f,
            q = 1.414f
        )

        for (i in 0 until 1000) {
            val input = sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat()
            val output = filter.processSample(input, 0)
            if (i > 100) {
                assertEquals(input, output, 0.01f)
            }
        }
    }

    @Test
    fun testPeakingFilterBoostAtCenterFrequency() {
        val filter = BiquadFilter(sampleRate)
        val gainDb = 6.0f
        val expectedGain = 10.0.pow(gainDb / 20.0).toFloat() // ~1.995

        filter.configure(
            type = FilterType.PEAKING,
            frequencyHz = 1000,
            gainDb = gainDb,
            q = 1.414f
        )

        var maxInput = 0f
        var maxOutput = 0f

        for (i in 0 until 2000) {
            val input = sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat()
            val output = filter.processSample(input, 0)
            if (i > 500) {
                maxInput = maxOf(maxInput, abs(input))
                maxOutput = maxOf(maxOutput, abs(output))
            }
        }

        val measuredGain = maxOutput / maxInput
        assertEquals(expectedGain, measuredGain, 0.08f)
    }

    @Test
    fun testLowPassFilterAttenuatesHighFrequencies() {
        val filter = BiquadFilter(sampleRate)
        filter.configure(
            type = FilterType.LOW_PASS,
            frequencyHz = 500,
            gainDb = 0f,
            q = 0.707f
        )

        var maxHighFreqOut = 0f
        for (i in 0 until 1000) {
            val highFreqIn = sin(2.0 * PI * 10000.0 * i / sampleRate).toFloat()
            val out = filter.processSample(highFreqIn, 0)
            if (i > 200) {
                maxHighFreqOut = maxOf(maxHighFreqOut, abs(out))
            }
        }

        assertTrue(maxHighFreqOut < 0.05f)
    }

    @Test
    fun testFilterStabilityNoNaNOrInfinity() {
        val filter = BiquadFilter(sampleRate)
        filter.configure(
            type = FilterType.PEAKING,
            frequencyHz = 20000,
            gainDb = 15.0f,
            q = 12.0f
        )

        for (i in 0 until 10000) {
            val output = filter.processSample(0.5f, 0)
            assertTrue(!output.isNaN())
            assertTrue(!output.isInfinite())
        }
    }

    @Test
    fun testFrequencyAboveNyquistStaysStable() {
        val filter = BiquadFilter(44100)
        filter.configure(FilterType.PEAKING, 24000, 12.0f, 1.0f)
        var out = 0f
        for (i in 0 until 10000) out = filter.processSample(if (i % 2 == 0) 0.5f else -0.5f, 0)
        assertTrue(out.isFinite() && abs(out) < 10f)
    }
}
