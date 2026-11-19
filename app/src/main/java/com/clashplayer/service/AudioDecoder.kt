package com.clashplayer.service

import java.io.Closeable

/** A source of interleaved float PCM for the audio loop. Not thread-safe: use from the audio thread. */
interface AudioDecoder : Closeable {
    /** Rate of the PCM handed out by [read]. */
    val sampleRate: Int
    /** Channels in the source. */
    val channels: Int
    /** Integer bits the output carries exactly. */
    val bitDepth: Int
    /** Channels per frame handed out by [read]: [channels] for pass-through, or 2 for a stereo downmix. */
    var outChannels: Int
    /** Presentation time of the last frame handed out, in microseconds. */
    val positionUs: Long
    /** Track length, or 0 if unknown. */
    val durationMs: Long

    /** Fills [out] with whole frames; returns the sample count written, or -1 at end of stream. */
    fun read(out: FloatArray): Int
    fun seekTo(positionMs: Long)
}

/** ITU-R BS.775 style stereo downmix for Android's default channel orders (FL FR FC LFE BL BR SL SR...). */
object Downmix {
    private const val M3DB = 0.70710677f

    /** Per-source-channel (left, right) gains, normalized so a full-scale signal on every channel can't clip. */
    fun gains(channels: Int): Array<FloatArray> {
        val roles = when (channels) {
            1 -> listOf("C")
            2 -> listOf("L", "R")
            3 -> listOf("L", "R", "C")
            4 -> listOf("L", "R", "BL", "BR")
            5 -> listOf("L", "R", "C", "BL", "BR")
            6 -> listOf("L", "R", "C", "LFE", "BL", "BR")
            7 -> listOf("L", "R", "C", "LFE", "BL", "BR", "BC")
            else -> listOf("L", "R", "C", "LFE", "BL", "BR", "SL", "SR") + List((channels - 8).coerceAtLeast(0)) { "X" }
        }
        val g = roles.map { r ->
            when (r) {
                "L" -> floatArrayOf(1f, 0f); "R" -> floatArrayOf(0f, 1f)
                "C" -> if (channels == 1) floatArrayOf(1f, 1f) else floatArrayOf(M3DB, M3DB)
                "BL", "SL" -> floatArrayOf(M3DB, 0f); "BR", "SR" -> floatArrayOf(0f, M3DB)
                "BC" -> floatArrayOf(0.5f, 0.5f)
                else -> floatArrayOf(0f, 0f) // LFE and unknown channels are dropped, as in BS.775
            }
        }.toTypedArray()
        val norm = maxOf(g.sumOf { it[0].toDouble() }, g.sumOf { it[1].toDouble() }).toFloat().coerceAtLeast(1f)
        g.forEach { it[0] /= norm; it[1] /= norm }
        return g
    }
}
