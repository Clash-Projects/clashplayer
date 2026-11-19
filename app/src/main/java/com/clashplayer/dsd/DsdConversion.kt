package com.clashplayer.dsd

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSD to PCM for outputs that can't take DoP: linear-phase FIR (Kaiser-windowed sinc, 28 kHz cutoff, beta=14.5,
 * >140 dB stopband rejection) decimating straight to 88.2/96 kHz. Each input byte is looked up in a per-position
 * table of its 8 taps, so the cost is one table lookup per input byte.
 */
class DsdToPcm(val dsdRate: Int, channels: Int) {
    val outRate = if (dsdRate % 44100 == 0) 88200 else 96000
    /** DSD bytes per channel consumed per PCM sample. */
    val bytesPerSample = dsdRate / outRate / 8
    private val tapBytes = 128 * (dsdRate / (outRate * 32)).coerceAtLeast(1)
    private val table: Array<FloatArray>
    private val history = Array(channels) { ByteArray(tapBytes) { 0x69 } } // idle pattern = silence
    private val head = IntArray(channels)

    init {
        require(bytesPerSample >= 1 && dsdRate % (outRate * 8) == 0) { "Unsupported DSD rate $dsdRate" }
        val h = filterCoefficients()
        // table[k][v]: contribution of byte v sitting k bytes back; its bit 7 is the earliest (largest delay)
        table = Array(tapBytes) { k ->
            FloatArray(256) { v ->
                var acc = 0.0
                for (j in 0 until 8) acc += h[8 * k + (7 - j)] * (if (v shr (7 - j) and 1 == 1) 1.0 else -1.0)
                acc.toFloat()
            }
        }
    }

    internal fun filterCoefficients(): DoubleArray {
        val n = tapBytes * 8
        val fc = 28_000.0 / dsdRate
        val beta = 14.5
        val i0Beta = besselI0(beta)
        val alpha = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val x = i - alpha
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val ratio = x / alpha
            val karg = beta * sqrt((1.0 - ratio * ratio).coerceAtLeast(0.0))
            val w = besselI0(karg) / i0Beta
            sinc * w
        }
        val sum = h.sum()
        return DoubleArray(n) { h[it] / sum }
    }

    /**
     * Converts [count] DSD bytes of channel [ch] from [src], writing one PCM sample per [bytesPerSample] bytes
     * into [out] at [outOffset] with [stride]. Returns samples written.
     */
    fun process(ch: Int, src: ByteArray, count: Int, out: FloatArray, outOffset: Int, stride: Int): Int {
        val hist = history[ch]
        var hd = head[ch]
        var o = outOffset
        var produced = 0
        var i = 0
        while (i + bytesPerSample <= count) {
            for (b in 0 until bytesPerSample) { hist[hd] = src[i + b]; hd = if (hd + 1 == tapBytes) 0 else hd + 1 }
            i += bytesPerSample
            var acc = 0f
            var p = hd
            for (k in tapBytes - 1 downTo 0) { // p walks oldest -> newest
                acc += table[k][hist[p].toInt() and 0xFF]
                p = if (p + 1 == tapBytes) 0 else p + 1
            }
            out[o] = acc
            o += stride
            produced++
        }
        head[ch] = hd
        return produced
    }

    fun reset() {
        history.forEach { it.fill(0x69) }
        head.fill(0)
    }

    companion object {
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            val xsq4 = (x * x) / 4.0
            while (term > 1e-16 * sum) {
                term *= xsq4 / (k * k)
                sum += term
                k++
            }
            return sum
        }
    }
}

/** DSD over PCM (DoP v1.1): two DSD bytes per channel in each 24-bit sample, under an alternating 0x05/0xFA marker. */
object Dop {
    const val MARKER_A = 0x05
    const val MARKER_B = 0xFA

    /**
     * Packs [frames] DoP frames from per-channel MSB-first DSD bytes (2 bytes per channel per frame) into [out]
     * at [outOffset] as floats that pack back to the exact 24-bit words. Returns the next marker to use.
     */
    fun pack(src: Array<ByteArray>, channels: Int, frames: Int, out: FloatArray, marker: Int, outOffset: Int = 0): Int {
        var m = marker
        var o = outOffset
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val word = (m shl 16) or ((src[c][2 * f].toInt() and 0xFF) shl 8) or (src[c][2 * f + 1].toInt() and 0xFF)
                val signed = (word shl 8) shr 8 // sign-extend 24 -> 32
                out[o++] = signed / 8388608f
            }
            m = if (m == MARKER_A) MARKER_B else MARKER_A
        }
        return m
    }
}
