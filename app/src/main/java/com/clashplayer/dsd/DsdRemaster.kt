package com.clashplayer.dsd

import com.clashplayer.dsp.Resampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tan

/**
 * PCM to DSD, delivered as DoP: band-limited resample to 8x 44.1/48 kHz, linear interpolation up to the DSD rate,
 * then a 5th-order 1-bit sigma-delta modulator per channel. The noise transfer function has all five zeros at DC and
 * Butterworth poles placed for a 1.5 peak gain (Lee's stability rule). Input 0 dBFS maps to 50% modulation, the
 * usual DSD reference level.
 */
class DsdRemaster(val inRate: Int, val dsdRate: Int, val channels: Int, gainDb: Float = 0f) {
    val dopRate = dsdRate / 16
    private val interRate = if (dsdRate % 44100 == 0) 352800 else 384000
    private val up = dsdRate / interRate
    private val resampler = Resampler(inRate, interRate, channels, cutoff = 0.9)
    private val gain = 0.5f * 10f.pow(gainDb / 20f)
    private val c = DoubleArray(ORDER)  // numerator of NTF - 1
    private val d = DoubleArray(ORDER)  // NTF denominator (monic, a0 omitted)
    private val eHist = Array(channels) { DoubleArray(ORDER) }
    private val fHist = Array(channels) { DoubleArray(ORDER) }
    private val last = FloatArray(channels)
    private val bits = IntArray(channels)
    private var nBits = 0
    private val pair = Array(channels) { ByteArray(2) }
    private var byteIdx = 0
    private var marker = Dop.MARKER_A
    private var inter = FloatArray(0)
    private val frame = FloatArray(channels)
    private val dopFrame = FloatArray(channels)

    init {
        require(dsdRate % interRate == 0 && up >= 2) { "Unsupported DSD rate $dsdRate" }
        val (num, den) = ntf(ORDER)
        for (k in 0 until ORDER) { c[k] = num[k + 1] - den[k + 1]; d[k] = den[k + 1] }
    }

    /** DoP samples [process] can write for [count] input samples, at most. */
    fun maxOut(count: Int) = ((count / channels).toLong() * dopRate / inRate + 4).toInt() * channels

    /** Converts [count] interleaved PCM samples into DoP samples in [out]; returns the count written. */
    fun process(src: FloatArray, count: Int, out: FloatArray): Int {
        val need = resampler.maxOutFrames(count / channels) * channels
        if (inter.size < need) inter = FloatArray(need)
        val n = resampler.process(src, count, inter)
        var written = 0
        var i = 0
        while (i < n) {
            for (j in 1..up) {
                val t = j.toFloat() / up
                for (ch in 0 until channels) frame[ch] = (last[ch] + (inter[i + ch] - last[ch]) * t) * gain
                for (ch in 0 until channels) bits[ch] = (bits[ch] shl 1) or modulate(ch, frame[ch].toDouble())
                if (++nBits == 8) {
                    for (ch in 0 until channels) pair[ch][byteIdx] = bits[ch].toByte()
                    nBits = 0
                    if (++byteIdx == 2) {
                        if (written + channels > out.size) return written // caller sized out with maxOut
                        marker = Dop.pack(pair, channels, 1, out, marker, written)
                        written += channels
                        byteIdx = 0
                    }
                }
            }
            for (ch in 0 until channels) last[ch] = inter[i + ch]
            i += channels
        }
        return written
    }

    /** One 1-bit decision; error feedback through (NTF - 1). */
    private fun modulate(ch: Int, x: Double): Int {
        val e = eHist[ch]
        val f = fHist[ch]
        var fb = 0.0
        for (k in 0 until ORDER) fb += c[k] * e[k] - d[k] * f[k]
        if (abs(fb) > 1e3) { e.fill(0.0); f.fill(0.0); fb = 0.0 } // overload: restart the loop instead of oscillating
        val w = x + fb
        val y = if (w >= 0) 1.0 else -1.0
        System.arraycopy(e, 0, e, 1, ORDER - 1); e[0] = y - w
        System.arraycopy(f, 0, f, 1, ORDER - 1); f[0] = fb
        return if (y > 0) 1 else 0
    }

    fun reset() {
        resampler.reset()
        eHist.forEach { it.fill(0.0) }; fHist.forEach { it.fill(0.0) }
        last.fill(0f); nBits = 0; byteIdx = 0; marker = Dop.MARKER_A
    }

    companion object {
        private const val ORDER = 5

        /** DSD rate for a remaster target multiple (64, 128, 256, 512, 1024) and a PCM rate's family. */
        fun dsdRateFor(multiple: Int, pcmRate: Int, allow48k: Boolean) =
            multiple * if (allow48k && pcmRate % 48000 == 0) 48000 else 44100

        /** NTF = (1 - z^-1)^n / D(z): Butterworth high-pass poles, cutoff searched for a 1.5 gain at Nyquist. */
        internal fun ntf(n: Int): Pair<DoubleArray, DoubleArray> {
            val num = DoubleArray(n + 1) { k -> binomial(n, k) * if (k % 2 == 0) 1.0 else -1.0 }
            fun den(fc: Double): DoubleArray {
                val wc = 2 * tan(PI * fc)
                var poly = arrayOf(doubleArrayOf(1.0, 0.0)) // complex coefficients (re, im), in z^-1
                for (k in 0 until n) {
                    val th = PI * (2 * k + n + 1) / (2 * n)
                    val pr = kotlin.math.cos(th); val pi = kotlin.math.sin(th)
                    // High-pass analog pole wc / p, then bilinear z = (2 + s) / (2 - s)
                    val mag = pr * pr + pi * pi
                    val sr = wc * pr / mag; val si = -wc * pi / mag
                    val ar = 2 + sr; val ai = si; val br = 2 - sr; val bi = -si
                    val bm = br * br + bi * bi
                    val zr = (ar * br + ai * bi) / bm; val zi = (ai * br - ar * bi) / bm
                    // multiply by (1 - z_p z^-1)
                    val next = Array(poly.size + 1) { doubleArrayOf(0.0, 0.0) }
                    for (j in poly.indices) {
                        next[j][0] += poly[j][0]; next[j][1] += poly[j][1]
                        next[j + 1][0] -= poly[j][0] * zr - poly[j][1] * zi
                        next[j + 1][1] -= poly[j][0] * zi + poly[j][1] * zr
                    }
                    poly = next
                }
                return DoubleArray(n + 1) { poly[it][0] }
            }
            fun nyquistGain(dn: DoubleArray) = abs(num.indices.sumOf { num[it] * if (it % 2 == 0) 1.0 else -1.0 } /
                dn.indices.sumOf { dn[it] * if (it % 2 == 0) 1.0 else -1.0 })
            var lo = 1e-4; var hi = 0.45
            repeat(60) { val mid = (lo + hi) / 2; if (nyquistGain(den(mid)) > 1.5) hi = mid else lo = mid }
            return num to den(lo)
        }

        private fun binomial(n: Int, k: Int): Double { var r = 1.0; for (i in 1..k) r = r * (n - k + i) / i; return r }
    }
}
