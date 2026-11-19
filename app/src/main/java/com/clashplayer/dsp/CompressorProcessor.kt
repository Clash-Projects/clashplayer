package com.clashplayer.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Stereo-linked feed-forward compressor with a soft knee; gain is computed in dB from the louder channel. */
class CompressorProcessor(private var sampleRate: Int) {
    @Volatile var enabled = false
    @Volatile var thresholdDb = -18f
    @Volatile var ratio = 3f
    @Volatile var kneeDb = 6f
    @Volatile var makeupDb = 0f
    var attackMs = 10f
        set(v) { field = v; coefs() }
    var releaseMs = 150f
        set(v) { field = v; coefs() }

    private var attack = 0f
    private var release = 0f
    private var envDb = 0f // smoothed gain reduction

    init { coefs() }

    fun updateSampleRate(rate: Int) { sampleRate = rate; coefs() }

    private fun coefs() {
        attack = exp(-1.0 / (attackMs * 0.001 * sampleRate)).toFloat()
        release = exp(-1.0 / (releaseMs * 0.001 * sampleRate)).toFloat()
    }

    /** Static curve: output level reduction in dB (<= 0) for an input level. */
    fun gainReductionDb(levelDb: Float): Float {
        val over = levelDb - thresholdDb
        val slope = 1f / ratio - 1f
        return when {
            2 * over <= -kneeDb -> 0f
            2 * over >= kneeDb -> slope * over
            else -> slope * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
        }
    }

    fun process(left: Float, right: Float, out: FloatArray) {
        val level = 20f * log10(max(max(abs(left), abs(right)), 1e-6f))
        val target = gainReductionDb(level)
        // Attack while reduction deepens, release while it recovers
        envDb = if (target < envDb) attack * envDb + (1 - attack) * target else release * envDb + (1 - release) * target
        val g = 10f.pow((envDb + makeupDb) / 20f)
        out[0] = left * g
        out[1] = right * g
    }

    fun reset() { envDb = 0f }
}
