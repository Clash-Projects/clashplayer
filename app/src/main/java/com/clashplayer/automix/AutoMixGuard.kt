package com.clashplayer.automix

import kotlin.math.abs

enum class MixRepair {
    RESTORE_PRIMARY_VOLUME,
    SILENCE_STANDBY,
    RESTORE_RATE,
    ABORT_STUCK_MIX
}

data class DeckReading(
    val volume: Float,
    val playing: Boolean,
    val rate: Float,
    val positionMs: Long = 0L
)

data class MixGuardInput(
    val mixing: Boolean,
    val armed: Boolean,
    val ramping: Boolean,
    val fullVolume: Float,
    val expectedRate: Float,
    val primary: DeckReading,
    val standby: DeckReading?,
    val mixAgeMs: Long = 0L,
    val maxMixAgeMs: Long = 60000L,
    val resolving: Boolean = false
)

object AutoMixGuard {
    fun checkMix(input: MixGuardInput): List<MixRepair> {
        val repairs = mutableListOf<MixRepair>()
        if (input.mixing) {
            if (input.mixAgeMs > input.maxMixAgeMs) {
                repairs.add(MixRepair.ABORT_STUCK_MIX)
            }
            return repairs
        }

        if (input.resolving) return repairs

        val sb = input.standby
        if (sb != null) {
            val audible = sb.volume > 0.005f
            if (audible || (!input.armed && sb.playing)) {
                repairs.add(MixRepair.SILENCE_STANDBY)
            }
        }

        if (abs(input.primary.volume - input.fullVolume) > 0.01f) {
            repairs.add(MixRepair.RESTORE_PRIMARY_VOLUME)
        }

        if (!input.ramping && abs(input.primary.rate - input.expectedRate) > 0.002f) {
            repairs.add(MixRepair.RESTORE_RATE)
        }

        return repairs
    }
}
