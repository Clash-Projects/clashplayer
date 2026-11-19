package com.clashplayer.dsp

import kotlin.math.*

/**
 * Audiophile Binaural Room & HRTF Spatializer
 *
 * Simulates Georg Neumann KU 100 binaural dummy head acoustics and a calibrated
 * studio mastering control room. Eliminates headphone in-head localization and listening fatigue
 * by synthesizing anatomical head acoustic shadowing (ITD + ILD), pinna concha notch filtering,
 * and natural studio room early reflections.
 */
class BinauralSpatializer(
    private var sampleRate: Int = 44100
) {
    var enabled: Boolean = false

    /** Room ambience level (0.0 = anechoic/dry, 1.0 = maximum acoustic room depth). Default: 0.35 */
    var roomAmbience: Float = 0.35f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateGains()
        }

    /** Out-of-head stereo soundstage width multiplier (0.5x to 2.0x). Default: 1.15 */
    var soundstageWidth: Float = 1.15f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            updateGains()
        }

    /** Interaural Time Delay in microseconds (typical head size 200..360 us). Default: 280 us */
    var itdMicros: Float = 280f
        set(value) {
            field = value.coerceIn(150f, 400f)
            updateDelays()
        }

    /** Direct dry signal vs spatial room mix (0.0 to 1.0). Default: 0.85 */
    var directMix: Float = 0.85f
        set(value) {
            field = value.coerceIn(0.2f, 1.0f)
            updateGains()
        }

    // Delay line buffer sizes (max 400 us ITD delay + 50 ms room reflections)
    private val maxItdSamples = 256
    private val itdBufferL = FloatArray(maxItdSamples)
    private val itdBufferR = FloatArray(maxItdSamples)
    private var itdWriteIndex = 0
    private var itdDelaySamples = 12

    // Contralateral Head-Shadow High-Shelf Filter (models skull diffraction)
    // Lowers high frequencies on the opposite ear by -5 dB above 3 kHz
    private val shadowFilterL = BiquadFilter(sampleRate)
    private val shadowFilterR = BiquadFilter(sampleRate)

    // Ipsilateral Pinna Notch Filter (Neumann KU 100 anatomical 6.0 kHz concha notch)
    private val pinnaNotchL = BiquadFilter(sampleRate)
    private val pinnaNotchR = BiquadFilter(sampleRate)

    // Ear Canal 3.2 kHz acoustic resonance boost
    private val earCanalL = BiquadFilter(sampleRate)
    private val earCanalR = BiquadFilter(sampleRate)

    // Studio BRIR (Binaural Room Impulse Response) Early Reflection Delay Network
    // 4 discrete early reflection taps in an acoustically treated mastering room
    private val maxReflectionSamples = 4096
    private val roomReflectL = FloatArray(maxReflectionSamples)
    private val roomReflectR = FloatArray(maxReflectionSamples)
    private var roomIndex = 0

    // Taps at roughly 11.2ms, 17.5ms, 25.8ms, 36.1ms
    private var tap1 = 0
    private var tap2 = 0
    private var tap3 = 0
    private var tap4 = 0

    // Diffuse room tail low-pass damping
    private var dampL = 0f
    private var dampR = 0f

    // Precalculated mixing gains
    private var directGain = 1.0f
    private var crossGain = 0.45f
    private var roomWetGain = 0.25f

    init {
        updateSampleRate(sampleRate)
    }

    fun updateSampleRate(newRate: Int) {
        if (newRate <= 0) return
        sampleRate = newRate
        updateDelays()

        // Contralateral head shadow: high-shelf -5.0 dB at 3200 Hz, Q=0.707
        shadowFilterL.updateSampleRate(newRate)
        shadowFilterR.updateSampleRate(newRate)
        shadowFilterL.configure(com.clashplayer.dsp.model.FilterType.HIGH_SHELF, 3200, -5.0f, 0.707f)
        shadowFilterR.configure(com.clashplayer.dsp.model.FilterType.HIGH_SHELF, 3200, -5.0f, 0.707f)

        // Pinna notch: 6000 Hz notch, -4.0 dB, Q=3.2 (KU 100 ear notch)
        pinnaNotchL.updateSampleRate(newRate)
        pinnaNotchR.updateSampleRate(newRate)
        pinnaNotchL.configure(com.clashplayer.dsp.model.FilterType.PEAKING, 6000, -4.0f, 3.2f)
        pinnaNotchR.configure(com.clashplayer.dsp.model.FilterType.PEAKING, 6000, -4.0f, 3.2f)

        // Ear canal resonance: 3200 Hz gentle presence, +2.2 dB, Q=1.8
        earCanalL.updateSampleRate(newRate)
        earCanalR.updateSampleRate(newRate)
        earCanalL.configure(com.clashplayer.dsp.model.FilterType.PEAKING, 3200, 2.2f, 1.8f)
        earCanalR.configure(com.clashplayer.dsp.model.FilterType.PEAKING, 3200, 2.2f, 1.8f)

        updateGains()
    }

    private fun updateDelays() {
        itdDelaySamples = (itdMicros * 0.000001f * sampleRate).roundToInt().coerceIn(1, maxItdSamples - 1)

        // Room reflection tap offsets (in samples)
        tap1 = (0.0112f * sampleRate).roundToInt().coerceIn(1, maxReflectionSamples - 1)
        tap2 = (0.0175f * sampleRate).roundToInt().coerceIn(1, maxReflectionSamples - 1)
        tap3 = (0.0258f * sampleRate).roundToInt().coerceIn(1, maxReflectionSamples - 1)
        tap4 = (0.0361f * sampleRate).roundToInt().coerceIn(1, maxReflectionSamples - 1)
    }

    private fun updateGains() {
        directGain = directMix
        // Crossfeed level decreases slightly as soundstage width increases
        crossGain = (0.42f / sqrt(soundstageWidth)).coerceIn(0.15f, 0.65f)
        roomWetGain = (roomAmbience * 0.32f).coerceIn(0f, 0.45f)
    }

    /**
     * Process a stereo frame (left, right) in-place into [out] (FloatArray of size >= 2).
     * Zero-allocation, high-performance execution.
     */
    fun process(left: Float, right: Float, out: FloatArray) {
        if (!enabled) {
            out[0] = left
            out[1] = right
            return
        }

        // 1. Buffer incoming direct samples in the ITD delay line
        itdBufferL[itdWriteIndex] = left
        itdBufferR[itdWriteIndex] = right

        // Read contralateral delayed samples (inter-aural acoustic arrival delay)
        val readIdx = (itdWriteIndex - itdDelaySamples + maxItdSamples) % maxItdSamples
        val delayedL = itdBufferL[readIdx]
        val delayedR = itdBufferR[readIdx]

        itdWriteIndex = (itdWriteIndex + 1) % maxItdSamples

        // 2. Direct ear path: pinna notch + ear canal resonance
        val directL = earCanalL.processSample(pinnaNotchL.processSample(left, 0), 0)
        val directR = earCanalR.processSample(pinnaNotchR.processSample(right, 1), 1)

        // 3. Contralateral ear path: head acoustic shadow high-shelf filter
        val shadowCrossL = shadowFilterR.processSample(delayedR, 0) // Right sound leaking to Left ear
        val shadowCrossR = shadowFilterL.processSample(delayedL, 1) // Left sound leaking to Right ear

        // 4. Studio BRIR Room Ambience simulation
        val roomInput = (left + right) * 0.5f
        roomReflectL[roomIndex] = roomInput + dampL * 0.28f
        roomReflectR[roomIndex] = roomInput + dampR * 0.28f

        val t1 = (roomIndex - tap1 + maxReflectionSamples) % maxReflectionSamples
        val t2 = (roomIndex - tap2 + maxReflectionSamples) % maxReflectionSamples
        val t3 = (roomIndex - tap3 + maxReflectionSamples) % maxReflectionSamples
        val t4 = (roomIndex - tap4 + maxReflectionSamples) % maxReflectionSamples

        // Early reflections with binaural decorrelation
        val earlyL = roomReflectL[t1] * 0.45f - roomReflectL[t3] * 0.28f
        val earlyR = roomReflectR[t2] * 0.42f - roomReflectR[t4] * 0.25f

        // Damped high-frequency acoustic tail
        dampL = dampL * 0.65f + earlyL * 0.35f
        dampR = dampR * 0.65f + earlyR * 0.35f

        roomIndex = (roomIndex + 1) % maxReflectionSamples

        // 5. Combine direct, crossfeed, and room reflections
        val outL = (directL * directGain) + (shadowCrossL * crossGain) + (earlyL * roomWetGain)
        val outR = (directR * directGain) + (shadowCrossR * crossGain) + (earlyR * roomWetGain)

        out[0] = outL
        out[1] = outR
    }

    fun reset() {
        itdBufferL.fill(0f)
        itdBufferR.fill(0f)
        roomReflectL.fill(0f)
        roomReflectR.fill(0f)
        itdWriteIndex = 0
        roomIndex = 0
        dampL = 0f
        dampR = 0f
        shadowFilterL.reset()
        shadowFilterR.reset()
        pinnaNotchL.reset()
        pinnaNotchR.reset()
        earCanalL.reset()
        earCanalR.reset()
    }
}
