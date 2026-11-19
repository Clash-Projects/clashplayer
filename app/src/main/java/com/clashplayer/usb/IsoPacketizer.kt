package com.clashplayer.usb

import kotlin.math.abs

/**
 * Decides how many audio frames go into each isochronous packet.
 * Rates are frames per packet in Q16 fixed point; the fractional remainder carries over, so the long-run
 * average is exact (44.1 kHz at 1000 packets/s gives nine 44s and one 45 every 10 ms).
 */
class IsoPacketizer(private val sampleRate: Int, val packetsPerSecond: Int, private val maxFramesPerPacket: Int) {
    val nominalQ16: Long = (sampleRate.toLong() shl 16) / packetsPerSecond
    @Volatile var currentQ16: Long = nominalQ16
        private set
    var feedbackActive = false
        private set
    private var accumulator = 0L

    fun nextPacketFrames(): Int {
        val frames = if (feedbackActive) {
            accumulator += currentQ16
            (accumulator shr 16).toInt()
        } else {
            // Exact rational: Q16 truncation would drop a frame every ~100 s at 44.1 kHz
            accumulator += sampleRate
            (accumulator / packetsPerSecond).toInt()
        }.coerceAtMost(maxFramesPerPacket)
        accumulator -= if (feedbackActive) frames.toLong() shl 16 else frames.toLong() * packetsPerSecond
        return frames
    }

    /** Applies a device feedback value (frames per packet, Q16); ignores values more than ±12.5% off nominal. */
    fun applyFeedback(q16: Long) {
        if (abs(q16 - nominalQ16) > nominalQ16 / 8) return
        if (!feedbackActive) {
            accumulator = accumulator * 65536 / packetsPerSecond // carry the fractional frame over to Q16
            feedbackActive = true
        }
        currentQ16 = q16
    }

    companion object {
        /**
         * Converts a raw feedback endpoint value to frames per data packet in Q16.
         * Full speed sends 10.14 frames per 1 ms frame in 3 bytes; high speed 16.16 frames per 125 us microframe
         * in 4 bytes. Some devices send the other format, so both readings are tried against the nominal rate.
         */
        fun feedbackToQ16(raw: Long, highSpeed: Boolean, busFramesPerPacket: Int, nominalQ16: Long): Long? {
            val uRaw = raw and 0xFFFFFFFFL
            val primary = if (highSpeed) uRaw else uRaw shl 2
            val alternate = if (highSpeed) uRaw shl 2 else uRaw
            for (perBusFrame in longArrayOf(primary, alternate)) {
                val perPacket = perBusFrame * busFramesPerPacket
                if (perPacket > 0 && abs(perPacket - nominalQ16) <= nominalQ16 / 8) return perPacket
            }
            return null
        }
    }
}
