package com.clashplayer.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

class DsdRemasterTest {

    @Test fun ntfPeakGainIsOnePointFive() {
        val (num, den) = DsdRemaster.ntf(5)
        var peak = 0.0
        for (i in 0..512) {
            val w = PI * i / 512
            fun mag(p: DoubleArray): Double { var re = 0.0; var im = 0.0; for (k in p.indices) { re += p[k] * cos(w * k); im -= p[k] * sin(w * k) }; return Math.hypot(re, im) }
            peak = maxOf(peak, mag(num) / mag(den))
        }
        assertEquals(1.5, peak, 0.01)
    }

    /** Remasters a 1 kHz tone to DSD64, decodes it back to PCM and checks the in-band noise. */
    @Test fun remasteredToneDecodesCleanly() {
        val rate = 44100
        val frames = rate // 1 s
        val src = FloatArray(frames * 2) { (0.5 * sin(2 * PI * 1000 * (it / 2) / rate)).toFloat() }
        val rm = DsdRemaster(rate, 64 * 44100, 2)
        val dop = FloatArray(rm.maxOut(src.size))
        val n = rm.process(src, src.size, dop)
        assertEquals(frames * 64 / 16.0, n / 2.0, 200.0) // minus the resampler latency

        // DoP -> bytes: markers alternate, payload is the low 16 bits, MSB first
        val bytes = ByteArray(n) // n/2 frames * 2 bytes, left channel only
        var lastMarker = -1
        for (f in 0 until n / 2) {
            val w = (dop[2 * f] * 8388608f).roundToInt() and 0xFFFFFF
            val marker = w shr 16
            assertTrue(marker == Dop.MARKER_A || marker == Dop.MARKER_B)
            assertTrue(marker != lastMarker); lastMarker = marker
            bytes[2 * f] = (w shr 8).toByte(); bytes[2 * f + 1] = w.toByte()
        }
        val pcm = DsdToPcm(64 * 44100, 1)
        val out = FloatArray(bytes.size)
        val got = pcm.process(0, bytes, bytes.size, out, 0, 1)

        // Least-squares 1 kHz fit over the settled middle
        val y = out.copyOfRange(got / 4, got * 3 / 4)
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var xs = 0.0; var xc = 0.0
        for (i in y.indices) { val w = 2 * PI * 1000 * i / pcm.outRate; val s = sin(w); val c = cos(w); ss += s * s; cc += c * c; sc += s * c; xs += y[i] * s; xc += y[i] * c }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det; val b = (xc * ss - xs * sc) / det
        // Audio-band noise: residual through a 20 kHz windowed-sinc low-pass (DSD noise climbs steeply above it)
        val res = DoubleArray(y.size) { i -> val w = 2 * PI * 1000 * i / pcm.outRate; y[i] - (a * sin(w) + b * cos(w)) }
        val taps = 255; val fc = 20000.0 / pcm.outRate
        val h = DoubleArray(taps) { k -> val x = k - taps / 2; val sinc = if (x == 0) 2 * fc else sin(2 * PI * fc * x) / (PI * x); sinc * (0.42 - 0.5 * cos(2 * PI * k / (taps - 1)) + 0.08 * cos(4 * PI * k / (taps - 1))) }
        var noise = 0.0
        for (i in taps until res.size) { var acc = 0.0; for (k in 0 until taps) acc += h[k] * res[i - k]; noise += acc * acc }
        noise /= res.size - taps
        val snr = 10 * log10((a * a + b * b) / 2 / noise)
        val amp = Math.hypot(a, b)
        // 0 dBFS PCM is 50% modulation, so a 0.5 tone comes back at 0.25
        assertEquals(0.25, amp, 0.01)
        assertTrue("SNR $snr", snr > 80)
        assertTrue(y.all { abs(it) < 0.5f })
    }
}
