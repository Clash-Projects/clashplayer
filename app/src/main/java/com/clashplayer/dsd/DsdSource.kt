package com.clashplayer.dsd

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Raw 1-bit DSD from a DSF (Sony) or DFF/DSDIFF (Philips, uncompressed) file.
 * [read] hands out bytes per channel, always MSB-first (earliest bit in bit 7).
 */
class DsdSource(private val ch: FileChannel) {
    val channels: Int
    val dsdRate: Int
    val bytesPerChannel: Long
    private val dataStart: Long
    private val dsf: Boolean
    private val lsbFirst: Boolean
    private val blockSize: Int // DSF only: bytes per channel per block
    var position = 0L // bytes per channel consumed
        private set

    private var group = ByteArray(0)
    private var groupIndex = -1L

    init {
        val head = read(0, 16)
        when (String(head, 0, 4, Charsets.US_ASCII)) {
            "DSD " -> {
                dsf = true
                var pos = 28L
                var fmt: ByteBuffer? = null
                var data = -1L
                while (data < 0 && pos + 12 <= ch.size()) {
                    val h = ByteBuffer.wrap(read(pos, 12)).order(ByteOrder.LITTLE_ENDIAN)
                    val id = String(h.array(), 0, 4, Charsets.US_ASCII)
                    val size = h.getLong(4)
                    if (id == "fmt ") fmt = ByteBuffer.wrap(read(pos, 52)).order(ByteOrder.LITTLE_ENDIAN)
                    if (id == "data") data = pos + 12
                    if (size < 12) throw IOException("Corrupt DSF chunk $id")
                    pos += size
                }
                val f = fmt ?: throw IOException("DSF without fmt chunk")
                if (data < 0) throw IOException("DSF without data chunk")
                if (f.getInt(16) != 0) throw IOException("Unsupported DSF format id ${f.getInt(16)}")
                channels = f.getInt(24)
                dsdRate = f.getInt(28)
                lsbFirst = f.getInt(32) == 1
                bytesPerChannel = (f.getLong(36) + 7) / 8
                blockSize = f.getInt(44)
                dataStart = data
            }
            "FRM8" -> {
                dsf = false
                lsbFirst = false
                blockSize = 0
                var pos = 16L
                var rate = 0; var chans = 0; var data = -1L; var dataLen = 0L
                fun walk(from: Long, to: Long) {
                    var p = from
                    while (p + 12 <= to) {
                        val h = ByteBuffer.wrap(read(p, 12)).order(ByteOrder.BIG_ENDIAN)
                        val id = String(h.array(), 0, 4, Charsets.US_ASCII)
                        val size = h.getLong(4)
                        val body = p + 12
                        when (id) {
                            "PROP" -> walk(body + 4, body + size) // skip "SND " form type
                            "FS  " -> rate = ByteBuffer.wrap(read(body, 4)).order(ByteOrder.BIG_ENDIAN).int
                            "CHNL" -> chans = ByteBuffer.wrap(read(body, 2)).order(ByteOrder.BIG_ENDIAN).short.toInt()
                            "CMPR" -> String(read(body, 4), Charsets.US_ASCII).let {
                                if (it != "DSD ") throw IOException("Compressed DFF ($it) is not supported")
                            }
                            "DSD " -> { data = body; dataLen = size }
                            "DST " -> throw IOException("DST-compressed DFF is not supported")
                        }
                        p = body + size + (size and 1) // chunks are padded to even length
                    }
                }
                walk(pos, ch.size())
                if (data < 0 || rate <= 0 || chans <= 0) throw IOException("Incomplete DFF header")
                channels = chans; dsdRate = rate; dataStart = data
                bytesPerChannel = dataLen / chans
            }
            else -> throw IOException("Not a DSF or DFF file")
        }
        if (channels !in 1..8) throw IOException("Unsupported DSD channel count $channels")
        if (dsdRate % 44100 != 0 && dsdRate % 48000 != 0) throw IOException("Unsupported DSD rate $dsdRate")
    }

    /** Reads up to [n] bytes per channel into [out][ch]; returns bytes per channel read (0 at end). */
    fun read(out: Array<ByteArray>, n: Int): Int {
        val count = minOf(n.toLong(), bytesPerChannel - position).toInt()
        if (count <= 0) return 0
        if (dsf) {
            var done = 0
            while (done < count) {
                val gi = (position + done) / blockSize
                if (gi != groupIndex) loadGroup(gi)
                val within = ((position + done) % blockSize).toInt()
                val take = minOf(count - done, blockSize - within)
                for (c in 0 until channels) {
                    val src = c * blockSize + within
                    for (k in 0 until take) {
                        val b = group[src + k]
                        out[c][done + k] = if (lsbFirst) REVERSE[b.toInt() and 0xFF] else b
                    }
                }
                done += take
            }
        } else {
            val bytes = read(dataStart + position * channels, count * channels)
            for (k in 0 until count) for (c in 0 until channels) out[c][k] = bytes[k * channels + c]
        }
        position += count
        return count
    }

    fun seek(bytePerChannel: Long) {
        // DSF: snap to a block boundary (<= 4096 bytes, ~12 ms at DSD64) so blocks stay aligned
        position = if (dsf) (bytePerChannel / blockSize) * blockSize else bytePerChannel
        position = position.coerceIn(0, bytesPerChannel)
    }

    private fun loadGroup(index: Long) {
        if (group.size != blockSize * channels) group = ByteArray(blockSize * channels)
        val buf = ByteBuffer.wrap(group)
        var p = dataStart + index * blockSize * channels
        while (buf.hasRemaining()) { val r = ch.read(buf, p); if (r <= 0) break; p += r }
        while (buf.hasRemaining()) buf.put(0x69) // DSD idle pattern past end of file
        groupIndex = index
    }

    private fun read(at: Long, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n)
        var p = at
        while (buf.hasRemaining()) { val r = ch.read(buf, p); if (r <= 0) throw IOException("Truncated DSD file"); p += r }
        return buf.array()
    }

    companion object {
        private val REVERSE = ByteArray(256) { Integer.reverse(it).ushr(24).toByte() }

        /** True if [head] (first 4 bytes) starts a DSF or DFF file. */
        fun isDsd(head: ByteArray) = head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII).let { it == "DSD " || it == "FRM8" }
    }
}
