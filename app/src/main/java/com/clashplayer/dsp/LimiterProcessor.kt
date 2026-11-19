package com.clashplayer.dsp

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

class LimiterProcessor(
    private var sampleRate: Int = 44100
) {
    var enabled: Boolean = true
    var threshold: Float = 0.95f
    var kneeDb: Float = 3.0f

    private var envelope: Float = 0.0f
    private val attackTime = 0.001f // 1ms
    private val releaseTime = 0.050f // 50ms

    private var attackCoeff = 0.0f
    private var releaseCoeff = 0.0f

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
        attackCoeff = Math.exp(-1.0 / (attackTime * rate)).toFloat()
        releaseCoeff = Math.exp(-1.0 / (releaseTime * rate)).toFloat()
    }

    fun process(sample: Float): Float {
        if (!enabled) return sample

        val absSample = abs(sample)
        envelope = if (absSample > envelope) {
            attackCoeff * envelope + (1.0f - attackCoeff) * absSample
        } else {
            releaseCoeff * envelope + (1.0f - releaseCoeff) * absSample
        }

        // Envelope gain reduction, then a tanh knee above threshold that is continuous and slope-1 at the threshold
        val reduced = if (envelope > threshold) sample * threshold / envelope else sample
        val mag = abs(reduced)
        if (mag <= threshold) return reduced
        val headroom = 1.0f - threshold
        return sign(reduced) * (threshold + headroom * tanh((mag - threshold) / headroom))
    }

    fun reset() {
        envelope = 0.0f
    }
}
