package com.clashplayer.dsp.model

import java.util.concurrent.atomic.AtomicInteger

class EqualizerBand(
    type: Int = 0,
    frequency: Int = 1000,
    q: Float = 1.414f,
    gain: Float = 0.0f,
    var name: String = "",
    val isEqu: Boolean = true,
    channels: Int = 0,
    var color: Int = 0
) {
    var type: Int = type.coerceIn(0, 6)
        private set

    var frequency: Int = frequency.coerceIn(20, 24000)
        private set

    var q: Float = q.coerceIn(0.1f, 12.0f)
        private set

    var gain: Float = gain.coerceIn(if (isEqu) -1.0f else 0.0f, 1.0f)
        private set

    var channels: Int = channels.coerceIn(0, 2)
        private set

    var enabled: Boolean = true
    var locked: Boolean = false

    val serial: Int = idCounter.getAndIncrement()
    var dirtySerial: Int = serialCounter.getAndIncrement()
        private set

    fun getGainDb(): Float = gain * 15.0f

    fun setGainDb(db: Float): Boolean {
        return setNormalizedGain(db / 15.0f)
    }

    fun setNormalizedGain(normGain: Float): Boolean {
        val clamped = normGain.coerceIn(if (isEqu) -1.0f else 0.0f, 1.0f)
        if (gain == clamped) return false
        gain = clamped
        markDirty()
        return true
    }

    fun setFrequency(hz: Int): Boolean {
        val clamped = hz.coerceIn(20, 24000)
        if (frequency == clamped) return false
        frequency = clamped
        markDirty()
        return true
    }

    fun setQ(factor: Float): Boolean {
        val clamped = factor.coerceIn(0.1f, 12.0f)
        if (q == clamped) return false
        q = clamped
        markDirty()
        return true
    }

    fun setFilterType(filterType: Int): Boolean {
        val clamped = filterType.coerceIn(0, 6)
        if (type == clamped) return false
        type = clamped
        markDirty()
        return true
    }

    fun setChannelRouting(channel: Int): Boolean {
        val clamped = channel.coerceIn(0, 2)
        if (channels == clamped) return false
        channels = clamped
        markDirty()
        return true
    }

    private fun markDirty() {
        dirtySerial = serialCounter.getAndIncrement()
    }

    companion object {
        private val serialCounter = AtomicInteger(1)
        private val idCounter = AtomicInteger(1)

        val DEFAULT_10_BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}
