package com.clashplayer.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Audio X-Ray PCM Bit Depth & Active Bits Integrity Analyzer (reverse-engineered from Resonada).
 *
 * Inspects raw PCM sample words in real-time to determine:
 * - Real active bit depth (e.g. true 24-bit vs padded 16-bit where bottom 8 bits are zero)
 * - 24-bit activity mask across all 24 bit positions (Bit 0 LSB to Bit 23 MSB)
 * - Direct current (DC) offset percentage & drift
 * - Dynamic crest factor (Peak-to-RMS ratio in dB)
 */
class AudioXRayAnalyzer {

    data class XRayMetrics(
        val effectiveBitDepth: Int = 16,
        val isPadded16Bit: Boolean = false,
        val activeBitsMask: Int = 0x00FFFF00,
        val bitGrid: BooleanArray = BooleanArray(24) { it in 8..23 },
        val dcOffsetPercent: Float = 0.001f,
        val crestFactorDb: Float = 14.2f,
        val theoreticalSnrDb: Float = 96.0f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as XRayMetrics
            return effectiveBitDepth == other.effectiveBitDepth &&
                    isPadded16Bit == other.isPadded16Bit &&
                    activeBitsMask == other.activeBitsMask &&
                    bitGrid.contentEquals(other.bitGrid)
        }

        override fun hashCode(): Int {
            var result = effectiveBitDepth
            result = 31 * result + isPadded16Bit.hashCode()
            result = 31 * result + activeBitsMask
            result = 31 * result + bitGrid.contentHashCode()
            return result
        }
    }

    private val _metrics = MutableStateFlow(XRayMetrics())
    val metrics: StateFlow<XRayMetrics> = _metrics.asStateFlow()

    private var sampleCounter = 0
    private var bitOrMask = 0
    private var sumSamples = 0.0
    private var sumSquares = 0.0
    private var peakSample = 0.0f
    private val analyzeWindow = 32768

    fun feed(buffer: FloatArray, offset: Int, count: Int) {
        if (count <= 0) return

        var i = offset
        val end = offset + count
        var peak = peakSample
        var sumS = sumSamples
        var sumSq = sumSquares
        var mask = bitOrMask
        var counter = sampleCounter

        while (i < end) {
            val s = buffer[i]
            val absS = abs(s)
            if (absS > peak) peak = absS
            sumS += s
            sumSq += s * s

            // Quantize to 24-bit signed integer word [-8388608, 8388607]
            val int24 = (s * 8388608.0f).toInt().coerceIn(-8388608, 8388607)
            mask = mask or (int24 and 0xFFFFFF)

            counter++
            if (counter >= analyzeWindow) {
                peakSample = peak
                sumSamples = sumS
                sumSquares = sumSq
                bitOrMask = mask
                sampleCounter = counter
                publishMetrics()
                counter = 0
                mask = 0
                sumS = 0.0
                sumSq = 0.0
                peak = 0.0f
            }
            i++
        }

        peakSample = peak
        sumSamples = sumS
        sumSquares = sumSq
        bitOrMask = mask
        sampleCounter = counter
    }

    private fun publishMetrics() {
        val mask = bitOrMask
        val grid = BooleanArray(24) { bit ->
            ((mask shr bit) and 1) == 1
        }

        // Count how many bits are active from MSB downward
        var highestBit = 0
        var lowestBit = 23
        for (b in 23 downTo 0) {
            if (grid[b]) {
                highestBit = max(highestBit, b)
                lowestBit = min(lowestBit, b)
            }
        }

        // Check if the bottom 8 bits (bits 0..7) are completely dead (padded 16-bit file)
        val bottom8Dead = (mask and 0x0000FF) == 0
        val isPadded = (mask != 0) && bottom8Dead && highestBit >= 16
        val depth = if (isPadded) 16 else if (!bottom8Dead && mask != 0) 24 else 16

        val n = max(1, sampleCounter)
        val dcOffset = (sumSamples / n.toDouble()).toFloat() * 100.0f
        val rms = sqrt(max(1e-12, sumSquares / n.toDouble())).toFloat()
        val crestFactor = if (rms > 1e-6f) (20.0 * log10((peakSample / rms).toDouble())).toFloat() else 0.0f
        val snr = depth * 6.02f + 1.76f

        val newMetrics = XRayMetrics(
            effectiveBitDepth = depth,
            isPadded16Bit = isPadded,
            activeBitsMask = mask,
            bitGrid = grid,
            dcOffsetPercent = dcOffset,
            crestFactorDb = crestFactor.coerceIn(0f, 40f),
            theoreticalSnrDb = snr
        )
        if (_metrics.value != newMetrics) {
            _metrics.value = newMetrics
        }
    }

    fun reset() {
        sampleCounter = 0
        bitOrMask = 0
        sumSamples = 0.0
        sumSquares = 0.0
        peakSample = 0.0f
    }
}
