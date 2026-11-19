package com.clashplayer.dsp

import java.nio.ByteBuffer
import kotlin.math.roundToLong

/**
 * Packs float samples into little-endian integer PCM.
 * Exact for floats decoded as int / 2^(bits-1), so untouched samples come back out bit for bit.
 */
fun packPcm(src: FloatArray, count: Int, bits: Int, out: ByteBuffer, offset: Int = 0) {
    val scale = (1L shl (bits - 1)).toDouble()
    val max = (1L shl (bits - 1)) - 1
    val min = -(1L shl (bits - 1))
    out.clear()
    for (i in 0 until count) {
        val s = src[offset + i]
        val v = if (s.isNaN() || s.isInfinite()) {
            0L
        } else {
            (s.toDouble() * scale).roundToLong().coerceIn(min, max)
        }
        for (b in 0 until bits / 8) out.put((v shr (8 * b)).toByte())
    }
    out.flip()
}
