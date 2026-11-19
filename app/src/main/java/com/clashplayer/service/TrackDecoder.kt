package com.clashplayer.service

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes one PCM-codec audio file (FLAC, ALAC, WAV, MP3, AAC, ...) with the platform codecs.
 * Integer PCM is scaled by 1/2^(bits-1), so it packs back to the same integers bit for bit.
 */
class TrackDecoder(context: Context, uri: Uri) : AudioDecoder {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    override val sampleRate: Int
    override val channels: Int
    override val bitDepth: Int
    override var outChannels = 2
    override val durationMs: Long
    private var outEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var pending: ByteBuffer? = null
    private var pendingIndex = -1
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private val frame: FloatArray
    private val downmix: Array<FloatArray>

    override var positionUs = 0L
        private set

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw IllegalArgumentException("No audio track in $uri") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // Lossless containers report their source depth; lossy decodes are 16-bit by definition
        bitDepth = if (format.containsKey(KEY_BITS_PER_SAMPLE)) format.getInteger(KEY_BITS_PER_SAMPLE) else 16
        if (bitDepth > 16) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        frame = FloatArray(channels)
        downmix = Downmix.gains(channels)
    }

    override fun read(out: FloatArray): Int {
        val outCh = outChannels
        var written = 0
        while (written + outCh <= out.size) {
            val buf = pending ?: nextOutput() ?: break
            val bytesPerSample = when (outEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_32BIT -> 4
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                else -> 2
            }
            val frameBytes = bytesPerSample * channels
            while (written + outCh <= out.size && buf.remaining() >= frameBytes) {
                for (c in 0 until channels) frame[c] = sample(buf)
                if (outCh == channels) {
                    System.arraycopy(frame, 0, out, written, channels)
                } else if (channels == 2) {
                    out[written] = frame[0]; out[written + 1] = frame[1]
                } else {
                    var l = 0f; var r = 0f
                    for (c in 0 until channels) { l += frame[c] * downmix[c][0]; r += frame[c] * downmix[c][1] }
                    out[written] = l; out[written + 1] = r
                }
                written += outCh
            }
            if (buf.remaining() < frameBytes) {
                codec.releaseOutputBuffer(pendingIndex, false)
                pending = null
            }
        }
        return if (written == 0 && outputDone) -1 else written
    }

    private fun sample(buf: ByteBuffer): Float = when (outEncoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val f = buf.float
            if (f.isNaN() || f.isInfinite()) 0f else f
        }
        AudioFormat.ENCODING_PCM_32BIT -> buf.int / 2147483648.0f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() // sign-extended
            val v = (b2 shl 16) or (b1 shl 8) or b0
            v / 8388608.0f
        }
        else -> buf.short / 32768f
    }

    private fun nextOutput(): ByteBuffer? {
        while (!outputDone) {
            if (!inputDone) feedInput()
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    outEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                }
                idx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size == 0) { codec.releaseOutputBuffer(idx, false); continue }
                    positionUs = info.presentationTimeUs
                    val buf = codec.getOutputBuffer(idx)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset).limit(info.offset + info.size)
                    pending = buf
                    pendingIndex = idx
                    return buf
                }
            }
        }
        return null
    }

    private fun feedInput() {
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) return
        val size = extractor.readSampleData(codec.getInputBuffer(idx)!!, 0)
        if (size < 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (pending != null) { codec.releaseOutputBuffer(pendingIndex, false); pending = null }
        extractor.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        positionUs = positionMs * 1000
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private companion object {
        // Set by the platform extractors for FLAC/WAV/ALAC; not exposed as a MediaFormat constant
        const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
    }
}
