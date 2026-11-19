package com.clashplayer.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.min

enum class ReplayGainMode(val label: String) { OFF("Off"), TRACK("Track"), ALBUM("Album") }

/** Loudness tags of one file; any field may be missing. */
data class ReplayGainInfo(val trackGain: Float? = null, val trackPeak: Float? = null, val albumGain: Float? = null, val albumPeak: Float? = null) {

    /**
     * Gain to apply in dB: the tag for [mode] (album falls back to track) plus [preampDb], or [untaggedDb] when the
     * file has none. With [preventClip] the result never pushes the tagged peak above full scale.
     */
    fun gainDb(mode: ReplayGainMode, preampDb: Float, untaggedDb: Float, preventClip: Boolean): Float {
        if (mode == ReplayGainMode.OFF) return 0f
        val album = mode == ReplayGainMode.ALBUM && albumGain != null
        val gain = (if (album) albumGain else trackGain) ?: return untaggedDb
        val peak = if (album) albumPeak ?: trackPeak else trackPeak ?: albumPeak
        val g = gain + preampDb
        return if (preventClip && peak != null && peak > 0f) min(g, -20f * log10(peak)) else g
    }

    companion object {
        /**
         * Reads ReplayGain from FLAC Vorbis comments or ID3v2 TXXX frames. [read] returns up to n bytes at an offset.
         * Unknown containers and untagged files give an empty info.
         */
        fun read(read: (offset: Long, n: Int) -> ByteArray): ReplayGainInfo {
            val head = read(0, 10)
            if (head.size < 10) return ReplayGainInfo()
            val tags = when {
                head.startsWith("fLaC") -> flacComments(read)
                head.startsWith("ID3") -> id3Txxx(head, read)
                else -> emptyMap()
            }
            fun num(key: String) = tags[key]?.trim()?.removeSuffix("dB")?.removeSuffix("DB")?.trim()?.toFloatOrNull()
            return ReplayGainInfo(num("REPLAYGAIN_TRACK_GAIN"), num("REPLAYGAIN_TRACK_PEAK"), num("REPLAYGAIN_ALBUM_GAIN"), num("REPLAYGAIN_ALBUM_PEAK"))
        }

        private fun ByteArray.startsWith(s: String) = s.indices.all { this[it] == s[it].code.toByte() }

        private fun flacComments(read: (Long, Int) -> ByteArray): Map<String, String> {
            var at = 4L
            repeat(64) {
                val h = read(at, 4)
                if (h.size < 4) return emptyMap()
                val type = h[0].toInt() and 0x7F
                val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                if (type == 4) return vorbisComments(ByteBuffer.wrap(read(at + 4, len)).order(ByteOrder.LITTLE_ENDIAN))
                if (h[0].toInt() and 0x80 != 0) return emptyMap() // last block, no comments
                at += 4 + len
            }
            return emptyMap()
        }

        private fun vorbisComments(b: ByteBuffer): Map<String, String> = runCatching {
            val vendor = b.int
            b.position(b.position() + vendor)
            val n = b.int
            buildMap {
                repeat(n) {
                    val bytes = ByteArray(b.int).also(b::get)
                    val s = String(bytes, Charsets.UTF_8)
                    val eq = s.indexOf('=')
                    if (eq > 0) put(s.substring(0, eq).uppercase(), s.substring(eq + 1))
                }
            }
        }.getOrDefault(emptyMap())

        private fun id3Txxx(head: ByteArray, read: (Long, Int) -> ByteArray): Map<String, String> {
            val version = head[3].toInt()
            val size = syncsafe(head, 6)
            val tag = read(10, size)
            val out = mutableMapOf<String, String>()
            var p = 0
            if (head[5].toInt() and 0x40 != 0 && tag.size >= 4) p += if (version >= 4) syncsafe(tag, 0) else 4 + be32(tag, 0) // extended header
            while (p + 10 <= tag.size && tag[p] != 0.toByte()) {
                val id = String(tag, p, 4, Charsets.ISO_8859_1)
                val len = if (version >= 4) syncsafe(tag, p + 4) else be32(tag, p + 4)
                val body = p + 10
                if (len <= 0 || body + len > tag.size) break
                if (id == "TXXX") {
                    val enc = tag[body].toInt()
                    val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                    val parts = String(tag, body + 1, len - 1, cs).split('\u0000').map { it.trim('﻿') }.filter { it.isNotEmpty() }
                    if (parts.size >= 2) out[parts[0].uppercase()] = parts[1]
                }
                p = body + len
            }
            return out
        }

        private fun syncsafe(b: ByteArray, o: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 7) or (b[o + i].toInt() and 0x7F) }
        private fun be32(b: ByteArray, o: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (b[o + i].toInt() and 0xFF) }
    }
}
