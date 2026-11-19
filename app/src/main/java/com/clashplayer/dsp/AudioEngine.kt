package com.clashplayer.dsp

import com.clashplayer.dsp.model.EqualizerBand
import com.clashplayer.dsp.model.EqualizerPreset
import com.clashplayer.dsp.model.ReverbPreset
import com.clashplayer.dsp.model.FilterType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

class AudioEngine(
    var sampleRate: Int = 44100
) {
    var dvcEnabled: Boolean = true
    // Per-output overrides (device profile); the global switches above stay as the user set them
    @Volatile var outputAllowsDvc = true
    @Volatile var outputAllowsEq = true
    @Volatile var outputHeadroom = true
    // Attenuation reserved for EQ/tone boosts when the system controls volume (DVC off); 0 disables it
    var dvcHeadroomDb: Float = 6.0f
    var volume: Float = 1.0f // 0.0 to 1.0
    var volumeSteps: Int = 100
    var volumeCurve: VolumeCurve = VolumeCurve.MEDIA
    // Last applied master gain; each buffer ramps from it to the new target so volume moves don't click
    private var currentMasterGain = Float.NaN
    // Bit-perfect output: samples must reach the DAC untouched, so all processing is bypassed
    @Volatile var bitPerfect: Boolean = false
    // Bit-perfect to a DAC with no volume control of its own: still apply the volume curve, or it plays at full scale
    @Volatile var bypassSoftwareVolume: Boolean = false
    // Off for streams that aren't audio samples (DoP), so the visualizer doesn't draw their bit patterns
    @Volatile var visualizerFeed: Boolean = true
    /** Channels per frame in bypass mode (multichannel pass-through); the DSP path is always stereo. */
    @Volatile var bypassChannels: Int = 2

    var preampDb: Float = 0.0f // -24dB to +24dB
    var bassDb: Float = 0.0f   // -15dB to +15dB
    var trebleDb: Float = 0.0f // -15dB to +15dB

    var bassFreqHz: Int = 100
    var trebleFreqHz: Int = 10000

    var equalizerEnabled: Boolean = true
    var toneEnabled: Boolean = true
    var isParametricMode: Boolean = false

    val graphicBands = EqualizerBand.DEFAULT_10_BANDS.mapIndexed { idx, freq ->
        EqualizerBand(
            type = FilterType.PEAKING.id,
            frequency = freq,
            q = 1.414f,
            gain = 0.0f,
            name = if (freq >= 1000) "${freq / 1000}K" else "$freq"
        )
    }.toMutableList()

    // Swapped as a whole so the audio thread always iterates a consistent snapshot
    @Volatile var parametricBands: List<EqualizerBand> = emptyList()
        private set

    data class EqualizerState(val enabled: Boolean, val name: String, val bands: List<EqualizerBand>, val revision: Int)
    private val _equalizerStateFlow = MutableStateFlow(EqualizerState(true, "Flat", graphicBands, 0))
    val equalizerStateFlow: StateFlow<EqualizerState> = _equalizerStateFlow.asStateFlow()

    // Controls are set from the UI thread; coefficients are recomputed on the audio thread at the next buffer
    @Volatile private var filtersDirty = false

    // DSP Processors
    private val graphicFilters = Array(10) { BiquadFilter(sampleRate) }
    private var parametricFilters = mutableListOf<BiquadFilter>()
    private val bassFilter = BiquadFilter(sampleRate)
    private val trebleFilter = BiquadFilter(sampleRate)

    val stereoProcessor = StereoProcessor()
    val reverbProcessor = ReverbProcessor(sampleRate)
    val limiterProcessor = LimiterProcessor(sampleRate)
    val compressor = CompressorProcessor(sampleRate)
    /** Pitch-preserving tempo; applied by the playback loop before this chain, since it changes the sample count. */
    val tempoProcessor = TempoProcessor(sampleRate)
    /** Dither for integer outputs; the output stage calls it once it knows the bit depth. */
    val ditherer = Ditherer()

    /** ReplayGain for the current track, in dB (set by the player). */
    @Volatile var replayGainDb = 0f
    /** Pull EQ/tone boosts back near full volume so they can't overload (DVC only). */
    @Volatile var smoothGains = true
    // Largest boost of the current EQ/tone curve, refreshed with the coefficients
    private var eqPeakDb = 0f

    // Play/pause/seek fade envelope, applied after the master gain
    @Volatile private var fadeLevel = 1f
    @Volatile private var fadeTarget = 1f
    @Volatile private var fadeStep = 0f

    // 60fps Visualizer data buffers
    private val fftSize = 512
    private val fftWindow = FloatArray(fftSize)
    private var fftIndex = 0
    val spectrumMagnitudes = FloatArray(128)
    val waveformBuffer = FloatArray(256)
    private val _fftSpectrumFlow = MutableStateFlow(FloatArray(128))
    val fftSpectrumFlow: StateFlow<FloatArray> = _fftSpectrumFlow.asStateFlow()
    private val _waveformFlow = MutableStateFlow(FloatArray(256))
    val waveformFlow: StateFlow<FloatArray> = _waveformFlow.asStateFlow()

    init {
        updateAllFilters()
    }

    fun updateSampleRate(newRate: Int) {
        if (sampleRate != newRate && newRate > 0) {
            sampleRate = newRate
            com.clashplayer.diagnostics.DiagnosticsManager.activeSampleRate = newRate
            com.clashplayer.diagnostics.DiagnosticsManager.audio("AudioEngine", "DSP pipeline clock switched to ${newRate}Hz")
            bassFilter.updateSampleRate(newRate)
            trebleFilter.updateSampleRate(newRate)
            graphicFilters.forEach { it.updateSampleRate(newRate) }
            parametricFilters.forEach { it.updateSampleRate(newRate) }
            reverbProcessor.updateSampleRate(newRate)
            limiterProcessor.updateSampleRate(newRate)
            compressor.updateSampleRate(newRate)
            tempoProcessor.updateSampleRate(newRate)
            updateAllFilters()
        }
    }

    private fun controlsChanged(name: String = _equalizerStateFlow.value.name) {
        filtersDirty = true
        val s = _equalizerStateFlow.value
        _equalizerStateFlow.value = EqualizerState(equalizerEnabled, name, if (isParametricMode) parametricBands else graphicBands, s.revision + 1)
    }

    @JvmName("updateEqualizerEnabled") // the property setter already owns setEqualizerEnabled
    fun setEqualizerEnabled(enabled: Boolean) { equalizerEnabled = enabled; controlsChanged() }

    fun setBandGain(index: Int, gainDb: Float) {
        val bands = if (isParametricMode) parametricBands else graphicBands
        if (bands.getOrNull(index)?.setGainDb(gainDb) == true) controlsChanged("Custom")
    }

    fun resetBands() {
        isParametricMode = false
        graphicBands.forEach { it.setGainDb(0f) }
        preampDb = 0f
        controlsChanged("Flat")
    }

    fun applyPreset(preset: EqualizerPreset) {
        if (preset.isParametric) {
            parametricBands = preset.parametricBands
            isParametricMode = true
        } else {
            isParametricMode = false
            graphicBands.forEachIndexed { i, b -> b.setGainDb(preset.bandGains.getOrElse(i) { 0f }) }
        }
        bassDb = preset.bass
        trebleDb = preset.treble
        preampDb = preset.preamp
        controlsChanged(preset.name)
    }

    fun setPreamp(db: Float) { preampDb = db; controlsChanged() }
    fun setToneControls(bass: Float, treble: Float) { bassDb = bass; trebleDb = treble; controlsChanged() }
    fun setStereoProcessor(width: Float, balance: Float) {
        stereoProcessor.stereoWidth = width
        stereoProcessor.balance = balance
    }
    fun applyReverbPreset(preset: ReverbPreset) {
        reverbProcessor.roomSize = preset.roomSize
        reverbProcessor.damping = preset.damping
        reverbProcessor.wetMix = preset.wetMix
        reverbProcessor.enabled = true
    }

    fun updateAllFilters() {
        // Update Tone controls
        bassFilter.configure(FilterType.LOW_SHELF, bassFreqHz, if (toneEnabled) bassDb else 0.0f, 0.707f)
        trebleFilter.configure(FilterType.HIGH_SHELF, trebleFreqHz, if (toneEnabled) trebleDb else 0.0f, 0.707f)

        // Update Graphic EQ
        for (i in graphicBands.indices) {
            val band = graphicBands[i]
            val gain = if (equalizerEnabled && band.enabled) band.getGainDb() else 0.0f
            graphicFilters[i].configure(FilterType.fromId(band.type), band.frequency, gain, band.q)
        }

        // Sync Parametric Filters
        while (parametricFilters.size < parametricBands.size) {
            parametricFilters.add(BiquadFilter(sampleRate))
        }
        for (i in parametricBands.indices) {
            val band = parametricBands[i]
            val gain = if (equalizerEnabled && band.enabled) band.getGainDb() else 0.0f
            parametricFilters[i].configure(FilterType.fromId(band.type), band.frequency, gain, band.q)
        }
        eqPeakDb = peakBoostDb()
    }

    /** Highest point of the combined EQ + tone response on a 1/6-octave grid, in dB (>= 0). */
    private fun peakBoostDb(): Float {
        var peak = 0.0
        var f = 20.0
        while (f < min(20000.0, sampleRate * 0.49)) {
            var db = 0.0
            if (toneEnabled) db += bassFilter.magnitudeDb(f) + trebleFilter.magnitudeDb(f)
            if (equalizerEnabled) {
                if (isParametricMode) parametricBands.forEachIndexed { i, b -> if (b.enabled) db += parametricFilters[i].magnitudeDb(f) }
                else graphicBands.forEachIndexed { i, b -> if (b.enabled) db += graphicFilters[i].magnitudeDb(f) }
            }
            peak = max(peak, db)
            f *= 1.122462 // 2^(1/6)
        }
        return peak.toFloat()
    }

    /** Ramps the fade envelope to [target] (0..1) over [ms]; 0 ms jumps. Only the DSP path fades. */
    fun fadeTo(target: Float, ms: Int) {
        val t = target.coerceIn(0f, 1f)
        if (ms <= 0) { fadeLevel = t; fadeTarget = t; return }
        fadeStep = abs(t - fadeLevel) / (ms * 0.001f * sampleRate).coerceAtLeast(1f)
        fadeTarget = t
    }

    val fadeDone: Boolean get() = fadeLevel == fadeTarget

    /**
     * Compute effective DVC gain multiplier.
     * Incorporates logarithmic curve and headroom gain.
     */
    fun computeEffectiveVolumeGain(): Float {
        // System volume does the attenuation; only reserve headroom so EQ boosts can't clip
        if (!dvcEnabled || !outputAllowsDvc) {
            val headroom = if (outputHeadroom && outputAllowsEq) dvcHeadroomDb.coerceIn(0f, 24f) else 0f
            return 10.0.pow((replayGainDb - headroom) / 20.0).toFloat()
        }

        var db = (volumeDb() ?: return 0.0f) + replayGainDb
        // Near full volume a boost would clip: give back exactly the part that would go over 0 dBFS
        if (smoothGains) db -= (db + preampDb + if (outputAllowsEq) eqPeakDb else 0f).coerceAtLeast(0f)
        return 10.0.pow(db / 20.0).toFloat()
    }

    /** Volume on the curve in dB (top step is 0 dB, so it only attenuates and can't clip), or null for mute. */
    fun volumeDb(): Float? = volumeCurve.stepToDb((volume.coerceIn(0f, 1f) * volumeSteps).roundToInt(), volumeSteps)

    private fun applyRampedGain(buffer: FloatArray, offset: Int, sampleCount: Int, target: Float) {
        // Per-sample ramp, so it works for any channel count (bypass mode may carry multichannel audio)
        val start = if (currentMasterGain.isNaN()) target else currentMasterGain
        val step = (target - start) / sampleCount.coerceAtLeast(1)
        var g = start
        for (i in offset until offset + sampleCount) {
            g += step
            buffer[i] *= g
        }
        currentMasterGain = target
    }

    /**
     * Process an interleaved stereo float audio buffer in-place
     */
    fun processInterleavedStereo(buffer: FloatArray, offset: Int, sampleCount: Int) {
        if (bitPerfect) {
            if (bypassSoftwareVolume) applyRampedGain(buffer, offset, sampleCount, volumeDb()?.let { 10.0.pow(it / 20.0).toFloat() } ?: 0f)
            if (visualizerFeed) {
                // Read-only tap of the front pair: the samples themselves stay untouched
                val ch = bypassChannels.coerceAtLeast(1)
                var i = offset
                while (i + ch <= offset + sampleCount) {
                    fftWindow[fftIndex] = if (ch > 1) (buffer[i] + buffer[i + 1]) * 0.5f else buffer[i]
                    fftIndex = (fftIndex + 1) % fftSize
                    i += ch
                }
                updateFftAndWaveform()
            }
            return
        }
        if (filtersDirty) {
            filtersDirty = false
            updateAllFilters()
        }
        val parametricBands = parametricBands
        val eqAllowed = outputAllowsEq
        val volGain = computeEffectiveVolumeGain()
        val preampGain = if (eqAllowed) 10.0.pow(preampDb / 20.0).toFloat() else 1f
        val targetGain = volGain * preampGain
        val startGain = if (currentMasterGain.isNaN()) targetGain else currentMasterGain
        val frames = (sampleCount / 2).coerceAtLeast(1)
        val gainStep = (targetGain - startGain) / frames
        var totalMasterGain = startGain
        currentMasterGain = targetGain

        val stereoTemp = FloatArray(2)

        var i = offset
        val end = offset + sampleCount
        while (i < end - 1) {
            totalMasterGain += gainStep
            if (fadeLevel != fadeTarget) {
                fadeLevel = if (fadeLevel < fadeTarget) min(fadeTarget, fadeLevel + fadeStep) else max(fadeTarget, fadeLevel - fadeStep)
            }
            val g = totalMasterGain * fadeLevel
            var left = buffer[i] * g
            var right = buffer[i + 1] * g

            // Apply Tone Controls (Bass / Treble)
            if (toneEnabled && eqAllowed) {
                left = bassFilter.processSample(left, 0)
                right = bassFilter.processSample(right, 1)
                left = trebleFilter.processSample(left, 0)
                right = trebleFilter.processSample(right, 1)
            }

            // Apply Equalizer
            if (equalizerEnabled && eqAllowed) {
                if (isParametricMode) {
                    for (bIdx in parametricBands.indices) {
                        val b = parametricBands[bIdx]
                        if (!b.enabled) continue
                        val filter = parametricFilters[bIdx]
                        if (b.channels == 0 || b.channels == 1) left = filter.processSample(left, 0)
                        if (b.channels == 0 || b.channels == 2) right = filter.processSample(right, 1)
                    }
                } else {
                    for (bIdx in graphicBands.indices) {
                        val b = graphicBands[bIdx]
                        if (!b.enabled) continue
                        val filter = graphicFilters[bIdx]
                        left = filter.processSample(left, 0)
                        right = filter.processSample(right, 1)
                    }
                }
            }

            // Spatial stereo processor (width + balance)
            stereoProcessor.process(left, right, stereoTemp)
            left = stereoTemp[0]
            right = stereoTemp[1]

            // Reverb
            reverbProcessor.process(left, right, stereoTemp)
            left = stereoTemp[0]
            right = stereoTemp[1]

            if (compressor.enabled) {
                compressor.process(left, right, stereoTemp)
                left = stereoTemp[0]
                right = stereoTemp[1]
            }

            // Soft-knee peak limiting
            left = limiterProcessor.process(left)
            right = limiterProcessor.process(right)

            buffer[i] = left
            buffer[i + 1] = right

            // Feed FFT window for real-time visualizer
            fftWindow[fftIndex] = (left + right) * 0.5f
            fftIndex = (fftIndex + 1) % fftSize

            i += 2
        }

        updateFftAndWaveform()
    }

    /** Overall loudness of the visualizer spectrum, 0..1, for ambient effects. Allocation-free. */
    fun energy(): Float {
        val m = _fftSpectrumFlow.value
        var sum = 0f
        for (i in 0 until 24) sum += m[i]
        return (sum / 24f * 3.2f).coerceIn(0f, 1f)
    }

    private fun updateFftAndWaveform() {
        // Fast Hartley/DFT bin magnitude approximation for 128 visualizer bins
        val step = fftSize / 128
        for (bin in 0 until 128) {
            val startIdx = bin * step
            var mag = 0.0f
            for (k in 0 until step) {
                val s = fftWindow[(startIdx + k) % fftSize]
                mag += abs(s)
            }
            // Smooth decay envelope
            val target = (mag / step.toFloat()) * (1.0f + bin * 0.05f)
            spectrumMagnitudes[bin] = spectrumMagnitudes[bin] * 0.75f + target * 0.25f
        }

        // Waveform downsample
        for (w in 0 until 256) {
            waveformBuffer[w] = fftWindow[(w * 2) % fftSize]
        }
        // Publish copies: the UI must never see the audio thread mutating an array it is drawing
        _fftSpectrumFlow.value = spectrumMagnitudes.copyOf()
        _waveformFlow.value = waveformBuffer.copyOf()
    }

}
