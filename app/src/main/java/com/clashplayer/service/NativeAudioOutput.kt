package com.clashplayer.service

import java.io.Closeable
import java.io.IOException

/** Float PCM output through AAudio or OpenSL ES. Write from the audio thread; control calls may come from any thread. */
class NativeAudioOutput private constructor(private val handle: Long, val api: Int, val sampleRate: Int, val channels: Int) : Closeable {
    @Volatile private var closed = false

    val label get() = if (api == AAUDIO) "AAudio" else "OpenSL ES"
    val framesPlayed: Long get() = if (closed) 0 else nativeFramesPlayed(handle)

    /** Queues [count] interleaved samples, blocking while the buffer is full. */
    fun write(data: FloatArray, count: Int) {
        if (closed) return
        val r = nativeWrite(handle, data, count)
        if (r < 0) throw IOException("$label write failed ($r)")
    }

    fun start() { if (!closed) nativeStart(handle) }
    fun pause() { if (!closed) nativePause(handle) }
    fun flush() { if (!closed) nativeFlush(handle) }

    override fun close() {
        if (closed) return
        closed = true
        nativeClose(handle)
    }

    companion object {
        const val AAUDIO = 1
        const val OPENSL = 2

        init { System.loadLibrary("nativeout") }

        /** Opens a float stream; null if the platform refuses the format. */
        fun open(api: Int, sampleRate: Int, channels: Int, bufferMs: Int = 200): NativeAudioOutput? {
            val h = nativeOpen(api, sampleRate, channels, bufferMs)
            return if (h == 0L) null else NativeAudioOutput(h, api, sampleRate, channels)
        }

        @JvmStatic private external fun nativeOpen(api: Int, rate: Int, channels: Int, bufferMs: Int): Long
        @JvmStatic private external fun nativeWrite(handle: Long, data: FloatArray, count: Int): Int
        @JvmStatic private external fun nativeStart(handle: Long): Int
        @JvmStatic private external fun nativePause(handle: Long): Int
        @JvmStatic private external fun nativeFlush(handle: Long): Int
        @JvmStatic private external fun nativeFramesPlayed(handle: Long): Long
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
