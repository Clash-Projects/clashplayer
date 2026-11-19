package com.clashplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Pitch-preserving tempo change (WSOLA) for interleaved stereo. 40 ms Hann grains at 50% overlap; each grain is
 * taken within +-10 ms of its nominal position where it best continues the previous one, so periodic sound stays
 * in phase. Tempo 1 passes audio through untouched until the first stretch (then it stays on WSOLA until [reset]).
 */
class TempoProcessor(sampleRate: Int) {
    @Volatile var tempo = 1f
    private var rate = 0
    private var win = 0          // grain length, frames
    private var hop = 0          // output hop = win / 2
    private var search = 0
    private lateinit var window: FloatArray
    private var input = FloatArray(0) // interleaved stereo FIFO
    private var inFrames = 0
    private var nominal = 0.0    // analysis position of the next grain
    private var prev = 0         // start of the previous grain (may sit before the trimmed buffer)
    private var started = false
    private var overlap = FloatArray(0) // second half of the previous grain, windowed

    init { updateSampleRate(sampleRate) }

    fun updateSampleRate(sampleRate: Int) {
        if (sampleRate == rate) return
        rate = sampleRate
        win = (sampleRate * 0.04).roundToInt() and 1.inv()
        hop = win / 2
        search = sampleRate / 100
        window = FloatArray(win) { (0.5 - 0.5 * cos(2 * PI * it / win)).toFloat() } // periodic Hann sums to 1 at 50%
        overlap = FloatArray(hop * 2)
        reset()
    }

    fun reset() { inFrames = 0; nominal = search.toDouble(); started = false; overlap.fill(0f) }

    /** Output samples [process] can produce for [count] input samples, at most. */
    fun maxOut(count: Int) = ((count / 2) / 0.5f).toInt() * 2 + win * 2

    /** Time-stretches [count] stereo samples; returns the count written to [out]. */
    fun process(src: FloatArray, count: Int, out: FloatArray): Int {
        val t = tempo.coerceIn(0.5f, 3f)
        if (t == 1f && !started) { System.arraycopy(src, 0, out, 0, count); return count }
        if ((inFrames + count / 2) * 2 > input.size) input = input.copyOf((inFrames + count / 2) * 4)
        System.arraycopy(src, 0, input, inFrames * 2, count)
        inFrames += count / 2

        var written = 0
        while (true) {
            val nom = nominal.toInt()
            if (nom + search + win > inFrames || written + hop * 2 > out.size) break
            val start = if (!started) nom else bestStart(prev + hop, nom)
            // Overlap-add: first half of this grain onto the stored tail of the previous one
            for (i in 0 until hop) {
                val w = window[i]
                out[written + 2 * i] = overlap[2 * i] + input[2 * (start + i)] * w
                out[written + 2 * i + 1] = overlap[2 * i + 1] + input[2 * (start + i) + 1] * w
            }
            for (i in 0 until hop) {
                val w = window[hop + i]
                overlap[2 * i] = input[2 * (start + hop + i)] * w
                overlap[2 * i + 1] = input[2 * (start + hop + i) + 1] * w
            }
            written += hop * 2
            prev = start
            started = true
            nominal += hop * t
        }

        // Drop input that no future grain can reach
        val keep = minOf(prev + hop, nominal.toInt() - search).coerceIn(0, inFrames)
        if (keep > 0) {
            System.arraycopy(input, keep * 2, input, 0, (inFrames - keep) * 2)
            inFrames -= keep
            nominal -= keep
            prev -= keep
        }
        return written
    }

    /** Grain start within +-search of [nom] whose opening best matches the natural continuation at [natural]. */
    private fun bestStart(natural: Int, nom: Int): Int {
        val len = hop
        fun corr(s: Int, stride: Int): Float {
            var acc = 0f
            var i = 0
            while (i < len) {
                acc += (input[2 * (s + i)] + input[2 * (s + i) + 1]) * (input[2 * (natural + i)] + input[2 * (natural + i) + 1])
                i += stride
            }
            return acc
        }
        val lo = (nom - search).coerceAtLeast(0)
        val hi = nom + search
        var best = nom.coerceAtLeast(lo)
        var bestC = Float.NEGATIVE_INFINITY
        var s = lo
        while (s <= hi) { val c = corr(s, 4); if (c > bestC) { bestC = c; best = s }; s += 2 }
        val coarse = best
        var fineC = Float.NEGATIVE_INFINITY
        for (s2 in (coarse - 2).coerceAtLeast(lo)..(coarse + 2).coerceAtMost(hi)) {
            val c = corr(s2, 1); if (c > fineC) { fineC = c; best = s2 }
        }
        return best
    }
}
