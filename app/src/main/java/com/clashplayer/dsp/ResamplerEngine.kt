package com.clashplayer.dsp

import java.util.Random
import kotlin.math.sin

class ResamplerEngine {
    var cutoffRatio: Float = 0.95f // 90% to 99%
    var ditherEnabled: Boolean = true

    private val random = Random()
    private var lastRandomL = 0.0f
    private var lastRandomR = 0.0f

    /**
     * Triangular Probability Density Function (TPDF) dither for 16-bit or 24-bit output
     */
    fun applyDither(sample: Float, bitDepth: Int, channel: Int): Float {
        if (!ditherEnabled || bitDepth >= 32) return sample

        val scale = when (bitDepth) {
            24 -> 8388607.0f
            else -> 32767.0f
        }

        val r1 = (random.nextFloat() - 0.5f) / scale
        val r2 = if (channel == 0) lastRandomL else lastRandomR
        val tpdf = r1 - r2

        if (channel == 0) {
            lastRandomL = r1
        } else {
            lastRandomR = r1
        }

        return (sample + tpdf).coerceIn(-1.0f, 1.0f)
    }

    /**
     * Normalized sinc function for band-limited interpolation
     */
    fun sinc(x: Double): Double {
        if (Math.abs(x) < 1e-9) return 1.0
        val pix = Math.PI * x
        return sin(pix) / pix
    }
}
