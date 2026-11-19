package com.clashplayer.automix

import kotlin.math.*

enum class DjMixStyle {
    BEATMATCH,
    FILTER
}

data class DjMixPlan(
    val mixOutStart: Double,
    val blendSec: Double,
    val incomingCue: Double,
    val incomingStartAt: Double,
    val rate: Double,
    val bars: Int,
    val style: DjMixStyle,
    val reason: String
) {
    val beatmatched: Boolean get() = style == DjMixStyle.BEATMATCH
    val mixOutEnd: Double get() = mixOutStart + blendSec
    val triggerAt: Double get() = mixOutStart - (incomingCue - incomingStartAt) / rate

    fun expectedIncoming(outPos: Double): Double =
        incomingCue + (outPos - mixOutStart) * rate
}

object DjMixPlanner {
    const val MAX_SLOW_DOWN = 0.08
    const val MAX_SPEED_UP = 0.08
    const val BEATMATCH_CONFIDENCE = 0.25
    const val MAX_BLEND_SEC = 24.0
    const val MAX_SHARE_OF_SONG = 0.20
    const val LONG_BLEND_CONFIDENCE = 0.40
    const val PREROLL = 2.0

    fun plan(
        outgoingTail: TrackAnalysis,
        incomingHead: TrackAnalysis,
        outgoingDuration: Double,
        incomingDuration: Double,
        bars: Int = 8,
        allowTempo: Boolean = true,
        earliestStart: Double? = null
    ): DjMixPlan? {
        val requestedBars = if (bars >= 16) 16 else 8
        val candidates = if (requestedBars == 16) listOf(16, 8) else listOf(8)
        for (b in candidates) {
            val p = planInternal(
                out = outgoingTail,
                inc = incomingHead,
                outDur = outgoingDuration,
                inDur = incomingDuration,
                bars = b,
                allowTempo = allowTempo,
                earliestStart = earliestStart
            )
            if (p != null && isSafePlan(p, outgoingDuration)) {
                return p
            }
        }
        return null
    }

    fun isSafePlan(p: DjMixPlan, outgoingDuration: Double): Boolean {
        if (!p.mixOutStart.isFinite() || !p.blendSec.isFinite() ||
            !p.incomingCue.isFinite() || !p.incomingStartAt.isFinite() || !p.rate.isFinite()
        ) return false

        if (p.blendSec < 2.0 || p.blendSec > 60.0) return false
        if (!rateAllowed(p.rate)) return false
        if (!p.beatmatched && p.rate != 1.0) return false
        if (p.mixOutStart < 0.0 || p.triggerAt < 0.0) return false
        if (p.mixOutEnd > outgoingDuration + 0.05) return false
        if (p.incomingCue < 0.0 || p.incomingStartAt < 0.0) return false
        if (p.incomingStartAt > p.incomingCue + 1e-6) return false
        return true
    }

    private fun rateAllowed(rate: Double): Boolean {
        return rate in (1.0 - MAX_SLOW_DOWN)..(1.0 + MAX_SPEED_UP)
    }

    private fun planInternal(
        out: TrackAnalysis,
        inc: TrackAnalysis,
        outDur: Double,
        inDur: Double,
        bars: Int,
        allowTempo: Boolean,
        earliestStart: Double?
    ): DjMixPlan? {
        if (!out.hasBeat || out.downbeats.isEmpty()) return null

        val canBeatmatch = allowTempo && inc.hasBeat && inc.downbeats.isNotEmpty() &&
            out.confidence >= BEATMATCH_CONFIDENCE && inc.confidence >= BEATMATCH_CONFIDENCE

        var rate = 1.0
        var style = DjMixStyle.FILTER
        var reason = "filter crossfade"

        if (canBeatmatch) {
            val rawRatio = out.bpm / inc.bpm
            // Check 1:1, 1:2 (half time), 2:1 (double time)
            val directMatch = rawRatio in (1.0 - MAX_SLOW_DOWN)..(1.0 + MAX_SPEED_UP)
            val halfMatch = (rawRatio * 2.0) in (1.0 - MAX_SLOW_DOWN)..(1.0 + MAX_SPEED_UP)
            val doubleMatch = (rawRatio * 0.5) in (1.0 - MAX_SLOW_DOWN)..(1.0 + MAX_SPEED_UP)

            if (directMatch) {
                rate = rawRatio
                style = DjMixStyle.BEATMATCH
                reason = "beatmatched 1:1 (${round(out.bpm)} -> ${round(inc.bpm)} BPM)"
            } else if (halfMatch) {
                rate = rawRatio * 2.0
                style = DjMixStyle.BEATMATCH
                reason = "beatmatched 1:2 half-time"
            } else if (doubleMatch) {
                rate = rawRatio * 0.5
                style = DjMixStyle.BEATMATCH
                reason = "beatmatched 2:1 double-time"
            }
        }

        // Target blend duration based on bars and tempo
        val beatPeriod = out.beatPeriod
        val barSec = beatPeriod * 4.0
        var blendSec = barSec * bars
        blendSec = min(blendSec, MAX_BLEND_SEC)
        blendSec = min(blendSec, outDur * MAX_SHARE_OF_SONG)

        // Find ideal outgoing downbeat for transition
        val targetEnd = out.contentEnd.coerceAtMost(outDur)
        val targetStart = targetEnd - blendSec
        val downbeats = out.downbeats
        val chosenOutStart = downbeats.minByOrNull { abs(it - targetStart) } ?: return null

        if (earliestStart != null && chosenOutStart < earliestStart) return null

        val finalBlendSec = targetEnd - chosenOutStart
        if (finalBlendSec < 2.0) return null

        // Incoming cue point on first audible downbeat
        val incCue = inc.downbeats.firstOrNull { it >= inc.audibleStart - 0.1 }
            ?: inc.audibleStart
        val incStartAt = max(0.0, incCue - PREROLL)

        return DjMixPlan(
            mixOutStart = chosenOutStart,
            blendSec = finalBlendSec,
            incomingCue = incCue,
            incomingStartAt = incStartAt,
            rate = rate,
            bars = bars,
            style = style,
            reason = reason
        )
    }
}
