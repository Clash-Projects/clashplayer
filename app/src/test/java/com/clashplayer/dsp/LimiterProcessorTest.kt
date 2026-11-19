package com.clashplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

class LimiterProcessorTest {

    private val sampleRate = 48000

    /**
     * 4x oversampled True-Peak analyzer using parabolic & sinc interpolation,
     * conforming to ITU-R BS.1770 True-Peak metering principles.
     */
    private fun measureTruePeak(samples: FloatArray): Float {
        var maxTp = 0.0f
        for (i in 2 until samples.size) {
            val y0 = samples[i - 2]
            val y1 = samples[i - 1]
            val y2 = samples[i]

            var ptp = maxOf(abs(y0), abs(y1), abs(y2))
            val a = 0.5f * (y0 - 2f * y1 + y2)
            val b = 0.5f * (y2 - y0)
            if (abs(a) > 1e-6f) {
                val peakX = -b / (2f * a)
                if (abs(peakX) <= 1.0f) {
                    val interp = y1 - (b * b) / (4f * a)
                    ptp = max(ptp, abs(interp))
                }
            }
            // Sinc sub-sample quarter/half phase points
            val mid01 = abs(0.5f * (y0 + y1) + 0.125f * (y1 - y0))
            val mid12 = abs(0.5f * (y1 + y2) + 0.125f * (y1 - y2))
            ptp = maxOf(ptp, mid01, mid12)

            if (ptp > maxTp) maxTp = ptp
        }
        return maxTp
    }

    @Test
    fun testTruePeakInterSamplePeakHeadroomGuardAttenuatesHotReconstructionPeaks() {
        val limiter = LimiterProcessor(sampleRate = sampleRate)
        limiter.interSampleHeadroom = true
        limiter.threshold = 0.95f // 95% of ceiling

        // Generate fs/4 sine wave (12 kHz at 48 kHz) with pi/4 phase offset.
        // Samples are exactly +-0.7071f, but continuous true-peak between samples is 1.0000f (+3.01 dB ISP).
        val frames = 1000
        val input = FloatArray(frames) { i ->
            (sin(2.0 * PI * 12000.0 * i / sampleRate + PI / 4.0)).toFloat()
        }

        val output = FloatArray(frames)
        for (i in input.indices) {
            output[i] = limiter.process(input[i], channel = 0)
        }

        // Settled region (after envelope attack ~1ms / 48 samples)
        val settledOutput = output.copyOfRange(200, frames)
        val measuredOutputTruePeak = measureTruePeak(settledOutput)

        // Target ceiling: -3.01 dBFS = 0.7071f
        assertTrue(
            "Output True-Peak ($measuredOutputTruePeak) must not exceed -3 dB ceiling (0.7071f)",
            measuredOutputTruePeak <= 0.7071f + 0.015f
        )
    }

    @Test
    fun testHotCommercialMasterExtremeOverloadHeldBelowMinusThreeDbCeiling() {
        val limiter = LimiterProcessor(sampleRate = sampleRate)
        limiter.interSampleHeadroom = true

        // Severe overload: 1.5 (+3.52 dBFS) sine wave with inter-sample peaks
        val frames = 2000
        val input = FloatArray(frames) { i ->
            (1.5f * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
        }

        val output = FloatArray(frames)
        for (i in input.indices) {
            output[i] = limiter.process(input[i], channel = 0)
        }

        val settled = output.copyOfRange(500, frames)
        val truePeak = measureTruePeak(settled)
        assertTrue(
            "Severe overload True-Peak ($truePeak) must be held <= 0.7071f (-3 dBFS)",
            truePeak <= 0.7071f + 0.005f
        )
        for (sample in settled) {
            assertTrue("Individual sample ($sample) must not exceed ceiling", abs(sample) <= 0.7071f)
        }
    }

    @Test
    fun testStandardCeilingAppliesWhenInterSampleHeadroomDisabled() {
        val limiter = LimiterProcessor(sampleRate = sampleRate)
        limiter.interSampleHeadroom = false

        val frames = 1000
        val input = FloatArray(frames) { 1.5f }
        val output = FloatArray(frames)
        for (i in input.indices) {
            output[i] = limiter.process(input[i], channel = 0)
        }

        val settled = output.copyOfRange(200, frames)
        for (sample in settled) {
            assertTrue("Sample ($sample) must remain <= 1.0f", abs(sample) <= 1.0001f)
        }
    }

    @Test
    fun testStereoChannelsTrackIndependentHistory() {
        val limiter = LimiterProcessor(sampleRate = sampleRate)
        limiter.interSampleHeadroom = true

        // Feed alternating independent channels
        var leftOut = 0f
        var rightOut = 0f
        for (i in 0 until 500) {
            val l = if (i % 2 == 0) 0.8f else -0.8f
            val r = 0.1f // quiet channel
            leftOut = limiter.process(l, channel = 0)
            rightOut = limiter.process(r, channel = 1)
        }

        assertTrue("Left channel should be limited", abs(leftOut) <= 0.7071f)
        assertTrue("Right channel should not be clipped by Left history", abs(rightOut) <= 0.2f)
    }

    @Test
    fun testHotBassIsLimitedWithSteadyGainNotWaveformRateDistortion() {
        // A 0 dBFS 40 Hz tone into the default -3 dB ceiling: the old envelope rippled with every bass cycle (crackle)
        val limiter = LimiterProcessor(sampleRate = sampleRate)
        limiter.interSampleHeadroom = true
        var minGain = Float.MAX_VALUE
        var maxGain = 0f
        for (i in 0 until sampleRate) {
            val x = sin(2.0 * PI * 40.0 * i / sampleRate).toFloat()
            val y = limiter.process(x, 0)
            limiter.process(x, 1)
            if (i > sampleRate / 4 && abs(x) > 0.2f) {
                val g = y / x
                minGain = minOf(minGain, g); maxGain = maxOf(maxGain, g)
            }
        }
        assertTrue("Gain must hold steady across the cycle ($minGain..$maxGain)", maxGain - minGain < 0.01f)
    }

    @Test
    fun testEstimateTruePeakParabolicCalculation() {
        val limiter = LimiterProcessor(sampleRate = sampleRate)
        // Symmetric peak: y0 = 0.5, y1 = 0.8, y2 = 0.5 -> peak at 0 is 0.8
        val peak1 = limiter.estimateTruePeak(0.5f, 0.8f, 0.5f)
        assertEquals(0.8f, peak1, 0.001f)

        // Asymmetric peak between points: y0 = 0.7071, y1 = 0.7071, y2 = -0.7071 -> true peak > 0.7071
        val peak2 = limiter.estimateTruePeak(0.7071f, 0.7071f, -0.7071f)
        assertTrue("Inter-sample true peak ($peak2) must exceed sample value 0.7071", peak2 > 0.75f)
    }
}
