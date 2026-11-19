package com.clashplayer.dsp

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming band-limited resampler: Kaiser-windowed sinc, [HALF] zero crossings per side, a 512-phase table
 * with linear interpolation between phases. Stopband is around -100 dB, which a 24-bit DAC can't resolve.
 * [cutoff] is the passband edge as a fraction of the lower Nyquist frequency.
 */
class Resampler(val inRate: Int, val outRate: Int, val channels: Int, cutoff: Double = 0.95) {
    private val step = inRate.toDouble() / outRate
    private val taps = 2 * HALF
    private val table = FloatArray((PHASES + 1) * taps)
    private var buf = FloatArray(0)
    private var frames = 0
    private var pos = 0.0

    init {
        // Downsampling lowers the cutoff to the output Nyquist; scale keeps unity passband gain
        val fc = cutoff * min(1.0, outRate.toDouble() / inRate)
        for (p in 0..PHASES) for (k in 0 until taps) {
            val t = (k - HALF + 1) - p.toDouble() / PHASES
            val x = fc * t
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val w = t / HALF
            table[p * taps + k] = (fc * sinc * if (w * w < 1) bessel0(BETA * sqrt(1 - w * w)) / bessel0(BETA) else 0.0).toFloat()
        }
        reset()
    }

    /** Output frames produced for [inFrames] input frames, at most. */
    fun maxOutFrames(inFrames: Int) = (inFrames / step).toInt() + 2

    /** Forgets all history (seek or new stream). */
    fun reset() {
        // HALF-1 frames of leading silence so the first input frame can be centred under the kernel
        frames = HALF - 1
        if (buf.size < frames * channels) buf = FloatArray(4096 * channels)
        java.util.Arrays.fill(buf, 0, frames * channels, 0f)
        pos = (HALF - 1).toDouble()
    }

    /** Resamples [count] interleaved samples from [input] into [out]; returns the sample count written. */
    fun process(input: FloatArray, count: Int, out: FloatArray): Int {
        val inFrames = count / channels
        if ((frames + inFrames) * channels > buf.size) buf = buf.copyOf((frames + inFrames) * channels * 2)
        System.arraycopy(input, 0, buf, frames * channels, inFrames * channels)
        frames += inFrames

        var written = 0
        while (true) {
            val i0 = floor(pos).toInt()
            if (i0 + HALF >= frames || written + channels > out.size) break
            val ph = (pos - i0) * PHASES
            val p = ph.toInt()
            val f = (ph - p).toFloat()
            val a = p * taps
            val b = a + taps
            val first = i0 - HALF + 1
            for (c in 0 until channels) {
                var acc = 0.0
                var idx = first * channels + c
                for (k in 0 until taps) {
                    val h = table[a + k] + f * (table[b + k] - table[a + k])
                    acc += buf[idx] * h
                    idx += channels
                }
                out[written + c] = acc.toFloat()
            }
            written += channels
            pos += step
        }

        // Keep only the history the next output frame still needs
        val drop = (floor(pos).toInt() - HALF + 1).coerceIn(0, frames)
        if (drop > 0) {
            System.arraycopy(buf, drop * channels, buf, 0, (frames - drop) * channels)
            frames -= drop
            pos -= drop
        }
        return written
    }

    companion object {
        const val HALF = 32
        private const val PHASES = 512
        private const val BETA = 10.0

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                k++
            }
            return sum
        }

        /**
         * Rate to run a device at when it can't take [source]: an integer multiple of the source if one is offered
         * (cleanest conversion), else the lowest rate above it, else the highest available.
         */
        fun pickRate(source: Int, supported: Collection<Int>): Int? {
            val rates = supported.filter { it > 0 }.distinct().sorted()
            if (rates.isEmpty()) return null
            if (source in rates) return source
            return rates.firstOrNull { it > source && it % source == 0 }
                ?: rates.firstOrNull { it > source }
                ?: rates.last()
        }
    }
}
