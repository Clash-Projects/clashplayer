package com.clashplayer.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(
    private val context: Context,
    private val appStartTimeMs: Long = SystemClock.elapsedRealtime()
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = generateCrashReport(thread, throwable)
            saveCrashReport(report)
            DiagnosticsManager.recordCrash(report)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun generateCrashReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTraceStr = sw.toString()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val crashTimeStr = dateFormat.format(Date())
        val uptimeSeconds = (SystemClock.elapsedRealtime() - appStartTimeMs) / 1000

        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        return buildString {
            appendLine("================================================================================")
            appendLine("                             CLASHPLAYER CRASH REPORT                           ")
            appendLine("================================================================================")
            appendLine("Timestamp      : $crashTimeStr")
            appendLine("Uptime         : ${uptimeSeconds}s")
            appendLine("Crashing Thread: [${thread.id}] ${thread.name} (Priority: ${thread.priority})")
            appendLine("Exception Type : ${throwable.javaClass.name}")
            appendLine("Message        : ${throwable.message ?: "No error message"}")
            appendLine()
            appendLine("-------------------------------- DEVICE INFO -----------------------------------")
            appendLine("Brand / Model  : ${Build.BRAND} ${Build.MODEL} (${Build.MANUFACTURER})")
            appendLine("Device / Board : ${Build.DEVICE} / ${Build.BOARD}")
            appendLine("Hardware       : ${Build.HARDWARE}")
            appendLine("Android OS     : Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Build ID       : ${Build.DISPLAY}")
            appendLine("Supported ABIs : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("-------------------------------- MEMORY INFO -----------------------------------")
            appendLine("JVM Heap Used  : ${usedHeapMb} MB / ${maxHeapMb} MB")
            appendLine("Native Heap    : ${nativeHeapMb} MB")
            appendLine()
            appendLine("-------------------------------- STACK TRACE -----------------------------------")
            appendLine(stackTraceStr)
            appendLine()
            appendLine("----------------------------- ALL ACTIVE THREADS -------------------------------")
            try {
                Thread.getAllStackTraces().forEach { (t, stack) ->
                    appendLine("Thread [${t.id}] ${t.name} (State: ${t.state})")
                    stack.take(6).forEach { frame ->
                        appendLine("    at $frame")
                    }
                    if (stack.size > 6) appendLine("    ... ${stack.size - 6} more frames")
                }
            } catch (e: Exception) {
                appendLine("Unable to dump thread traces: ${e.message}")
            }
            appendLine("================================================================================")
        }
    }

    private fun saveCrashReport(report: String) {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val timestampFile = File(logDir, "crash_$timestamp.log")
        val latestFile = File(logDir, "latest_crash.log")

        timestampFile.writeText(report)
        latestFile.writeText(report)
    }

    companion object {
        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
