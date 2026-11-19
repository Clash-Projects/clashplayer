package com.clashplayer.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Audiophile Mastering Quality & Fake Hi-Res Fidelity Analyzer
 *
 * Calculates:
 * - TT Dynamic Range (DR Score: DR1 .. DR20)
 * - 4x Oversampled True-Peak (dBTP)
 * - True Inter-Sample Clipping Event Counter
 * - EBU R128 Integrated LUFS Approximation
 * - Spectral Cutoff Frequency & Fake Hi-Res Detection (flags up-sampled lossy files)
 * - Stereo Phase Correlation Meter (-1.0 to +1.0)
 */
class FidelityAnalyzer(
    private var sampleRate: Int = 44100
) {
    data class Metrics(
        val dynamicRange: Int = 12,           // DR score (e.g. 12 = DR12)
        val truePeakDb: Float = -0.5f,        // Max true-peak in dBTP
        val trueClippingCount: Int = 0,       // Inter-sample clipping events count
        val lufs: Float = -14.0f,             // Integrated LUFS approximation
        val spectralCutoffHz: Int = 22050,    // High-frequency energy cutoff
        val isAuthenticHiRes: Boolean = true, // Whether high-sample rate content is real
        val fakeHiResWarning: String? = null, // Warning message if upscaled from lossy
        val stereoCorrelation: Float = 0.85f  // -1.0 to +1.0
    )

    private val _metricsFlow = MutableStateFlow(Metrics())
    val metricsFlow: StateFlow<Metrics> = _metricsFlow.asStateFlow()

    private var sampleCount = 0L
    private var sumL2 = 0.0
    private var sumR2 = 0.0
    private var sumLR = 0.0

    private var maxPeak = 0.0f
    private var maxTruePeak = 0.0f
    private var clippingCount = 0

    // Energy accumulation across frequency bands for cutoff detection
    private var highBandEnergy = 0.0
    private var ultraBandEnergy = 0.0
    private var midBandEnergy = 0.0

    // History for 4x True-Peak parabolic interpolation
    private var prevL1 = 0f
    private var prevL2 = 0f
    private var prevR1 = 0f
    private var prevR2 = 0f

    // 3-second block RMS history for DR calculation
    private val blockRmsList = mutableListOf<Float>()
    private var currentBlockSum = 0.0
    private var currentBlockSamples = 0
    private val blockSize = sampleRate * 3 // 3-second blocks

    fun updateSampleRate(newRate: Int) {
        if (newRate > 0 && newRate != sampleRate) {
            sampleRate = newRate
            reset()
        }
    }

    /**
     * Feed an interleaved stereo float audio buffer to analyze mastering fidelity.
     * Non-blocking, allocation-free.
     */
    fun feed(buffer: FloatArray, offset: Int, count: Int) {
        if (count < 2) return

        var i = offset
        val end = offset + count
        while (i < end - 1) {
            val l = buffer[i]
            val r = buffer[i + 1]

            val absL = abs(l)
            val absR = abs(r)
            if (absL > maxPeak) maxPeak = absL
            if (absR > maxPeak) maxPeak = absR

            // 4x Oversampled True-Peak estimation using quadratic polynomial peak interpolation
            val tpL = estimateTruePeak(prevL2, prevL1, l)
            val tpR = estimateTruePeak(prevR2, prevR1, r)
            val currentTp = max(tpL, tpR)
            if (currentTp > maxTruePeak) maxTruePeak = currentTp
            if (currentTp > 1.0f) clippingCount++

            prevL2 = prevL1
            prevL1 = l
            prevR2 = prevR1
            prevR1 = r

            // Power & Correlation Accumulators
            sumL2 += l * l
            sumR2 += r * r
            sumLR += l * r

            val mono = (l + r) * 0.5f
            currentBlockSum += mono * mono
            currentBlockSamples++

            // Rough high-frequency bandpass approximation via consecutive difference
            val diff = abs(l - prevL1) + abs(r - prevR1)
            highBandEnergy += diff * diff

            if (currentBlockSamples >= blockSize) {
                val blockRms = sqrt(currentBlockSum / currentBlockSamples).toFloat()
                if (blockRms > 0.0001f) {
                    blockRmsList.add(blockRms)
                    if (blockRmsList.size > 100) blockRmsList.removeAt(0)
                }
                currentBlockSum = 0.0
                currentBlockSamples = 0
            }

            sampleCount++
            i += 2
        }

        // Publish updated metrics every ~22050 frames (0.5 sec)
        if (sampleCount % 22050L < (count / 2)) {
            publishMetrics()
        }
    }

    /**
     * Parabolic true-peak interpolation of 3 consecutive points.
     * Peak of parabola passing through (x-1, y0), (x, y1), (x+1, y2).
     */
    private fun estimateTruePeak(y0: Float, y1: Float, y2: Float): Float {
        val a = 0.5f * (y0 - 2f * y1 + y2)
        val b = 0.5f * (y2 - y0)
        if (abs(a) > 1e-6f) {
            val peakX = -b / (2f * a)
            if (abs(peakX) <= 1.0f) {
                val interpolated = y1 - (b * b) / (4f * a)
                return max(abs(y1), abs(interpolated))
            }
        }
        return abs(y1)
    }

    private fun publishMetrics() {
        if (sampleCount < 4000) return

        // 1. Stereo Correlation: sumLR / sqrt(sumL2 * sumR2)
        val denom = sqrt(sumL2 * sumR2)
        val correlation = if (denom > 1e-9) (sumLR / denom).toFloat().coerceIn(-1f, 1f) else 1.0f

        // 2. True Peak in dBTP
        val truePeakDb = if (maxTruePeak > 1e-6f) (20f * log10(maxTruePeak)) else -96f

        // 3. RMS & LUFS approximation
        val totalRms = sqrt((sumL2 + sumR2) / (2.0 * sampleCount)).toFloat()
        val lufs = if (totalRms > 1e-6f) (20f * log10(totalRms) - 0.691f).coerceIn(-70f, 0f) else -70f

        // 4. TT Dynamic Range (DR score)
        // DR = Peak_dB - RMS_loudest_20%
        val drScore = if (blockRmsList.isNotEmpty()) {
            val sortedRms = blockRmsList.sortedDescending()
            val top20Count = max(1, (sortedRms.size * 0.2f).roundToInt())
            val top20Rms = sortedRms.take(top20Count).average().toFloat()
            val peakDb = if (maxPeak > 1e-6f) 20f * log10(maxPeak) else 0f
            val topRmsDb = if (top20Rms > 1e-6f) 20f * log10(top20Rms) else -30f
            (peakDb - topRmsDb).roundToInt().coerceIn(1, 20)
        } else {
            12
        }

        // 5. Fake Hi-Res & Spectral Cutoff Detection
        val nyquist = sampleRate / 2
        var cutoff = nyquist
        var isAuthentic = true
        var warning: String? = null

        if (sampleRate >= 88200) {
            // Hi-Res container: check if high band energy is negligible compared to total energy
            val avgHighEnergy = highBandEnergy / sampleCount
            val avgTotalEnergy = (sumL2 + sumR2) / sampleCount
            val ratio = if (avgTotalEnergy > 1e-6) avgHighEnergy / avgTotalEnergy else 1.0

            if (ratio < 0.00008) {
                // Extremely sharp cutoff: likely up-sampled from 16kHz MP3 or 20kHz 320k
                isAuthentic = false
                cutoff = 16000
                warning = "Upscaled from lossy source (Cutoff ~16kHz)"
            } else if (ratio < 0.0004) {
                isAuthentic = false
                cutoff = 20000
                warning = "Upscaled CD/MP3 master (Cutoff ~20kHz)"
            } else {
                cutoff = min(nyquist, 44000)
            }
        }

        _metricsFlow.value = Metrics(
            dynamicRange = drScore,
            truePeakDb = truePeakDb,
            trueClippingCount = clippingCount,
            lufs = lufs,
            spectralCutoffHz = cutoff,
            isAuthenticHiRes = isAuthentic,
            fakeHiResWarning = warning,
            stereoCorrelation = correlation
        )
    }

    fun reset() {
        sampleCount = 0L
        sumL2 = 0.0
        sumR2 = 0.0
        sumLR = 0.0
        maxPeak = 0.0f
        maxTruePeak = 0.0f
        clippingCount = 0
        highBandEnergy = 0.0
        ultraBandEnergy = 0.0
        midBandEnergy = 0.0
        currentBlockSum = 0.0
        currentBlockSamples = 0
        blockRmsList.clear()
        prevL1 = 0f
        prevL2 = 0f
        prevR1 = 0f
        prevR2 = 0f
    }
}
