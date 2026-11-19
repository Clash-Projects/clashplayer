package com.clashplayer.automix

import android.util.Log
import java.nio.ByteBuffer

/**
 * Native C++ Spectral Deck Crossover for DJ AutoMix.
 * Executes phase-aligned 4th-order (24 dB/oct) Linkwitz-Riley crossover
 * and 1-bar sub-bass swap at midpoint (t = 0.5).
 */
class SpectralCrossoverAudioProcessor(sampleRate: Float = 44100f) {
    companion object {
        private const val TAG = "SpectralCrossover"
        val isNativeLoaded: Boolean = try {
            System.loadLibrary("nativeout")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Native automix library unavailable: ${e.message}")
            false
        }
    }

    init {
        if (isNativeLoaded) {
            try {
                nativeInit(sampleRate)
            } catch (e: Throwable) {
                Log.w(TAG, "nativeInit failed: ${e.message}")
            }
        }
    }

    fun reset() {
        if (isNativeLoaded) {
            try { nativeReset() } catch (_: Throwable) {}
        }
    }

    fun setTempoRatio(ratioAlpha: Float, ratioBeta: Float) {
        if (isNativeLoaded) {
            try { nativeSetTempoRatio(ratioAlpha, ratioBeta) } catch (_: Throwable) {}
        }
    }

    fun processStereo(
        alphaBuf: ByteBuffer,
        betaBuf: ByteBuffer,
        outBuf: ByteBuffer,
        numFrames: Int,
        progress: Float,
        applySpectralSweeps: Boolean = true
    ) {
        if (isNativeLoaded) {
            try {
                nativeProcessStereo(alphaBuf, betaBuf, outBuf, numFrames, progress, applySpectralSweeps)
            } catch (_: Throwable) {}
        }
    }

    fun estimateMomentaryLufs(pcmBuf: ByteBuffer, numFrames: Int): Float {
        return if (isNativeLoaded) {
            try {
                nativeEstimateLufs(pcmBuf, numFrames)
            } catch (_: Throwable) {
                -70f
            }
        } else {
            -70f
        }
    }

    private external fun nativeInit(sampleRate: Float)
    private external fun nativeReset()
    private external fun nativeSetTempoRatio(ratioAlpha: Float, ratioBeta: Float)
    private external fun nativeProcessStereo(
        alphaBuf: ByteBuffer?,
        betaBuf: ByteBuffer?,
        outBuf: ByteBuffer?,
        numFrames: Int,
        progress: Float,
        applySpectralSweeps: Boolean
    )
    private external fun nativeEstimateLufs(pcmBuf: ByteBuffer?, numFrames: Int): Float
}
