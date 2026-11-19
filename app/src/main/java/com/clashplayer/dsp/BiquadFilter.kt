package com.clashplayer.dsp

import com.clashplayer.dsp.model.FilterType
import kotlin.math.*

class BiquadFilter(
    private var sampleRate: Int = 44100
) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Direct Form II states for stereo channels (0 = Left, 1 = Right)
    private var z1Left = 0.0
    private var z2Left = 0.0
    private var z1Right = 0.0
    private var z2Right = 0.0

    fun updateSampleRate(newRate: Int) {
        if (sampleRate != newRate && newRate > 0) {
            sampleRate = newRate
            reset()
        }
    }

    fun configure(type: FilterType, frequencyHz: Int, gainDb: Float, q: Float) {
        // Above Nyquist omega > pi flips the sign of alpha and the filter goes unstable (e.g. 24 kHz at 44.1 kHz)
        val freq = frequencyHz.toDouble().coerceIn(1.0, sampleRate * 0.499)
        val omega = 2.0 * Math.PI * freq / sampleRate.toDouble()
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val qVal = q.toDouble().coerceIn(0.1, 12.0)
        val alpha = sinOmega / (2.0 * qVal)
        val a = 10.0.pow(gainDb.toDouble() / 40.0) // sqrt(10^(dB/20))

        var a0 = 1.0

        when (type) {
            FilterType.PEAKING -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosOmega
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val sqrtA = sqrt(a)
                b0 = a * ((a + 1.0) - (a - 1.0) * cosOmega + 2.0 * sqrtA * alpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosOmega)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosOmega - 2.0 * sqrtA * alpha)
                a0 = (a + 1.0) + (a - 1.0) * cosOmega + 2.0 * sqrtA * alpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosOmega)
                a2 = (a + 1.0) + (a - 1.0) * cosOmega - 2.0 * sqrtA * alpha
            }
            FilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(a)
                b0 = a * ((a + 1.0) + (a - 1.0) * cosOmega + 2.0 * sqrtA * alpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosOmega)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosOmega - 2.0 * sqrtA * alpha)
                a0 = (a + 1.0) - (a - 1.0) * cosOmega + 2.0 * sqrtA * alpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosOmega)
                a2 = (a + 1.0) - (a - 1.0) * cosOmega - 2.0 * sqrtA * alpha
            }
            FilterType.LOW_PASS -> {
                b0 = (1.0 - cosOmega) / 2.0
                b1 = 1.0 - cosOmega
                b2 = (1.0 - cosOmega) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }
            FilterType.HIGH_PASS -> {
                b0 = (1.0 + cosOmega) / 2.0
                b1 = -(1.0 + cosOmega)
                b2 = (1.0 + cosOmega) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }
            FilterType.BAND_PASS -> {
                b0 = alpha
                b1 = 0.0
                b2 = -alpha
                a0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }
            FilterType.NOTCH -> {
                b0 = 1.0
                b1 = -2.0 * cosOmega
                b2 = 1.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }
        }

        // Normalize
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
    }

    fun processSample(input: Float, channel: Int): Float {
        val x = input.toDouble()
        val z1 = if (channel == 0) z1Left else z1Right
        val z2 = if (channel == 0) z2Left else z2Right

        val y = b0 * x + z1
        val newZ1 = b1 * x - a1 * y + z2
        val newZ2 = b2 * x - a2 * y

        if (channel == 0) {
            z1Left = newZ1
            z2Left = newZ2
        } else {
            z1Right = newZ1
            z2Right = newZ2
        }

        return y.toFloat()
    }

    /** Magnitude response in dB at [freqHz], from the current coefficients. */
    fun magnitudeDb(freqHz: Double): Double {
        val w = 2.0 * Math.PI * freqHz / sampleRate
        val c1 = cos(w); val c2 = cos(2 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
        val den = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * c1 + 2 * a2 * c2
        return 10 * log10(num / den)
    }

    fun reset() {
        z1Left = 0.0
        z2Left = 0.0
        z1Right = 0.0
        z2Right = 0.0
    }
}
