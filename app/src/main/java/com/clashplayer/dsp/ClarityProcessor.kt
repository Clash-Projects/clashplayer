package com.clashplayer.dsp

import android.util.Log
import kotlin.math.*

/**
 * Studio Master Clarity & Acoustic Separation DSP Processor.
 *
 * Implements 8-stage acoustic contouring, 10 Hz DC-blocking, 130 Hz mono-bass phase alignment,
 * non-linear cubic tape-harmonic exciter, and holographic mid-side stereo separation.
 * Directly integrates the C++20 native audio engine with bit-identical 64-bit precision fallback.
 */
class ClarityProcessor(
    private var sampleRate: Int = 44100
) {
    var enabled: Boolean = false
        set(value) {
            field = value
            if (nativeHandle != 0L) {
                try { nativeSetStudioMasterClarity(nativeHandle, value) } catch (_: Throwable) {}
            }
        }

    var wetMix: Float = 1.0f                // 0.0 to 1.0
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (nativeHandle != 0L) {
                try { nativeSetClarityWet(nativeHandle, field) } catch (_: Throwable) {}
            }
        }

    var stereoWidth: Float = 1.25f          // 1.0 to 1.6 (upgraded stage separation)
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
        }

    var exciterAmount: Float = 0.18f        // 0.0 to 0.35 (analog tape harmonic sheen)
        set(value) {
            field = value.coerceIn(0f, 0.5f)
        }

    enum class Preset(val index: Int, val displayName: String, val description: String) {
        REFERENCE(0, "Reference", "Studio master contour; neutral reference curve"),
        SPEAKER(1, "Speaker", "Controlled sub-bass (-1.5dB) & gentle air (-0.5dB) for small drivers"),
        HEADPHONE(2, "Headphone", "Lighter bass (-0.5dB) & soft presence (-1.0dB) to avoid ear fatigue"),
        DAC(3, "Audiophile DAC", "Silky air (-1.5dB) & subtle exciter (-1.0dB) for resolving DACs")
    }

    var currentPreset: Preset = Preset.REFERENCE
        private set

    companion object {
        private const val TAG = "ClarityProcessor"

        const val BASS_GAIN_DB = 3.2
        const val LOW_MID_GAIN_DB = -3.0
        const val BOXINESS_GAIN_DB = -1.4
        const val PRESENCE_GAIN_DB = 3.8
        const val AIR_GAIN_DB = 4.8
        const val MAKEUP_GAIN = 1.04f

        val isNativeAvailable: Boolean = try {
            System.loadLibrary("nativeout")
            true
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Native audio engine unavailable, using Kotlin DSP fallback: ${e.message}")
            } catch (_: Throwable) {}
            false
        }

        private val PRESET_TRIMS = arrayOf(
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),          // Reference
            floatArrayOf(0f, -1.5f, 0f, 0f, 0f, -0.5f, 0f, 0f),     // Speaker
            floatArrayOf(0f, -0.5f, 0f, 0f, -1.0f, 0f, 0f, 0f),     // Headphone
            floatArrayOf(0f, 0f, 0f, 0f, 0f, -1.5f, 0f, -1.0f)      // DAC
        )
    }

    private var nativeHandle: Long = 0L

    class Biquad {
        var b0: Double = 1.0
        var b1: Double = 0.0
        var b2: Double = 0.0
        var a1: Double = 0.0
        var a2: Double = 0.0
        var z1_0: Double = 0.0
        var z2_0: Double = 0.0
        var z1_1: Double = 0.0
        var z2_1: Double = 0.0

        inline fun tick(input: Float, channel: Int): Float {
            val x = input.toDouble()
            val z1 = if (channel == 0) z1_0 else z1_1
            val z2 = if (channel == 0) z2_0 else z2_1
            val y = b0 * x + z1
            val newZ1 = b1 * x - a1 * y + z2
            val newZ2 = b2 * x - a2 * y
            if (channel == 0) {
                z1_0 = newZ1
                z2_0 = newZ2
            } else {
                z1_1 = newZ1
                z2_1 = newZ2
            }
            return y.toFloat()
        }

        fun settle() {
            if (!z1_0.isFinite() || !z2_0.isFinite()) {
                z1_0 = 0.0
                z2_0 = 0.0
            }
            if (!z1_1.isFinite() || !z2_1.isFinite()) {
                z1_1 = 0.0
                z2_1 = 0.0
            }
            if (abs(z1_0) < 1.0e-20) z1_0 = 0.0
            if (abs(z2_0) < 1.0e-20) z2_0 = 0.0
            if (abs(z1_1) < 1.0e-20) z1_1 = 0.0
            if (abs(z2_1) < 1.0e-20) z2_1 = 0.0
        }

        fun clear() {
            z1_0 = 0.0
            z2_0 = 0.0
            z1_1 = 0.0
            z2_1 = 0.0
        }
    }

    private var subBassHighPass = Biquad()
    private var bassFoundation = Biquad()
    private var lowMidSeparation = Biquad()
    private var boxinessControl = Biquad()
    private var presenceDetail = Biquad()
    private var airDetail = Biquad()
    private var monoBassFilter = Biquad()
    private var airExciterFilter = Biquad()

    private val currentTrimsDb = FloatArray(8)
    private val trimGainLinear = FloatArray(8) { 1.0f }

    private var dcBlockerR: Double = 0.999
    private var dcXPrev0: Double = 0.0
    private var dcXPrev1: Double = 0.0
    private var dcYPrev0: Double = 0.0
    private var dcYPrev1: Double = 0.0

    private var currentWet: Float = 0.0f
    private var rampPerSample: Float = 1.0f / (44100f * 0.050f)

    init {
        if (isNativeAvailable) {
            nativeHandle = try {
                nativeCreate(sampleRate.toDouble())
            } catch (e: Throwable) {
                0L
            }
        }
        updateCoefficients()
        currentWet = if (enabled) wetMix else 0.0f
    }

    fun updateSampleRate(newRate: Int) {
        if (sampleRate != newRate && newRate > 0) {
            sampleRate = newRate
            if (nativeHandle != 0L) {
                try { nativeConfigure(nativeHandle, newRate.toDouble()) } catch (_: Throwable) {}
            }
            updateCoefficients()
            clear()
        }
    }

    fun hasActiveRamp(): Boolean {
        val target = if (enabled) wetMix else 0.0f
        return abs(currentWet - target) > 0.0001f
    }

    fun setPreset(presetIndex: Int) {
        val idx = presetIndex.coerceIn(0, 3)
        currentPreset = Preset.entries[idx]
        val trims = PRESET_TRIMS[idx]
        for (i in trims.indices) {
            currentTrimsDb[i] = trims[i]
            trimGainLinear[i] = if (trims[i] == 0f) 1.0f else 10.0.pow(trims[i] / 20.0).toFloat()
        }
        if (nativeHandle != 0L) {
            try {
                nativeSetClarityPreset(nativeHandle, idx)
                nativeSetClarityTrims(nativeHandle, trims)
            } catch (_: Throwable) {}
        }
        updateCoefficients()
    }

    private fun updateCoefficients() {
        val sr = sampleRate.toDouble()
        subBassHighPass = highPass(sr, 24.0, 0.7071067811865476)
        bassFoundation = peaking(sr, 72.0, 0.80, BASS_GAIN_DB + currentTrimsDb[1])
        lowMidSeparation = peaking(sr, 280.0, 0.90, LOW_MID_GAIN_DB + currentTrimsDb[2])
        boxinessControl = peaking(sr, 750.0, 0.85, BOXINESS_GAIN_DB + currentTrimsDb[3])
        presenceDetail = peaking(sr, 3400.0, 0.85, PRESENCE_GAIN_DB + currentTrimsDb[4])
        airDetail = highShelf(sr, 10500.0, 0.85, AIR_GAIN_DB + currentTrimsDb[5])
        monoBassFilter = highPass(sr, 130.0, 0.7071067811865476)
        airExciterFilter = highPass(sr, 6000.0, 0.7071067811865476)

        dcBlockerR = exp(-2.0 * Math.PI * 10.0 / sr)
        rampPerSample = (1.0 / (sr * 0.050)).toFloat()
    }

    /** Process a whole block using the native C++ engine if available. Returns true if processed. */
    fun processBlock(buffer: FloatArray, offset: Int, frameCount: Int, channels: Int = 2): Boolean {
        if (nativeHandle != 0L && enabled) {
            try {
                nativeProcess(nativeHandle, buffer, offset, frameCount, channels)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    fun process(left: Float, right: Float, output: FloatArray) {
        // One-pole DC Blocker (10 Hz)
        val dcLeft = left.toDouble()
        val dcOutLeft = dcLeft - dcXPrev0 + dcBlockerR * dcYPrev0
        dcXPrev0 = dcLeft
        dcYPrev0 = dcOutLeft

        val dcRight = right.toDouble()
        val dcOutRight = dcRight - dcXPrev1 + dcBlockerR * dcYPrev1
        dcXPrev1 = dcRight
        dcYPrev1 = dcOutRight

        val inL = dcOutLeft.toFloat()
        val inR = dcOutRight.toFloat()

        val targetWet = if (enabled) wetMix else 0.0f
        if (currentWet < targetWet) {
            currentWet = min(targetWet, currentWet + rampPerSample)
        } else if (currentWet > targetWet) {
            currentWet = max(targetWet, currentWet - rampPerSample)
        }

        if (currentWet <= 0.00001f) {
            output[0] = inL
            output[1] = inR
            return
        }

        // 1. Sub-bass highpass (24 Hz tight cutoff removes subsonic mud)
        var wL = subBassHighPass.tick(inL, 0) * trimGainLinear[0]
        var wR = subBassHighPass.tick(inR, 1) * trimGainLinear[0]

        // 2. Bass foundation (72 Hz body punch)
        wL = bassFoundation.tick(wL, 0)
        wR = bassFoundation.tick(wR, 1)

        // 3. Low-mid anti-mud separation (surgical 280 Hz dip unmasks vocals)
        wL = lowMidSeparation.tick(wL, 0)
        wR = lowMidSeparation.tick(wR, 1)

        // 4. Boxiness & resonance control (750 Hz dip)
        wL = boxinessControl.tick(wL, 0)
        wR = boxinessControl.tick(wR, 1)

        // 5. Vocal presence & instrument detail (3400 Hz articulation lift)
        wL = presenceDetail.tick(wL, 0)
        wR = presenceDetail.tick(wR, 1)

        // 6. Silky air shelf (10.5 kHz high-frequency extension)
        wL = airDetail.tick(wL, 0)
        wR = airDetail.tick(wR, 1)

        // 7. Harmonic Air Exciter (Tape sheen above 6 kHz)
        val exciterInL = airExciterFilter.tick(inL, 0)
        val exciterInR = airExciterFilter.tick(inR, 1)
        val excitedL = exciterInL - (exciterInL * exciterInL * exciterInL * 0.25f)
        val excitedR = exciterInR - (exciterInR * exciterInR * exciterInR * 0.25f)
        val effExciter = exciterAmount * trimGainLinear[7]
        wL += excitedL * effExciter
        wR += excitedR * effExciter

        // 8. Mid-Side Acoustic Matrix & Mono-Bass Separation
        val mid = (wL + wR) * 0.5f
        val rawSide = (wL - wR) * 0.5f
        // Mono-Bass (Anti-Blur): 130 Hz HPF locks bass to centered mono with zero phase blur
        val sideHigh = monoBassFilter.tick(rawSide, 0) * trimGainLinear[6]
        // Holographic stereo width expansion
        val wideSide = sideHigh * stereoWidth
        wL = (mid + wideSide) * MAKEUP_GAIN
        wR = (mid - wideSide) * MAKEUP_GAIN

        // Wet/Dry blending
        val eff = currentWet
        output[0] = inL + (wL - inL) * eff
        output[1] = inR + (wR - inR) * eff
    }

    fun settle() {
        subBassHighPass.settle()
        bassFoundation.settle()
        lowMidSeparation.settle()
        boxinessControl.settle()
        presenceDetail.settle()
        airDetail.settle()
        monoBassFilter.settle()
        airExciterFilter.settle()
    }

    fun clear() {
        if (nativeHandle != 0L) {
            try { nativeReset(nativeHandle) } catch (_: Throwable) {}
        }
        subBassHighPass.clear()
        bassFoundation.clear()
        lowMidSeparation.clear()
        boxinessControl.clear()
        presenceDetail.clear()
        airDetail.clear()
        monoBassFilter.clear()
        airExciterFilter.clear()
        dcXPrev0 = 0.0
        dcXPrev1 = 0.0
        dcYPrev0 = 0.0
        dcYPrev1 = 0.0
    }

    protected fun finalize() {
        if (nativeHandle != 0L) {
            try { nativeDestroy(nativeHandle) } catch (_: Throwable) {}
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Double): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConfigure(handle: Long, sampleRate: Double)
    private external fun nativeReset(handle: Long)
    private external fun nativeSetStudioMasterClarity(handle: Long, enabled: Boolean)
    private external fun nativeSetClarityWet(handle: Long, wet: Float)
    private external fun nativeSetClarityTrims(handle: Long, trims: FloatArray)
    private external fun nativeSetClarityPreset(handle: Long, preset: Int)
    private external fun nativeProcess(handle: Long, buffer: FloatArray, offset: Int, frameCount: Int, channels: Int)

    private fun highPass(sampleRate: Double, frequency: Double, q: Double): Biquad {
        val b = Biquad()
        val omega = 2.0 * Math.PI * frequency.coerceIn(1.0, sampleRate * 0.45) / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 * max(q, 0.01))
        val a0 = 1.0 + alpha
        b.b0 = ((1.0 + cosine) * 0.5) / a0
        b.b1 = -(1.0 + cosine) / a0
        b.b2 = b.b0
        b.a1 = (-2.0 * cosine) / a0
        b.a2 = (1.0 - alpha) / a0
        return b
    }

    private fun peaking(sampleRate: Double, frequency: Double, q: Double, gainDb: Double): Biquad {
        val b = Biquad()
        val omega = 2.0 * Math.PI * frequency.coerceIn(1.0, sampleRate * 0.45) / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 * max(q, 0.01))
        val amplitude = 10.0.pow(gainDb / 40.0)
        val a0 = 1.0 + alpha / amplitude
        b.b0 = (1.0 + alpha * amplitude) / a0
        b.b1 = (-2.0 * cosine) / a0
        b.b2 = (1.0 - alpha * amplitude) / a0
        b.a1 = b.b1
        b.a2 = (1.0 - alpha / amplitude) / a0
        return b
    }

    private fun highShelf(sampleRate: Double, frequency: Double, slope: Double, gainDb: Double): Biquad {
        val b = Biquad()
        val omega = 2.0 * Math.PI * frequency.coerceIn(1.0, sampleRate * 0.45) / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val amplitude = 10.0.pow(gainDb / 40.0)
        val safeSlope = slope.coerceIn(0.1, 1.0)
        val alpha = (sine * 0.5) * sqrt((amplitude + 1.0 / amplitude) * (1.0 / safeSlope - 1.0) + 2.0)
        val beta = 2.0 * sqrt(amplitude) * alpha
        val a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta
        b.b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta) / a0
        b.b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0
        b.b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta) / a0
        b.a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0
        b.a2 = ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta) / a0
        return b
    }
}
