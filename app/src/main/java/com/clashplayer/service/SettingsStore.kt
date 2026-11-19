package com.clashplayer.service

import android.content.Context
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.DitherType
import com.clashplayer.dsp.ReplayGainMode
import com.clashplayer.dsp.model.EqualizerBand
import com.clashplayer.dsp.model.EqualizerPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Saves and restores every engine, player and per-device setting as one JSON document. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("clash_settings", Context.MODE_PRIVATE)

    /** Device key -> overrides. Read by the player whenever the output changes. */
    val profiles = ConcurrentHashMap<String, OutputProfile>()

    fun save(engine: AudioEngine, s: PlaybackSettings, driver: AudioDriverType) {
        prefs.edit().putString(KEY, encode(engine, s, driver).toString()).apply()
    }

    /** Restores saved state; returns the saved driver, or null on first run. */
    fun load(engine: AudioEngine, s: PlaybackSettings): AudioDriverType? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { decode(JSONObject(raw), engine, s) }.getOrNull()
    }

    internal fun encode(e: AudioEngine, s: PlaybackSettings, driver: AudioDriverType) = JSONObject().apply {
        put("driver", driver.id)
        put("player", JSONObject().also { p ->
            BOOLS.forEach { p.put(it.name, it.get(s)) }
            INTS.forEach { p.put(it.name, it.get(s)) }
            FLOATS.forEach { p.put(it.name, it.get(s).toDouble()) }
            p.put("replayGain", s.replayGain.name)
        })
        put("engine", JSONObject().apply {
            put("dvc", e.dvcEnabled); put("headroom", e.dvcHeadroomDb.toDouble()); put("volume", e.volume.toDouble())
            put("toneOn", e.toneEnabled); put("eqOn", e.equalizerEnabled); put("smooth", e.smoothGains)
            put("width", e.stereoProcessor.stereoWidth.toDouble()); put("balance", e.stereoProcessor.balance.toDouble())
            put("mono", e.stereoProcessor.forceMono)
            put("clarity", JSONArray(listOf(
                e.clarityProcessor.enabled,
                e.clarityProcessor.wetMix.toDouble(),
                e.clarityProcessor.stereoWidth.toDouble(),
                e.clarityProcessor.exciterAmount.toDouble(),
                e.clarityProcessor.currentPreset.index
            )))
            put("reverb", JSONArray(listOf(e.reverbProcessor.enabled, e.reverbProcessor.roomSize, e.reverbProcessor.damping, e.reverbProcessor.wetMix)))
            put("comp", JSONArray(listOf(e.compressor.enabled, e.compressor.thresholdDb, e.compressor.ratio, e.compressor.makeupDb)))
            put("limiter", e.limiterProcessor.enabled)
            put("dither", e.ditherer.type.name)
            put("tempo", e.tempoProcessor.tempo.toDouble())
            val eq = e.equalizerStateFlow.value
            put("eq", JSONObject().apply {
                put("name", eq.name); put("parametric", e.isParametricMode)
                put("preamp", e.preampDb.toDouble()); put("bass", e.bassDb.toDouble()); put("treble", e.trebleDb.toDouble())
                put("graphic", JSONArray(e.graphicBands.map { it.getGainDb().toDouble() }))
                put("bands", JSONArray(e.parametricBands.map {
                    JSONArray(listOf(it.type, it.frequency, it.q.toDouble(), it.gain.toDouble(), it.channels, it.enabled))
                }))
            })
        })
        put("profiles", JSONObject().also { o -> profiles.forEach { (k, v) -> o.put(k, v.toJson()) } })
    }

    internal fun decode(root: JSONObject, e: AudioEngine, s: PlaybackSettings): AudioDriverType {
        root.optJSONObject("player")?.let { p ->
            BOOLS.forEach { if (p.has(it.name)) it.set(s, p.getBoolean(it.name)) }
            INTS.forEach { if (p.has(it.name)) it.set(s, p.getInt(it.name)) }
            FLOATS.forEach { if (p.has(it.name)) it.set(s, p.getDouble(it.name).toFloat()) }
            p.optString("replayGain").let { n -> ReplayGainMode.entries.firstOrNull { it.name == n }?.let { s.replayGain = it } }
        }
        root.optJSONObject("engine")?.let { j ->
            e.dvcEnabled = j.optBoolean("dvc", e.dvcEnabled)
            e.dvcHeadroomDb = j.optDouble("headroom", e.dvcHeadroomDb.toDouble()).toFloat()
            e.volume = j.optDouble("volume", e.volume.toDouble()).toFloat()
            e.toneEnabled = j.optBoolean("toneOn", e.toneEnabled)
            e.smoothGains = j.optBoolean("smooth", e.smoothGains)
            e.stereoProcessor.stereoWidth = j.optDouble("width", 1.0).toFloat()
            e.stereoProcessor.balance = j.optDouble("balance", 0.0).toFloat()
            e.stereoProcessor.forceMono = j.optBoolean("mono")
            j.optJSONArray("clarity")?.let { c ->
                e.clarityProcessor.enabled = c.optBoolean(0, true)
                e.clarityProcessor.wetMix = c.optDouble(1, 1.0).toFloat()
                e.clarityProcessor.stereoWidth = c.optDouble(2, 1.25).toFloat()
                e.clarityProcessor.exciterAmount = c.optDouble(3, 0.18).toFloat()
                e.clarityProcessor.setPreset(c.optInt(4, 0))
            }
            j.optJSONArray("reverb")?.let { r ->
                e.reverbProcessor.enabled = r.getBoolean(0); e.reverbProcessor.roomSize = r.getDouble(1).toFloat()
                e.reverbProcessor.damping = r.getDouble(2).toFloat(); e.reverbProcessor.wetMix = r.getDouble(3).toFloat()
            }
            j.optJSONArray("comp")?.let { c ->
                e.compressor.enabled = c.getBoolean(0); e.compressor.thresholdDb = c.getDouble(1).toFloat()
                e.compressor.ratio = c.getDouble(2).toFloat(); e.compressor.makeupDb = c.getDouble(3).toFloat()
            }
            e.limiterProcessor.enabled = j.optBoolean("limiter", true)
            DitherType.entries.firstOrNull { it.name == j.optString("dither") }?.let { e.ditherer.type = it }
            e.tempoProcessor.tempo = j.optDouble("tempo", 1.0).toFloat()
            j.optJSONObject("eq")?.let { q ->
                val graphic = q.optJSONArray("graphic")
                val bands = q.optJSONArray("bands")
                e.applyPreset(EqualizerPreset(
                    name = q.optString("name", "Flat"),
                    isParametric = q.optBoolean("parametric"),
                    bandGains = (0 until (graphic?.length() ?: 0)).map { graphic!!.getDouble(it).toFloat() },
                    parametricBands = (0 until (bands?.length() ?: 0)).map { i ->
                        val b = bands!!.getJSONArray(i)
                        EqualizerBand(b.getInt(0), b.getInt(1), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), channels = b.getInt(4))
                            .also { it.enabled = b.getBoolean(5) }
                    },
                    bass = q.optDouble("bass", 0.0).toFloat(),
                    treble = q.optDouble("treble", 0.0).toFloat(),
                    preamp = q.optDouble("preamp", 0.0).toFloat(),
                ))
            }
            e.setEqualizerEnabled(j.optBoolean("eqOn", true))
        }
        profiles.clear()
        root.optJSONObject("profiles")?.let { o -> o.keys().forEach { k -> profiles[k] = OutputProfile.fromJson(o.getJSONObject(k)) } }
        return AudioDriverType.fromId(root.optString("driver", AudioDriverType.BIT_PERFECT_DIRECT.id))
    }

    private companion object {
        const val KEY = "state_v1"
        val BOOLS = listOf(PlaybackSettings::gapless, PlaybackSettings::fades, PlaybackSettings::preventClipping,
            PlaybackSettings::ignoreShortFocusLoss, PlaybackSettings::pauseInsteadOfDuck, PlaybackSettings::usbResample,
            PlaybackSettings::preferNativeDsd, PlaybackSettings::dsdRemaster, PlaybackSettings::remasterAllow48k,
            PlaybackSettings::noDvcOnBluetooth, PlaybackSettings::autoMixEnabled, PlaybackSettings::autoMixTempoMatch)
        val INTS = listOf(PlaybackSettings::crossfadeMs, PlaybackSettings::fadeMs, PlaybackSettings::seekFadeMs,
            PlaybackSettings::silenceMs, PlaybackSettings::usbWarmupMs, PlaybackSettings::remasterMultiple, PlaybackSettings::btCodecRate,
            PlaybackSettings::autoMixBars)
        val FLOATS = listOf(PlaybackSettings::replayGainPreampDb, PlaybackSettings::untaggedGainDb, PlaybackSettings::remasterGainDb)
    }
}
