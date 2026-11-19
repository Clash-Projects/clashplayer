package com.clashplayer.dsp

import java.util.Random
import kotlin.math.roundToLong

/** Dither for integer outputs. Shaped types use 44.1 kHz error-feedback filters and fall back to triangular elsewhere. */
enum class DitherType(val label: String, internal val shaping: DoubleArray? = null) {
    NONE("None"),
    RECTANGULAR("Rectangular"),
    TRIANGULAR("Triangular"),
    TRIANGULAR_HP("Triangular HP"),
    F_WEIGHTED("F-weighted", doubleArrayOf(2.412, -3.370, 3.937, -4.174, 3.353, -2.205, 1.281, -0.569, 0.0847)),
    MODIFIED_E("Modified-E", doubleArrayOf(1.662, -1.263, 0.4827, -0.2913, 0.1268, -0.1124, 0.03252, -0.01265, -0.03524)),
    IMPROVED_E("Improved-E", doubleArrayOf(2.847, -4.685, 6.214, -7.184, 6.639, -5.032, 3.263, -1.632, 0.4191)),
}

/**
 * Quantizes float samples onto the [bits]-bit grid in place, so the packer's rounding is exact afterwards.
 * Noise shaping is error feedback: y = Q(x - sum(h * e) + d), giving a noise transfer of 1 - sum(h * z^-k).
 */
class Ditherer(var type: DitherType = DitherType.TRIANGULAR) {
    private val random = Random(0x5EED)
    private val lastRand = FloatArray(MAX_CH)
    private val err = Array(MAX_CH) { DoubleArray(9) }
    private val errPos = IntArray(MAX_CH)

    fun reset() {
        lastRand.fill(0f)
        err.forEach { it.fill(0.0) }
    }

    fun process(buf: FloatArray, count: Int, channels: Int, bits: Int, sampleRate: Int) {
        if (type == DitherType.NONE || bits >= 32 || bits < 8) return
        val scale = (1L shl (bits - 1)).toDouble()
        val max = (1L shl (bits - 1)) - 1
        val min = -(1L shl (bits - 1))
        val h = type.shaping?.takeIf { sampleRate == 44100 || sampleRate == 48000 }
        for (i in 0 until count) {
            val c = (i % channels).coerceAtMost(MAX_CH - 1)
            var v = buf[i] * scale
            if (h != null) {
                val e = err[c]
                var p = errPos[c]
                for (k in h.indices) { v -= h[k] * e[p]; p = if (p == 0) e.size - 1 else p - 1 }
            }
            val d = when (type) {
                DitherType.RECTANGULAR -> random.nextDouble() - 0.5
                DitherType.TRIANGULAR_HP -> { val r = random.nextFloat() - 0.5f; val o = r - lastRand[c]; lastRand[c] = r; o.toDouble() }
                else -> random.nextDouble() - random.nextDouble()
            }
            val q = (v + d).roundToLong().coerceIn(min, max)
            if (h != null) {
                // Clipping makes the error huge; cap it so the feedback loop can't run away
                val p = (errPos[c] + 1) % err[c].size
                err[c][p] = (q - v).coerceIn(-1.5, 1.5)
                errPos[c] = p
            }
            buf[i] = (q / scale).toFloat()
        }
    }

    private companion object { const val MAX_CH = 8 }
}
