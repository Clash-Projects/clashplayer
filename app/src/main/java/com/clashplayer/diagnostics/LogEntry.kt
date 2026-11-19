package com.clashplayer.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    CRASH("CRASH"),
    AUDIO("AUDIO")
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableStackTrace: String? = null
) {
    fun toFormattedString(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val timeStr = dateFormat.format(Date(timestamp))
        val base = "[$timeStr] [${level.label}] [$tag] $message"
        return if (throwableStackTrace != null) {
            "$base\n$throwableStackTrace"
        } else {
            base
        }
    }
}
