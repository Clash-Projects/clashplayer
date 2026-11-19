package com.clashplayer.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.tanh

class LimiterProcessor(
    private var sampleRate: Int = 44100
) {
    var enabled: Boolean = true
    var threshold: Float = 0.95f
    var kneeDb: Float = 3.0f

    /**
     * -3 dB Inter-Sample Peak (ISP) Headroom Option (inspired by studio mastering & Resonada).
     * Lowers digital ceiling to -3.01 dBFS (0.7071) to prevent DAC oversampling reconstruction
     * interpolation filters from clipping on hot commercial masters.
     */
    var interSampleHeadroom: Boolean = false

    private var envelope: Float = 0.0f
    // Instant attack + peak hold + slow release: the gain can't follow the waveform, which is what crackled on bass
    private val holdTime = 0.030f // longer than half a 20 Hz cycle
    private val releaseTime = 0.200f

    private var releaseCoeff = 0.0f
    private var holdSamples = 0
    private var holdLeft = 0

    // Sample history for 4x oversampled parabolic / sinc inter-sample peak detection
    private var prevL1 = 0.0f
    private var prevL2 = 0.0f
    private var prevR1 = 0.0f
    private var prevR2 = 0.0f

    init {
        updateCoefficients()
    }

    fun updateSampleRate(newRate: Int) {
        if (sampleRate != newRate && newRate > 0) {
            sampleRate = newRate
            updateCoefficients()
        }
    }

    private fun updateCoefficients() {
        // Envelope is shared by L and R (linked stereo) and advanced twice per frame
        val rate = 2.0 * sampleRate
        holdSamples = (holdTime * rate).toInt()
        releaseCoeff = Math.exp(-1.0 / (releaseTime * rate)).toFloat()
    }

    /**
     * 4x oversampled True-Peak Inter-Sample Peak estimation using quadratic polynomial peak interpolation
     * and sub-sample sinc approximation across 3 consecutive sample points.
     */
    fun estimateTruePeak(y0: Float, y1: Float, y2: Float): Float {
        var peak = maxOf(abs(y0), abs(y1), abs(y2))
        val a = 0.5f * (y0 - 2.0f * y1 + y2)
        val b = 0.5f * (y2 - y0)
        if (abs(a) > 1e-6f) {
            val peakX = -b / (2.0f * a)
            if (abs(peakX) <= 1.0f) {
                val interpolated = y1 - (b * b) / (4.0f * a)
                peak = max(peak, abs(interpolated))
            }
        }
        // Sub-sample mid-point sinc approximation
        val mid01 = abs(0.5f * (y0 + y1) + 0.125f * (y1 - y0))
        val mid12 = abs(0.5f * (y1 + y2) + 0.125f * (y1 - y2))
        peak = maxOf(peak, mid01, mid12)
        return peak
    }

    fun process(sample: Float, channel: Int = 0): Float {
        if (!enabled) return sample
        if (sample.isNaN() || sample.isInfinite()) return 0.0f

        val ceiling = if (interSampleHeadroom) 0.7071f else 1.0f
        val effectiveThreshold = threshold * ceiling

        val detectedPeak = if (interSampleHeadroom) {
            if (channel == 0) {
                val tp = estimateTruePeak(prevL2, prevL1, sample)
                prevL2 = prevL1
                prevL1 = sample
                tp
            } else {
                val tp = estimateTruePeak(prevR2, prevR1, sample)
                prevR2 = prevR1
                prevR1 = sample
                tp
            }
        } else {
            abs(sample)
        }

        if (detectedPeak >= envelope) {
            envelope = detectedPeak
            holdLeft = holdSamples
        } else if (holdLeft > 0) {
            holdLeft--
        } else {
            envelope = max(detectedPeak, releaseCoeff * envelope)
        }

        // Envelope >= this peak, so the reduced sample stays under threshold; the tanh knee is a backstop
        val reduced = if (envelope > effectiveThreshold) sample * effectiveThreshold / envelope else sample
        val mag = abs(reduced)
        if (mag <= effectiveThreshold) return reduced
        val headroom = ceiling - effectiveThreshold
        if (headroom <= 1e-6f) return sign(reduced) * effectiveThreshold
        return sign(reduced) * (effectiveThreshold + headroom * tanh((mag - effectiveThreshold) / headroom))
    }

    fun reset() {
        envelope = 0.0f
        holdLeft = 0
        prevL1 = 0.0f
        prevL2 = 0.0f
        prevR1 = 0.0f
        prevR2 = 0.0f
    }
}
