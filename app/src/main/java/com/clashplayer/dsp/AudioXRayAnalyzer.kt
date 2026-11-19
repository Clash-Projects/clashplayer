package com.clashplayer.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
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
        while (i < end) {
            val s = buffer[i]
            val absS = abs(s)
            if (absS > peakSample) peakSample = absS
            sumSamples += s
            sumSquares += s * s

            // Quantize to 24-bit signed integer word [-8388608, 8388607]
            val int24 = (s.coerceIn(-1.0f, 1.0f) * 8388607.0f).toInt()
            bitOrMask = bitOrMask or (abs(int24) and 0xFFFFFF)

            sampleCounter++
            if (sampleCounter >= analyzeWindow) {
                publishMetrics()
                sampleCounter = 0
                bitOrMask = 0
                sumSamples = 0.0
                sumSquares = 0.0
                peakSample = 0.0f
            }
            i++
        }
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
                lowestBit = minOf(lowestBit, b)
            }
        }

        // Check if the bottom 8 bits (bits 0..7) are completely dead (padded 16-bit file)
        val bottom8Dead = (mask and 0x0000FF) == 0
        val isPadded = bottom8Dead && highestBit >= 16
        val depth = if (isPadded) 16 else if (highestBit >= 20) 24 else if (highestBit >= 16) 16 else 16

        val dcOffset = (sumSamples / analyzeWindow.toDouble()).toFloat() * 100.0f
        val rms = sqrt(max(1e-12, sumSquares / analyzeWindow.toDouble())).toFloat()
        val crestFactor = if (rms > 1e-6f) (20.0 * log10((peakSample / rms).toDouble())).toFloat() else 0.0f
        val snr = depth * 6.02f + 1.76f

        _metrics.value = XRayMetrics(
            effectiveBitDepth = depth,
            isPadded16Bit = isPadded,
            activeBitsMask = mask,
            bitGrid = grid,
            dcOffsetPercent = dcOffset,
            crestFactorDb = crestFactor.coerceIn(0f, 40f),
            theoreticalSnrDb = snr
        )
    }

    fun reset() {
        sampleCounter = 0
        bitOrMask = 0
        sumSamples = 0.0
        sumSquares = 0.0
        peakSample = 0.0f
    }
}
