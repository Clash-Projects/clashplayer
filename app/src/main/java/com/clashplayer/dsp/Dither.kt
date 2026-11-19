package com.clashplayer.dsp

import java.util.Random
import kotlin.math.roundToLong

/**
 * Dither for integer outputs. The fixed shaped types use 44.1 kHz error-feedback filters (triangular elsewhere);
 * the threshold-of-hearing types are designed for the actual sample rate.
 */
enum class DitherType(val label: String, internal val shaping: DoubleArray? = null, internal val athOrder: Int = 0, internal val athRangeDb: Double = 0.0) {
    NONE("None"),
    RECTANGULAR("Rectangular"),
    TRIANGULAR("Triangular"),
    TRIANGULAR_HP("Triangular HP"),
    F_WEIGHTED("F-weighted", doubleArrayOf(2.412, -3.370, 3.937, -4.174, 3.353, -2.205, 1.281, -0.569, 0.0847)),
    MODIFIED_E("Modified-E", doubleArrayOf(1.662, -1.263, 0.4827, -0.2913, 0.1268, -0.1124, 0.03252, -0.01265, -0.03524)),
    IMPROVED_E("Improved-E", doubleArrayOf(2.847, -4.685, 6.214, -7.184, 6.639, -5.032, 3.263, -1.632, 0.4191)),
    SHIBATA("Shibata", athOrder = 20, athRangeDb = 30.0),
    LOW_SHIBATA("Low Shibata", athOrder = 12, athRangeDb = 15.0);

    /** Error-feedback coefficients for [sampleRate], or null for plain dither. */
    internal fun filterFor(sampleRate: Int): DoubleArray? = when {
        athOrder > 0 -> athCache.getOrPut(name + sampleRate) { athShaping(sampleRate, athOrder, athRangeDb) }
        sampleRate == 44100 || sampleRate == 48000 -> shaping
        else -> null
    }

    private companion object {
        val athCache = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()
    }
}

/** Absolute threshold of hearing in dB SPL (Terhardt), [f] in Hz. */
internal fun athDb(f: Double): Double {
    val k = maxOf(f, 20.0) / 1000
    return 3.64 * Math.pow(k, -0.8) - 6.5 * Math.exp(-0.6 * (k - 3.3) * (k - 3.3)) + 1e-3 * k * k * k * k
}

/**
 * Noise-shaping filter whose noise spectrum follows the threshold of hearing, limited to [rangeDb] above its
 * minimum: an all-pole (LPC) fit to the inverse target gives a minimum-phase A(z) with |A|^2 proportional to it.
 */
internal fun athShaping(sampleRate: Int, order: Int, rangeDb: Double): DoubleArray {
    val n = 1024
    val ath = DoubleArray(n) { athDb((it + 0.5) * sampleRate / 2.0 / n) }
    val floor = ath.min()
    val inv = DoubleArray(n) { Math.pow(10.0, -(ath[it] - floor).coerceAtMost(rangeDb) / 10) }
    val r = DoubleArray(order + 1) { k -> (0 until n).sumOf { inv[it] * Math.cos(Math.PI * (it + 0.5) / n * k) } }
    r[0] *= 1 + 1e-9 // tiny white-noise floor keeps the recursion well conditioned
    // Levinson-Durbin: A(z) = 1 + a1 z^-1 + ...
    val a = DoubleArray(order + 1).also { it[0] = 1.0 }
    var e = r[0]
    for (i in 1..order) {
        var acc = r[i]
        for (j in 1 until i) acc += a[j] * r[i - j]
        val k = -acc / e
        val prev = a.copyOf()
        for (j in 1 until i) a[j] = prev[j] + k * prev[i - j]
        a[i] = k
        e *= 1 - k * k
    }
    return DoubleArray(order) { -a[it + 1] } // NTF = 1 - sum(h z^-k)
}

/**
 * Quantizes float samples onto the [bits]-bit grid in place, so the packer's rounding is exact afterwards.
 * Noise shaping is error feedback: y = Q(x - sum(h * e) + d), giving a noise transfer of 1 - sum(h * z^-k).
 */
class Ditherer(var type: DitherType = DitherType.TRIANGULAR) {
    private val random = Random(0x5EED)
    private val lastRand = FloatArray(MAX_CH)
    private val err = Array(MAX_CH) { DoubleArray(24) }
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
        val h = type.filterFor(sampleRate)
        for (i in 0 until count) {
            val s = buf[i]
            if (s.isNaN() || s.isInfinite()) {
                buf[i] = 0f
                continue
            }
            val c = (i % channels).coerceAtMost(MAX_CH - 1)
            var v = s * scale
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
