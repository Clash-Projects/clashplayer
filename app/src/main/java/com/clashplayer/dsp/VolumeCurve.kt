package com.clashplayer.dsp

/**
 * Piecewise-linear volume curves as {index on a 1..100 scale, dB} points, from AOSP's default
 * audio policy volume tables. Index 0 is mute.
 */
enum class VolumeCurve(private val points: Array<IntArray>) {
    MEDIA(arrayOf(intArrayOf(1, -58), intArrayOf(20, -40), intArrayOf(60, -17), intArrayOf(100, 0))),
    SPEAKER(arrayOf(intArrayOf(1, -56), intArrayOf(20, -34), intArrayOf(60, -11), intArrayOf(100, 0))),
    LINEAR_DB(arrayOf(intArrayOf(1, -58), intArrayOf(100, 0)));

    /** dB for [step] of [steps], or null for mute. */
    fun stepToDb(step: Int, steps: Int): Float? {
        if (steps <= 0) return null
        val first = points.first()[0]
        val last = points.last()[0]
        var idx = (last - first + 1) * step / steps
        if (steps > last - first + 1 && step == 1) idx = 1 // keep the lowest step audible when steps > 100
        if (idx < first) return null
        if (idx >= last) return points.last()[1].toFloat()
        val seg = points.indices.first { idx < points[it + 1][0] }
        val (i0, db0) = points[seg].let { it[0] to it[1] }
        val (i1, db1) = points[seg + 1].let { it[0] to it[1] }
        return db0 + (db1 - db0) * (idx - i0).toFloat() / (i1 - i0)
    }
}
