package com.clashplayer.diagnostics

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Debug
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsManager {

    private const val MAX_IN_MEMORY_LOGS = 600
    private const val MAX_LOG_FILE_SIZE_BYTES = 4 * 1024 * 1024 // 4 MB

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val memoryLogLock = Any()
    private val memoryLogs = ArrayDeque<LogEntry>(MAX_IN_MEMORY_LOGS)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private var logFile: File? = null
    private var isInitialized = false

    var activeDriverName: String = "Auto"
    var activeSampleRate: Int = 44100
    var isDvcActive: Boolean = false
    var underrunCount: Long = 0L

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        CrashHandler.install(context)

        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        logFile = File(logDir, "clash_runtime.log")

        pruneOldLogs(logDir)

        i("Diagnostics", "DiagnosticsManager initialized")
        logAudioHardware(context)
    }

    fun d(tag: String, message: String) = addLog(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = addLog(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, tr: Throwable? = null) = addLog(LogLevel.WARN, tag, message, tr)
    fun e(tag: String, message: String, tr: Throwable? = null) = addLog(LogLevel.ERROR, tag, message, tr)
    fun audio(tag: String, message: String) = addLog(LogLevel.AUDIO, tag, message)

    private fun addLog(level: LogLevel, tag: String, message: String, tr: Throwable? = null) {
        val stackTrace = tr?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwableStackTrace = stackTrace
        )

        synchronized(memoryLogLock) {
            if (memoryLogs.size >= MAX_IN_MEMORY_LOGS) {
                memoryLogs.removeFirst()
            }
            memoryLogs.addLast(entry)
            _logsFlow.value = memoryLogs.toList()
        }

        scope.launch {
            writeToFile(entry)
        }
    }

    private fun writeToFile(entry: LogEntry) {
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() > MAX_LOG_FILE_SIZE_BYTES) {
                val rolledFile = File(file.parentFile, "clash_runtime_old.log")
                if (rolledFile.exists()) rolledFile.delete()
                file.renameTo(rolledFile)
            }
            file.appendText(entry.toFormattedString() + "\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recordCrash(report: String) {
        addLog(LogLevel.CRASH, "FATAL", "Application encountered an unhandled exception")
    }

    fun hasCrashReports(context: Context): Boolean {
        val latest = File(File(context.filesDir, "logs"), "latest_crash.log")
        return latest.exists() && latest.length() > 0
    }

    fun getLatestCrashReport(context: Context): String? {
        val latest = File(File(context.filesDir, "logs"), "latest_crash.log")
        return if (latest.exists()) {
            try {
                latest.readText()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    private fun pruneOldLogs(logDir: File) {
        try {
            val files = logDir.listFiles() ?: return
            val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
            for (f in files) {
                if (f.lastModified() < sevenDaysAgo && f.name.startsWith("crash_")) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun logAudioHardware(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val nativeSampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val nativeFramesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)

        audio("Hardware", "Platform Native Rate: ${nativeSampleRate}Hz, Native Frames/Buffer: $nativeFramesPerBuffer")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val devNames = devices.joinToString(", ") { dev ->
                val typeName = when (dev.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC"
                    else -> "Type(${dev.type})"
                }
                "$typeName (${dev.productName})"
            }
            audio("Devices", "Connected Audio Devices: $devNames")
        }
    }

    fun generateSystemReport(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Unknown"
        val nativeFrames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Unknown"

        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        return buildString {
            appendLine("================================================================================")
            appendLine("                          CLASHPLAYER DIAGNOSTIC REPORT                         ")
            appendLine("================================================================================")
            appendLine("Generated At       : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App Version        : 1.0.0-release")
            appendLine("Device             : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("Hardware Board     : ${Build.HARDWARE} / ${Build.BOARD}")
            appendLine("Android OS         : Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Build ID           : ${Build.DISPLAY}")
            appendLine("Supported ABIs     : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("------------------------------ AUDIO ENGINE STATE ------------------------------")
            appendLine("Active Driver      : $activeDriverName")
            appendLine("Active Sample Rate : ${activeSampleRate} Hz")
            appendLine("Platform Native SR : ${nativeRate} Hz")
            appendLine("Frames Per Buffer  : $nativeFrames")
            appendLine("DVC Headroom       : ${if (isDvcActive) "ENABLED (+12dB)" else "DISABLED"}")
            appendLine("Total Underruns    : $underrunCount")
            appendLine()
            appendLine("--------------------------------- MEMORY STATS ---------------------------------")
            appendLine("Heap Allocated     : $usedHeapMb MB")
            appendLine("Heap Max Limit     : $maxHeapMb MB")
            appendLine("Native Heap        : $nativeHeapMb MB")
            appendLine("================================================================================")
        }
    }

    fun exportDiagnosticsToFile(context: Context): File {
        val cacheLogsDir = File(context.cacheDir, "logs")
        if (!cacheLogsDir.exists()) cacheLogsDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(cacheLogsDir, "clash_diagnostics_$timestamp.log")

        exportFile.bufferedWriter().use { writer ->
            writer.write(generateSystemReport(context))
            writer.write("\n\n")

            val latestCrash = getLatestCrashReport(context)
            if (!latestCrash.isNullOrBlank()) {
                writer.write("---------------------------- LAST RECORDED CRASH -------------------------------\n")
                writer.write(latestCrash)
                writer.write("\n\n")
            }

            writer.write("----------------------------- RECENT RUNTIME LOGS ------------------------------\n")
            val currentLogs = synchronized(memoryLogLock) { memoryLogs.toList() }
            for (entry in currentLogs) {
                writer.write(entry.toFormattedString())
                writer.write("\n")
            }
        }

        return exportFile
    }

    fun shareDiagnostics(context: Context) {
        val file = exportDiagnosticsToFile(context)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ClashPlayer Diagnostic Log - ${file.name}")
            putExtra(Intent.EXTRA_TEXT, "Attached is the ClashPlayer crash and diagnostic log report.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Export ClashPlayer Diagnostics").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun clearLogs(context: Context) {
        synchronized(memoryLogLock) {
            memoryLogs.clear()
            _logsFlow.value = emptyList()
        }

        scope.launch {
            try {
                logFile?.writeText("")
                val latestCrash = File(File(context.filesDir, "logs"), "latest_crash.log")
                if (latestCrash.exists()) latestCrash.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        i("Diagnostics", "Logs cleared by user")
    }
}
