package com.clashplayer.service

import com.clashplayer.dsd.Dop
import com.clashplayer.dsd.DsdSource
import com.clashplayer.dsd.DsdToPcm
import java.io.Closeable
import java.nio.channels.FileChannel

/**
 * DSF/DFF playback. In DoP mode ([useDop]) the DSD stream is carried bit-exact inside 24-bit PCM at DSD rate / 16,
 * for a DAC that decodes DoP; otherwise it is converted to 88.2/96 kHz PCM.
 */
class DsdDecoder(channel: FileChannel, private val owner: Closeable) : AudioDecoder {
    private val src = DsdSource(channel)
    val dsdRate = src.dsdRate
    /** PCM rate a DoP stream needs (176.4 kHz for DSD64). */
    val dopRate = dsdRate / 16
    private val pcm = DsdToPcm(dsdRate, src.channels)

    /** Must be decided before the first [read]; DoP is never downmixed or processed. */
    var useDop = false
        set(value) { field = value; if (value) outChannels = channels }

    /** Native DSD: the DAC's raw-data mode at DSD rate / 32; read with [readRaw] instead of [read]. */
    var nativeDsd = false
        set(value) { field = value; if (value) outChannels = channels }
    val nativeRate = dsdRate / 32

    override val sampleRate get() = if (nativeDsd) nativeRate else if (useDop) dopRate else pcm.outRate
    override val channels = src.channels
    override val bitDepth = 24
    override var outChannels = 2
    override val positionUs get() = src.position * 8_000_000L / dsdRate
    override val durationMs = src.bytesPerChannel * 8_000L / dsdRate

    private var bytes = Array(channels) { ByteArray(0) }
    private var marker = Dop.MARKER_A
    private var mono = FloatArray(0)
    private val downmix = Downmix.gains(channels)

    override fun read(out: FloatArray): Int {
        if (useDop) {
            val frames = out.size / channels
            ensure(frames * 2)
            val got = src.read(bytes, frames * 2) / 2
            if (got == 0) return -1
            marker = Dop.pack(bytes, channels, got, out, marker)
            return got * channels
        }
        val outCh = outChannels
        val frames = out.size / outCh
        ensure(frames * pcm.bytesPerSample)
        val got = src.read(bytes, frames * pcm.bytesPerSample)
        if (got == 0) return -1
        val produced = got / pcm.bytesPerSample
        if (outCh == channels) {
            for (c in 0 until channels) pcm.process(c, bytes[c], got, out, c, channels)
        } else {
            if (mono.size < produced) mono = FloatArray(produced)
            java.util.Arrays.fill(out, 0, produced * 2, 0f)
            for (c in 0 until channels) {
                pcm.process(c, bytes[c], got, mono, 0, 1)
                val gl = downmix[c][0]; val gr = downmix[c][1]
                for (f in 0 until produced) { out[2 * f] += mono[f] * gl; out[2 * f + 1] += mono[f] * gr }
            }
        }
        return produced * outCh
    }

    /** Fills [out] with whole 32-bit slots per channel (4 DSD bytes each, earliest first); returns bytes, or -1 at the end. */
    fun readRaw(out: ByteArray): Int {
        val frames = out.size / (4 * channels)
        ensure(frames * 4)
        val got = src.read(bytes, frames * 4) / 4
        if (got == 0) return -1
        var o = 0
        for (f in 0 until got) for (c in 0 until channels) {
            System.arraycopy(bytes[c], 4 * f, out, o, 4)
            o += 4
        }
        return o
    }

    private fun ensure(n: Int) {
        if (bytes[0].size < n) bytes = Array(channels) { ByteArray(n) }
    }

    override fun seekTo(positionMs: Long) {
        src.seek(positionMs * dsdRate / 8000)
        pcm.reset()
        marker = Dop.MARKER_A
    }

    override fun close() = owner.close()
}
