package com.clashplayer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun testLogEntryFormattedStringWithoutThrowable() {
        val entry = LogEntry(
            timestamp = 1700000000000L,
            level = LogLevel.AUDIO,
            tag = "AudioEngine",
            message = "Clock rate switched to 192000Hz"
        )

        val formatted = entry.toFormattedString()
        assertTrue(formatted.contains("[AUDIO]"))
        assertTrue(formatted.contains("[AudioEngine]"))
        assertTrue(formatted.contains("Clock rate switched to 192000Hz"))
    }

    @Test
    fun testLogEntryFormattedStringWithThrowable() {
        val exception = RuntimeException("Buffer underflow in native audio driver")
        val entry = LogEntry(
            timestamp = 1700000000000L,
            level = LogLevel.ERROR,
            tag = "Playback",
            message = "Audio buffer underflow",
            throwableStackTrace = "java.lang.RuntimeException: Buffer underflow in native audio driver\n\tat com.clashplayer.service.PlaybackService.emit"
        )

        val formatted = entry.toFormattedString()
        assertTrue(formatted.contains("[ERROR]"))
        assertTrue(formatted.contains("Audio buffer underflow"))
        assertTrue(formatted.contains("java.lang.RuntimeException: Buffer underflow"))
    }

    @Test
    fun testLogLevelLabels() {
        assertEquals("DEBUG", LogLevel.DEBUG.label)
        assertEquals("INFO", LogLevel.INFO.label)
        assertEquals("WARN", LogLevel.WARN.label)
        assertEquals("ERROR", LogLevel.ERROR.label)
        assertEquals("CRASH", LogLevel.CRASH.label)
        assertEquals("AUDIO", LogLevel.AUDIO.label)
    }
}
