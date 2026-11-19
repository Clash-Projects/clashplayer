package com.clashplayer.dsd

import com.clashplayer.service.Downmix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DsdTest {

    private fun tempFile(bytes: ByteArray) = File.createTempFile("dsd", ".bin").apply { deleteOnExit(); writeBytes(bytes) }

    /** Minimal DSF: [blocks] blocks of [block] bytes per channel, LSB-first. */
    private fun dsf(channels: Int, rate: Int, perChannel: List<ByteArray>, block: Int): ByteArray {
        val bytesPer = perChannel[0].size
        val groups = (bytesPer + block - 1) / block
        val data = ByteArray(groups * block * channels)
        for (g in 0 until groups) for (c in 0 until channels) for (k in 0 until block) {
            val src = g * block + k
            data[(g * channels + c) * block + k] = if (src < bytesPer) perChannel[c][src] else 0
        }
        val b = ByteBuffer.allocate(92 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("DSD ".toByteArray()).putLong(28).putLong(92L + data.size).putLong(0)
        b.put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(channels).putInt(rate)
            .putInt(1).putLong(bytesPer * 8L).putInt(block).putInt(0)
        b.put("data".toByteArray()).putLong(12L + data.size).put(data)
        return b.array()
    }

    @Test
    fun testDsfReadsAcrossBlocksAndReversesBitOrder() {
        val l = ByteArray(10) { (0x01 shl (it % 8)).toByte() } // LSB-first single bits
        val r = ByteArray(10) { 0x0F }
        val f = tempFile(dsf(2, 2822400, listOf(l, r), block = 4))
        RandomAccessFile(f, "r").use { raf ->
            val src = DsdSource(raf.channel)
            assertEquals(2, src.channels); assertEquals(2822400, src.dsdRate); assertEquals(10L, src.bytesPerChannel)
            val out = Array(2) { ByteArray(16) }
            assertEquals(10, src.read(out, 16))
            for (i in 0 until 10) {
                assertEquals((0x80 ushr (i % 8)).toByte(), out[0][i]) // now MSB-first
                assertEquals(0xF0.toByte(), out[1][i])
            }
            assertEquals(0, src.read(out, 16))
        }
    }

    @Test
    fun testDffReadsByteInterleavedMsbFirst() {
        val samples = ByteArray(8) { it.toByte() } // L0 R0 L1 R1 ...
        val prop = ByteBuffer.allocate(4 + 12 + 4 + 12 + 2 + 8 + 12 + 4).order(ByteOrder.BIG_ENDIAN)
            .put("SND ".toByteArray())
            .put("FS  ".toByteArray()).putLong(4).putInt(5644800)
            .put("CHNL".toByteArray()).putLong(10).putShort(2).put("SLFTSRGT".toByteArray())
            .put("CMPR".toByteArray()).putLong(4).put("DSD ".toByteArray()).array()
        val body = ByteBuffer.allocate(4 + 12 + prop.size + 12 + samples.size).order(ByteOrder.BIG_ENDIAN)
            .put("DSD ".toByteArray())
            .put("PROP".toByteArray()).putLong(prop.size.toLong()).put(prop)
            .put("DSD ".toByteArray()).putLong(samples.size.toLong()).put(samples).array()
        val file = ByteBuffer.allocate(12 + body.size).order(ByteOrder.BIG_ENDIAN)
            .put("FRM8".toByteArray()).putLong(body.size.toLong()).put(body).array()
        RandomAccessFile(tempFile(file), "r").use { raf ->
            val src = DsdSource(raf.channel)
            assertEquals(5644800, src.dsdRate); assertEquals(2, src.channels); assertEquals(4L, src.bytesPerChannel)
            val out = Array(2) { ByteArray(4) }
            assertEquals(4, src.read(out, 4))
            assertEquals(listOf<Byte>(0, 2, 4, 6), out[0].toList())
            assertEquals(listOf<Byte>(1, 3, 5, 7), out[1].toList())
        }
    }

    @Test
    fun testDopWordsMarkersAndExactRoundTrip() {
        val src = arrayOf(byteArrayOf(0x12, 0x34, 0x56, 0x78), byteArrayOf(0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte()))
        val out = FloatArray(4)
        val next = Dop.pack(src, 2, 2, out, Dop.MARKER_A)
        assertEquals(Dop.MARKER_A, next)
        val words = out.map { (it * 8388608f).toInt() and 0xFFFFFF }
        assertEquals(listOf(0x051234, 0x059ABC, 0xFA5678, 0xFADEF0), words)
    }

    /** Second-order sigma-delta modulation of a 1 kHz sine at DSD64, MSB-first. */
    private fun modulate(amplitude: Double, bytes: Int, rate: Int): ByteArray {
        var i1 = 0.0; var i2 = 0.0; var y = 0.0
        return ByteArray(bytes) { b ->
            var v = 0
            for (bit in 0 until 8) {
                val t = (b * 8 + bit).toDouble() / rate
                val x = amplitude * sin(2 * PI * 1000 * t)
                i1 += x - y; i2 += i1 - y
                y = if (i2 >= 0) 1.0 else -1.0
                v = (v shl 1) or (if (y > 0) 1 else 0)
            }
            v.toByte()
        }
    }

    @Test
    fun testDsdToPcmRecoversSineAndSilence() {
        val rate = 2822400
        val conv = DsdToPcm(rate, 1)
        assertEquals(88200, conv.outRate); assertEquals(4, conv.bytesPerSample)
        val dsd = modulate(0.5, 88200, rate) // 0.25 s
        val pcm = FloatArray(dsd.size / 4)
        assertEquals(pcm.size, conv.process(0, dsd, dsd.size, pcm, 0, 1))
        val settled = pcm.copyOfRange(4410, pcm.size) // skip filter warm-up
        val rms = sqrt(settled.sumOf { it.toDouble() * it } / settled.size)
        assertEquals(0.5 / sqrt(2.0), rms, 0.02) // amplitude 0.5 sine
        // Idle pattern is silence
        conv.reset()
        val idle = ByteArray(8820) { 0x69 }
        val quiet = FloatArray(idle.size / 4)
        conv.process(0, idle, idle.size, quiet, 0, 1)
        assertTrue(quiet.drop(300).all { abs(it) < 1e-3f })
    }

    @Test
    fun testDownmixNeverClipsAndKeepsStereoUntouched() {
        val st = Downmix.gains(2)
        assertEquals(1f, st[0][0], 0f); assertEquals(0f, st[0][1], 0f); assertEquals(1f, st[1][1], 0f)
        for (n in 1..8) {
            val g = Downmix.gains(n)
            assertTrue(g.sumOf { it[0].toDouble() } <= 1.0001 && g.sumOf { it[1].toDouble() } <= 1.0001)
        }
        val six = Downmix.gains(6)
        assertEquals(0f, six[3][0], 0f) // LFE dropped
        assertEquals(six[2][0], six[2][1], 0f) // centre to both sides equally
    }
}
