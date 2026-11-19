package com.clashplayer.dsp.model

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Headphone correction profiles from the AutoEq project (github.com/jaakkopasanen/AutoEq, MIT).
 * The index and each profile are fetched once and cached in [cacheDir]. Calls block; run them off the main thread.
 */
class AutoEqRepository(private val cacheDir: File) {

    data class Entry(val name: String, val path: String, val source: String, val rig: String?)

    @Volatile private var index: List<Entry>? = null

    fun loadIndex(): List<Entry> {
        index?.let { return it }
        val text = cachedFetch(File(cacheDir, "autoeq/INDEX.md"), "$BASE/INDEX.md")
        return parseIndex(text).also { index = it }
    }

    fun search(query: String, limit: Int = 200): List<Entry> {
        val all = loadIndex()
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all.asSequence().filter { e -> words.all { e.name.contains(it, ignoreCase = true) } }.take(limit).toList()
    }

    fun loadPreset(entry: Entry): EqualizerPreset {
        val fileName = entry.path.substringAfterLast('/') + "%20ParametricEQ.txt"
        val local = File(cacheDir, "autoeq/" + URLDecoder.decode(entry.path, "UTF-8") + "/ParametricEQ.txt")
        val text = cachedFetch(local, "$BASE/${entry.path}/$fileName")
        val preset = AutoEqParser.parseParametric("${entry.name} (${entry.source})", text)
        require(preset.parametricBands.isNotEmpty()) { "No filters in AutoEq profile for ${entry.name}" }
        return preset
    }

    private fun cachedFetch(file: File, url: String): String {
        if (file.isFile && file.length() > 0) return file.readText()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
            val text = conn.inputStream.bufferedReader().readText()
            file.parentFile?.mkdirs()
            // Write then rename, so an interrupted download never leaves a truncated cache file
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        private val LINE = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?$""")

        fun parseIndex(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
            LINE.matchEntire(line.trim())?.destructured?.let { (name, path, source, rig) ->
                Entry(name, path, source, rig.ifEmpty { null })
            }
        }.toList()
    }
}
