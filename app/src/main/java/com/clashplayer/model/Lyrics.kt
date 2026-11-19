package com.clashplayer.model

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val isInterlude: Boolean = false
)

data class Lyrics(
    val trackId: Long,
    val lines: List<LyricsLine>,
    val isSynced: Boolean = true
) {
    fun currentLineIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        for (i in lines.indices.reversed()) {
            if (positionMs >= lines[i].timeMs) {
                return i
            }
        }
        return 0
    }

    companion object {
        fun parseLrc(lrcContent: String, trackId: Long = 0L): Lyrics {
            val lines = mutableListOf<LyricsLine>()
            val pattern = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")

            for (raw in lrcContent.lines()) {
                val match = pattern.find(raw.trim())
                if (match != null) {
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val msPart = match.groupValues[3]
                    val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
                    val totalMs = (min * 60 + sec) * 1000 + ms
                    val text = match.groupValues[4].trim()

                    if (text.isNotEmpty()) {
                        lines.add(LyricsLine(totalMs, text, false))
                    }
                }
            }

            lines.sortBy { it.timeMs }

            // Insert musical interlude marker if gap between lines > 8 seconds
            val withInterludes = mutableListOf<LyricsLine>()
            for (i in lines.indices) {
                withInterludes.add(lines[i])
                if (i < lines.size - 1) {
                    val gap = lines[i + 1].timeMs - lines[i].timeMs
                    if (gap > 8000) {
                        withInterludes.add(LyricsLine(lines[i].timeMs + 2000, "•••", isInterlude = true))
                    }
                }
            }

            return Lyrics(trackId, if (withInterludes.isNotEmpty()) withInterludes else lines, isSynced = lines.isNotEmpty())
        }
    }
}
