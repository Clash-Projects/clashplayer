package com.clashplayer.automix

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class AnalysisSide { HEAD, TAIL }

class AutoMixAnalyzer(private val context: Context) {
    companion object {
        private const val TAG = "AutoMixAnalyzer"
    }

    private val memCache = ConcurrentHashMap<String, TrackAnalysis>()
    private val cacheDir = File(context.cacheDir, "automix").apply { mkdirs() }

    fun getCached(key: String): TrackAnalysis? = memCache[key]

    suspend fun analyze(
        key: String,
        side: AnalysisSide,
        pcm: ShortArray,
        sampleRate: Int,
        offsetSeconds: Double = 0.0
    ): TrackAnalysis? = withContext(Dispatchers.Default) {
        val cacheKey = "$key-${side.name}"
        memCache[cacheKey]?.let { return@withContext it }

        // Check disk cache
        loadFromDisk(cacheKey)?.let {
            memCache[cacheKey] = it
            return@withContext it
        }

        try {
            val analysis = AudioAnalyzer.analyzePcm(pcm, sampleRate, offsetSeconds)
            memCache[cacheKey] = analysis
            saveToDisk(cacheKey, analysis)
            analysis
        } catch (e: Exception) {
            Log.w(TAG, "Audio analysis failed for $cacheKey: ${e.message}")
            null
        }
    }

    private fun saveToDisk(key: String, a: TrackAnalysis) {
        try {
            val file = File(cacheDir, "${key.hashCode()}.json")
            val json = JSONObject().apply {
                put("ws", a.windowStart)
                put("we", a.windowEnd)
                put("bpm", a.bpm)
                put("conf", a.confidence)
                put("beats", JSONArray(a.beats))
                put("down", JSONArray(a.downbeats))
                put("as", a.audibleStart)
                put("ce", a.contentEnd)
                put("e", JSONArray(a.energyDb))
                put("eh", a.energyHop)
            }
            file.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun loadFromDisk(key: String): TrackAnalysis? {
        try {
            val file = File(cacheDir, "${key.hashCode()}.json")
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val beats = mutableListOf<Double>()
            val beatsArr = json.optJSONArray("beats")
            if (beatsArr != null) {
                for (i in 0 until beatsArr.length()) beats.add(beatsArr.getDouble(i))
            }
            val down = mutableListOf<Double>()
            val downArr = json.optJSONArray("down")
            if (downArr != null) {
                for (i in 0 until downArr.length()) down.add(downArr.getDouble(i))
            }
            val energy = mutableListOf<Double>()
            val eArr = json.optJSONArray("e")
            if (eArr != null) {
                for (i in 0 until eArr.length()) energy.add(eArr.getDouble(i))
            }
            return TrackAnalysis(
                windowStart = json.optDouble("ws", 0.0),
                windowEnd = json.optDouble("we", 0.0),
                bpm = json.optDouble("bpm", 0.0),
                confidence = json.optDouble("conf", 0.0),
                beats = beats,
                downbeats = down,
                audibleStart = json.optDouble("as", 0.0),
                contentEnd = json.optDouble("ce", 0.0),
                energyDb = energy,
                energyHop = json.optDouble("eh", 0.5)
            )
        } catch (_: Exception) {
            return null
        }
    }
}
