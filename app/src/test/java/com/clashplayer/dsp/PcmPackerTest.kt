package com.clashplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmPackerTest {
    @Test
    fun testPackIsBitExactFor16And24Bit() {
        for (bits in intArrayOf(16, 24)) {
            val full = 1 shl (bits - 1)
            val ints = intArrayOf(0, 1, -1, full - 1, -full, 12345, -54321 % full)
            val floats = FloatArray(ints.size) { ints[it].toFloat() / full }
            val out = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            packPcm(floats, floats.size, bits, out)
            assertEquals(ints.size * bits / 8, out.remaining())
            for (expected in ints) {
                var v = 0
                for (b in 0 until bits / 8) v = v or ((out.get().toInt() and 0xFF) shl (8 * b))
                v = (v shl (32 - bits)) shr (32 - bits) // sign-extend
                assertEquals(expected, v)
            }
        }
    }
}
