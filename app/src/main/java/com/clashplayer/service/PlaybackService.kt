package com.clashplayer.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.clashplayer.dsd.DsdRemaster
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.ReplayGainInfo
import com.clashplayer.dsp.ReplayGainMode
import com.clashplayer.dsp.Resampler
import com.clashplayer.dsp.packPcm
import com.clashplayer.model.Lyrics
import com.clashplayer.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class PlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val audioEngine = AudioEngine()
    lateinit var outputManager: AudioOutputManager
        private set

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var progressJob: Job? = null

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    var isShuffle: Boolean = false
    var repeatMode: Int = REPEAT_ALL // 0 = off, 1 = all, 2 = one

    val settings = PlaybackSettings()

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerRemainingMinutes: StateFlow<Int?> = _sleepTimerRemainingMinutes.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        outputManager = AudioOutputManager(this)
        store = SettingsStore(this)
        store.load(audioEngine, settings)?.let(outputManager::selectDriver)
        _volume.value = audioEngine.volume
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        createNotificationChannel()

        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Throwable) {
            try { registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (_: Throwable) {}
        }

        try {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Failed to register volume content observer: ${t.message}")
        }
        syncSystemVolume()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncSystemVolume()
        }
    }

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            syncSystemVolume()
        }
    }

    private val _volume = MutableStateFlow(1f)
    /** In-app volume 0..1 (the Now Playing slider). */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** Sets the app volume; the audio loop sends it to the DAC's hardware volume, or applies it in software. */
    fun setVolume(v: Float) {
        val c = v.coerceIn(0f, 1f)
        audioEngine.volume = c
        _volume.value = c
    }

    /** Volume rocker: our own USB driver ignores Android's volume, so there the keys move the app volume. */
    fun stepVolume(up: Boolean) {
        if (usbOutput != null) setVolume(audioEngine.volume + if (up) 0.05f else -0.05f)
        else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    private var lastStreamVolume = -1

    /**
     * Mirrors a system media volume change onto the app volume, only on our own USB driver. Elsewhere Android
     * already applies it, and copying it into the app too would attenuate twice.
     */
    fun syncSystemVolume() {
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // The observer fires on every system setting (brightness etc.); only a real change may move the volume
            if (max <= 0 || current == lastStreamVolume) return
            val first = lastStreamVolume < 0
            lastStreamVolume = current
            if (!first && usbOutput != null) setVolume(current.toFloat() / max)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Failed to sync system volume: ${t.message}")
        }
    }

    private lateinit var store: SettingsStore
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = applyOutputProfile()
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = applyOutputProfile()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queue.value = tracks
        _currentQueueIndex.value = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        if (tracks.isNotEmpty()) {
            loadTrack(tracks[_currentQueueIndex.value], autoPlay = true)
        }
    }

    @Volatile private var decoder: AudioDecoder? = null
    // Converts to the USB DAC's rate when it can't run at the track's own
    @Volatile private var resampler: Resampler? = null
    @Volatile private var remaster: DsdRemaster? = null
    @Volatile private var nativeOut: NativeAudioOutput? = null
    @Volatile private var pendingWarmupMs = 0
    @Volatile private var fadingOut = false
    private var pauseGen = 0
    // Seeks are handed to the audio thread, which owns the decoder
    @Volatile private var pendingSeekMs = -1L

    fun loadTrack(track: Track, autoPlay: Boolean = true) {
        // The audio loop owns the decoder; stop it before swapping (never called from the loop itself)
        runBlocking { playbackJob?.cancelAndJoin() }
        decoder?.close()
        decoder = null
        pendingSeekMs = -1L
        _currentTrack.value = track
        _durationMs.value = track.durationMs
        _currentPositionMs.value = 0L

        val dec = try {
            com.clashplayer.diagnostics.DiagnosticsManager.i("Playback", "Loading track: ${track.title} [${track.artist}]")
            openDecoder(track.uri)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot decode ${track.uri}", e)
            com.clashplayer.diagnostics.DiagnosticsManager.e("Playback", "Cannot decode ${track.uri}: ${e.message}", e)
            _isPlaying.value = false
            return
        }
        decoder = dec
        // MediaStore often has no duration for DSD; the decoder's own length is authoritative when known
        if (dec.durationMs > 0) _durationMs.value = dec.durationMs
        _currentLyrics.value = null // no lyrics source yet; never show placeholder text as if it were real
        audioEngine.replayGainDb = replayGainFor(track)

        initOutput(dec)

        if (autoPlay) {
            play()
        }
    }

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // Encoding the AudioTrack was opened with; a bit-perfect mixer requires the track to match it exactly
    @Volatile private var outputEncoding = AudioFormat.ENCODING_PCM_FLOAT

    // Set only while the audio loop is stopped; read by the loop
    @Volatile private var usbOutput: com.clashplayer.usb.UsbAudioOutput? = null

    private fun closeUsbOutput() {
        usbOutput?.let { runCatching { it.close() } }
        usbOutput = null
        outputManager.onUsbDirectClosed()
    }

    /** DSF/DFF are read directly (the platform has no DSD codec); everything else goes to MediaCodec. */
    private fun openDecoder(uri: android.net.Uri): AudioDecoder {
        contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
            val stream = java.io.FileInputStream(pfd.fileDescriptor)
            val head = java.nio.ByteBuffer.allocate(4)
            stream.channel.read(head, 0)
            if (com.clashplayer.dsd.DsdSource.isDsd(head.array())) {
                return try {
                    DsdDecoder(stream.channel) { stream.close(); pfd.close() }
                } catch (e: Exception) {
                    stream.close(); pfd.close(); throw e
                }
            }
            stream.close(); pfd.close()
        }
        return TrackDecoder(this, uri)
    }

    private fun useUsb(usb: com.clashplayer.usb.UsbAudioOutput, dec: AudioDecoder) {
        usbOutput = usb
        val bitstream = dec is DsdDecoder && (dec.useDop || dec.nativeDsd)
        // Pass every channel through when the DAC has room for them; otherwise a stereo downmix
        if (!bitstream) dec.outChannels = if (usb.channels >= dec.channels) dec.channels else 2
        audioEngine.bitPerfect = usb.exact
        audioEngine.bypassSoftwareVolume = !usb.hasHardwareVolume
        audioEngine.forcedDvc = !usb.hasHardwareVolume
        audioEngine.visualizerFeed = !bitstream
        audioEngine.bypassChannels = dec.outChannels
        audioEngine.updateSampleRate(dec.sampleRate)
        outputManager.onTrackFormatChanged(dec.sampleRate, dec.bitDepth)
        resampler = if (!audioEngine.bitPerfect && usb.sampleRate != dec.sampleRate) Resampler(dec.sampleRate, usb.sampleRate, dec.outChannels) else null
        if (!bitstream) pendingWarmupMs = settings.usbWarmupMs // DSD silence would need its own idle pattern
        applyOutputProfile()
        syncSystemVolume()
    }

    /** DSP path (stereo, processed) setup shared by every non-bit-perfect output. */
    private fun useDspPath(dec: AudioDecoder) {
        audioEngine.bitPerfect = false
        audioEngine.bypassSoftwareVolume = false
        audioEngine.forcedDvc = null
        audioEngine.visualizerFeed = true
        audioEngine.bypassChannels = 2
        dec.outChannels = 2
        audioEngine.updateSampleRate(dec.sampleRate)
        outputManager.onTrackFormatChanged(dec.sampleRate, dec.bitDepth)
    }

    private fun initOutput(dec: AudioDecoder) {
        audioTrack?.release()
        audioTrack = null
        nativeOut?.close()
        nativeOut = null
        closeUsbOutput()
        resampler = null
        remaster = null
        pendingWarmupMs = 0
        val retry: () -> Unit = { serviceScope.launch(Dispatchers.Main) { reopenOutput() } }
        val usbProfile = outputManager.attachedUsbName()?.let { store.profiles[OutputDevice.usb(it).key] } ?: OutputProfile()

        // DSD is never attenuated in software (that would destroy it), so bitstream modes need the DAC's own volume
        if (dec is DsdDecoder) {
            dec.useDop = false
            dec.nativeDsd = false
            if (settings.preferNativeDsd) {
                val raw = outputManager.openUsbDirect(dec.nativeRate, 32, dec.channels, retry, nativeDsd = true)
                if (raw != null && raw.isNativeDsd && raw.exact && raw.hasHardwareVolume && raw.channels >= dec.channels) {
                    dec.nativeDsd = true
                    return useUsb(raw, dec)
                }
                raw?.close()
                outputManager.onUsbDirectClosed()
            }
            if (usbProfile.allowDop) {
                val dop = outputManager.openUsbDirect(dec.dopRate, 24, dec.channels, retry)
                if (dop != null && dop.exact && dop.hasHardwareVolume && dop.channels >= dec.channels) {
                    dec.useDop = true
                    return useUsb(dop, dec)
                }
                dop?.close()
                outputManager.onUsbDirectClosed()
            }
        }

        // PCM remastered to DSD: DSP runs first, volume included, then the modulator feeds DoP
        if (settings.dsdRemaster && usbProfile.allowDop) {
            val dsdRate = DsdRemaster.dsdRateFor(settings.remasterMultiple, dec.sampleRate, settings.remasterAllow48k)
            val usb = outputManager.openUsbDirect(dsdRate / 16, 24, 2, retry)
            if (usb != null && usb.exact && usb.channels >= 2) {
                useDspPath(dec)
                usbOutput = usb
                remaster = DsdRemaster(dec.sampleRate, dsdRate, 2, settings.remasterGainDb)
                applyOutputProfile()
                return
            }
            usb?.close()
            outputManager.onUsbDirectClosed()
        }

        // 1. Own USB Audio driver: exclusive, any Android version, DAC clocked at the track's rate
        val usb = outputManager.openUsbDirect(dec.sampleRate, dec.bitDepth, dec.channels, retry, allowResample = false)
            ?: if (settings.usbResample) outputManager.openUsbDirect(dec.sampleRate, dec.bitDepth, dec.channels, retry, allowResample = true) else null
        if (usb != null) return useUsb(usb, dec)

        useDspPath(dec)
        val sampleRate = dec.sampleRate
        val bitDepth = dec.bitDepth

        // 2. Native drivers when chosen
        val api = when (outputManager.selectedDriver.value) {
            AudioDriverType.AAUDIO -> NativeAudioOutput.AAUDIO
            AudioDriverType.OPENSL_ES -> NativeAudioOutput.OPENSL
            else -> 0
        }
        if (api != 0) {
            nativeOut = NativeAudioOutput.open(api, sampleRate, 2)
            if (nativeOut != null) {
                outputEncoding = AudioFormat.ENCODING_PCM_FLOAT
                applyOutputProfile()
                return
            }
            android.util.Log.w(TAG, "Native output $api refused $sampleRate Hz; using AudioTrack")
        }

        // 3. Android 14+ bit-perfect mixer, 4. normal mixed output
        val bitPerfect = outputManager.prepareOutput(mediaAttributes, sampleRate, bitDepth)
        outputEncoding = bitPerfect?.encoding ?: AudioFormat.ENCODING_PCM_FLOAT
        audioEngine.bitPerfect = bitPerfect != null
        if (bitPerfect != null) {
            audioEngine.bypassSoftwareVolume = false
            resampler = null
        }

        var trackRate = sampleRate
        var minBuf = AudioTrack.getMinBufferSize(trackRate, AudioFormat.CHANNEL_OUT_STEREO, outputEncoding)
        if (minBuf <= 0 && bitPerfect == null) {
            trackRate = 48000
            minBuf = AudioTrack.getMinBufferSize(trackRate, AudioFormat.CHANNEL_OUT_STEREO, outputEncoding)
            if (minBuf > 0) {
                resampler = Resampler(sampleRate, trackRate, 2)
            }
        }

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(mediaAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(outputEncoding)
                        .setSampleRate(trackRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(4096) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            if (trackRate != 48000) {
                trackRate = 48000
                resampler = Resampler(sampleRate, trackRate, 2)
                outputEncoding = AudioFormat.ENCODING_PCM_FLOAT
                val fallbackBuf = AudioTrack.getMinBufferSize(trackRate, AudioFormat.CHANNEL_OUT_STEREO, outputEncoding).coerceAtLeast(4096)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(mediaAttributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(outputEncoding)
                            .setSampleRate(trackRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(fallbackBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                throw e
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            outputManager.bitPerfectDevice?.let { audioTrack?.setPreferredDevice(it) }
        }
        applyOutputProfile()
    }

    private val _currentOutput = MutableStateFlow(OutputDevice.Speaker)
    /** Device audio is going to now; its profile (if any) is applied. */
    val currentOutput: StateFlow<OutputDevice> = _currentOutput.asStateFlow()
    private val _bluetoothCodecResult = MutableStateFlow<String?>(null)
    val bluetoothCodecResult: StateFlow<String?> = _bluetoothCodecResult.asStateFlow()
    private var lastBtRequest = 0

    private fun detectOutput(): OutputDevice {
        if (usbOutput != null) return OutputDevice.usb(outputManager.attachedUsbName())
        audioTrack?.routedDevice?.let { return OutputDevice.of(it) }
        return OutputDevice.guess(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
    }

    /** Applies the current device's overrides to the engine, and the Bluetooth codec request if set. */
    fun applyOutputProfile() {
        val device = detectOutput()
        _currentOutput.value = device
        val p = store.profiles[device.key] ?: OutputProfile()
        audioEngine.outputAllowsDvc = !p.noDvc && !(device.bluetooth && settings.noDvcOnBluetooth)
        audioEngine.outputAllowsEq = !p.noEqTone
        audioEngine.outputHeadroom = !p.noHeadroom
        val btRate = when (val r = settings.btCodecRate) {
            0 -> 0
            -1 -> decoder?.sampleRate?.let { sr -> if (sr in listOf(44100, 48000, 88200, 96000)) sr else if (sr % 44100 == 0) 88200 else 96000 } ?: 0
            else -> r
        }
        if (device.bluetooth && btRate != 0 && btRate != lastBtRequest) {
            lastBtRequest = btRate
            BluetoothCodec.request(this, btRate) { _bluetoothCodecResult.value = it }
        }
        if (!device.bluetooth) lastBtRequest = 0
    }

    /** Stores (or with null, removes) the overrides for [device]. Output-path options apply from the next track. */
    fun setProfile(device: OutputDevice, profile: OutputProfile?) {
        if (profile == null) store.profiles.remove(device.key) else store.profiles[device.key] = profile
        applyOutputProfile()
    }

    fun profileFor(device: OutputDevice): OutputProfile? = store.profiles[device.key]

    fun saveSettings() = store.save(audioEngine, settings, outputManager.selectedDriver.value)

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val focusRequest by lazy {
        android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttributes)
            .setOnAudioFocusChangeListener(this)
            .build()
    }
    private var resumeOnFocusGain = false
    private var volumeBeforeDuck = Float.NaN

    fun play() {
        val track = _currentTrack.value ?: return
        if (decoder == null) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        _isPlaying.value = true
        pauseGen++ // cancels a pending fade-out pause
        fadingOut = false
        startForeground(NOTIFICATION_ID, buildNotification(track, true))
        if (fadeApplies()) { audioEngine.fadeTo(0f, 0); audioEngine.fadeTo(1f, settings.fadeMs) } else audioEngine.fadeTo(1f, 0)

        audioTrack?.play()
        nativeOut?.start()
        startAudioLoop()
        startProgressTracker()
    }

    fun resume() = play()

    private var lastStamp: android.media.AudioTimestamp? = null
    private var lastMeasuredHz: Double? = null

    /** Live output description for the HUD. Cheap; call a few times a second at most. */
    fun outputClock(): OutputClock {
        val dec = decoder ?: return OutputClock.Idle
        val track = _currentTrack.value
        val source = when {
            dec is DsdDecoder -> "DSD${dec.dsdRate / 44100}"
            else -> "${track?.format ?: "PCM"} ${dec.bitDepth}/${formatKhz(dec.sampleRate)}"
        }
        usbOutput?.let { usb ->
            val rm = remaster
            val mode = when {
                rm != null -> "PCM→DSD${rm.dsdRate / 44100} DoP"
                dec is DsdDecoder && dec.nativeDsd -> "Native DSD"
                dec is DsdDecoder && dec.useDop -> "DoP"
                dec is DsdDecoder -> "DSD→PCM"
                resampler != null -> "Resampled"
                else -> "PCM"
            }
            return OutputClock(source, "USB ${usb.classLabel} direct", usb.sampleRate, usb.measuredRateHz,
                usb.alt.bitResolution, usb.channels, "${usb.syncLabel} · ${usb.busLabel}", usb.exact && rm == null, mode)
        }
        nativeOut?.let { n ->
            return OutputClock(source, "${n.label} output", n.sampleRate, null, 32, 2, "System clock", false, "DSP 64-bit")
        }
        val at = audioTrack ?: return OutputClock.Idle
        // Measured consumption rate from successive hardware timestamps
        val stamp = android.media.AudioTimestamp()
        if (at.getTimestamp(stamp)) {
            lastStamp?.let { prev ->
                val dn = stamp.nanoTime - prev.nanoTime
                if (dn > 200_000_000L) lastMeasuredHz = (stamp.framePosition - prev.framePosition) * 1e9 / dn
            }
            if (lastStamp == null || stamp.nanoTime - lastStamp!!.nanoTime > 200_000_000L) lastStamp = stamp
        }
        val bitPerfect = audioEngine.bitPerfect
        return OutputClock(source, if (bitPerfect) "Android bit-perfect mixer" else "Android mixer", at.sampleRate,
            lastMeasuredHz?.takeIf { _isPlaying.value }, AudioOutputManager.precisionBits(outputEncoding), 2,
            "System clock", bitPerfect, if (bitPerfect) "PCM" else "DSP 64-bit")
    }

    private fun formatKhz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.US, hz / 1000.0)

    /** Plays [track] from the current queue, or as a one-track queue if it isn't queued. */
    fun playTrack(track: Track) {
        val idx = _queue.value.indexOfFirst { it.id == track.id }
        if (idx >= 0) {
            _currentQueueIndex.value = idx
            loadTrack(track, autoPlay = true)
        } else {
            setQueue(listOf(track))
        }
    }

    /** Reopens the output so the new driver (e.g. bit-perfect USB) takes effect on the current track. */
    fun switchAudioDriver(driver: AudioDriverType) {
        com.clashplayer.diagnostics.DiagnosticsManager.activeDriverName = driver.name
        com.clashplayer.diagnostics.DiagnosticsManager.audio("Playback", "Output driver switched to ${driver.name}")
        outputManager.selectDriver(driver)
        reopenOutput()
    }

    /** Reopens the output for the current track with the selected driver, keeping play state. */
    private fun reopenOutput() {
        val dec = decoder ?: return
        val wasPlaying = _isPlaying.value
        if (wasPlaying) pause()
        runBlocking { playbackJob?.cancelAndJoin() }
        initOutput(dec)
        if (wasPlaying) play()
    }

    fun pause() {
        val job = playbackJob
        _isPlaying.value = false
        progressJob?.cancel()
        _currentTrack.value?.let {
            startForeground(NOTIFICATION_ID, buildNotification(it, false))
        }
        if (fadeApplies() && job?.isActive == true) {
            // The loop plays the fade-out; then stop() lets the AudioTrack drain it instead of cutting it off
            fadingOut = true
            audioEngine.fadeTo(0f, settings.fadeMs)
            val gen = ++pauseGen
            serviceScope.launch(Dispatchers.Main) {
                withTimeoutOrNull(settings.fadeMs + 500L) { job.join() }
                if (gen == pauseGen && !_isPlaying.value) { fadingOut = false; job.cancel(); audioTrack?.stop(); nativeOut?.pause() }
            }
        } else {
            fadingOut = false
            job?.cancel()
            audioTrack?.pause()
            nativeOut?.pause()
        }
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val nextIdx = nextIndex(auto = false) ?: _currentQueueIndex.value
        _currentQueueIndex.value = nextIdx
        loadTrack(q[nextIdx], autoPlay = true)
    }

    fun previous() {
        val q = _queue.value
        if (q.isEmpty()) return
        val prevIdx = (_currentQueueIndex.value - 1).coerceAtLeast(0)
        _currentQueueIndex.value = prevIdx
        loadTrack(q[prevIdx], autoPlay = true)
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, _durationMs.value)
        _currentPositionMs.value = target
        if (_isPlaying.value) {
            pendingSeekMs = target
        } else {
            runBlocking { playbackJob?.cancelAndJoin() }
            decoder?.seekTo(target) // loop is stopped, so this thread may touch the decoder
            audioTrack?.flush()
            nativeOut?.flush()
            usbOutput?.flush()
            remaster?.reset()
            resampler?.reset()
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMinutes.value = minutes

        sleepTimerJob = serviceScope.launch {
            var remaining = minutes
            while (remaining > 0) {
                delay(60000L)
                remaining--
                _sleepTimerRemainingMinutes.value = remaining
            }
            // Smooth fade out
            val startVol = audioEngine.volume
            for (step in 10 downTo 0) {
                audioEngine.volume = startVol * (step / 10.0f)
                delay(100L)
            }
            pause()
            audioEngine.volume = startVol
            _sleepTimerRemainingMinutes.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMinutes.value = null
    }

    private fun startAudioLoop() {
        playbackJob?.cancel()
        playbackJob = serviceScope.launch(Dispatchers.Default) {
            AudioOutputManager.setAudioThreadPriority()
            val bufferSize = 2048
            val floatBuffer = FloatArray(bufferSize)
            val pcmBuffer = java.nio.ByteBuffer.allocateDirect(bufferSize * 4 * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var dec = decoder ?: return@launch
            var stretched = FloatArray(bufferSize * 4)
            var resampled = FloatArray(bufferSize * 4)
            var dsdBuffer = FloatArray(bufferSize * 16)
            val rawBuffer = ByteArray(16384)
            val zeros = FloatArray(bufferSize)
            // Crossfade: the outgoing track keeps playing under the incoming one, which is already the current track
            var outgoing: PullBuffer? = null
            var xfadeFrames = 0
            var xfadeDone = 0
            var outgoingGain = 1f
            val mixBuffer = FloatArray(bufferSize)

            /** Sends [n] processed samples to the active output; false if the output died. */
            fun emit(src: FloatArray, n: Int): Boolean {
                var data = src
                var count = n
                val ch = dec.outChannels
                resampler?.let { r ->
                    val need = r.maxOutFrames(n / ch) * ch
                    if (resampled.size < need) resampled = FloatArray(need)
                    count = r.process(src, n, resampled)
                    data = resampled
                }
                if (count == 0) return true
                val rm = remaster
                if (rm != null) {
                    val need = rm.maxOut(count)
                    if (dsdBuffer.size < need) dsdBuffer = FloatArray(need)
                    count = rm.process(data, count, dsdBuffer)
                    data = dsdBuffer
                    if (count == 0) return true
                }
                nativeOut?.let { out ->
                    return try { out.write(data, count); true } catch (e: java.io.IOException) {
                        android.util.Log.w(TAG, "Native output failed", e)
                        com.clashplayer.diagnostics.DiagnosticsManager.w("Playback", "Native output write failed: ${e.message}", e)
                        false
                    }
                }
                val usb = usbOutput
                if (usb != null) {
                    // Untouched samples go out as they are; scaled or resampled ones get dithered to the DAC's depth
                    if (rm == null && (resampler != null || (audioEngine.bypassSoftwareVolume && audioEngine.volumeDb() != 0f))) {
                        audioEngine.ditherer.process(data, count, ch, usb.alt.bitResolution, usb.sampleRate)
                    }
                    return try {
                        // Remastered DSD already carries the volume; keep the DAC at unity
                        if (usb.hasHardwareVolume) usb.setVolumeDb(if (rm != null) 0f else audioEngine.volumeDb())
                        usb.write(data, count, if (rm != null) 2 else ch)
                        true
                    } catch (e: java.io.IOException) {
                        // DAC unplugged or stalled: reopen on whatever output is left, from outside this job
                        android.util.Log.w(TAG, "USB output failed", e)
                        com.clashplayer.diagnostics.DiagnosticsManager.w("Playback", "USB direct write failed: ${e.message}", e)
                        outputManager.usbDirectSuspended = true
                        serviceScope.launch(Dispatchers.Main) { reopenOutput() }
                        false
                    }
                }
                // Output to AudioTrack in the encoding it was opened with
                if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    audioTrack?.write(data, 0, count, AudioTrack.WRITE_BLOCKING)
                } else {
                    packPcm(data, count, AudioOutputManager.precisionBits(outputEncoding), pcmBuffer)
                    audioTrack?.write(pcmBuffer, pcmBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                }
                val at = audioTrack
                if (at != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    val underruns = at.underrunCount
                    if (underruns > 0) {
                        com.clashplayer.diagnostics.DiagnosticsManager.underrunCount = underruns.toLong()
                    }
                }
                return true
            }

            fun silence(ms: Int): Boolean {
                val ch = dec.outChannels
                var frames = (dec.sampleRate.toLong() * ms / 1000).toInt()
                val chunk = bufferSize - (bufferSize % ch)
                while (frames > 0 && isActive) {
                    val n = minOf(frames, chunk / ch)
                    zeros.fill(0f, 0, n * ch) // emit may dither in place
                    if (!emit(zeros, n * ch)) return false
                    frames -= n
                }
                return true
            }

            try {
                if (pendingWarmupMs > 0) { val ms = pendingWarmupMs; pendingWarmupMs = 0; if (!silence(ms)) return@launch }
                while (isActive && (_isPlaying.value || fadingOut)) {
                    val seek = pendingSeekMs
                    if (seek >= 0) {
                        pendingSeekMs = -1L
                        dec.seekTo(seek)
                        audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()
                        nativeOut?.let { it.pause(); it.flush(); it.start() }
                        usbOutput?.flush() // drop queued packets so the new position is heard at once
                        resampler?.reset()
                        remaster?.reset()
                        audioEngine.tempoProcessor.reset()
                        outgoing?.close(); outgoing = null; xfadeFrames = 0
                        if (fadeApplies()) { audioEngine.fadeTo(0f, 0); audioEngine.fadeTo(1f, settings.seekFadeMs) }
                    }

                    // Crossfade starts once the remaining time fits in the crossfade length
                    val xfMs = settings.crossfadeMs
                    if (xfadeFrames == 0 && xfMs > 0 && !audioEngine.bitPerfect && dec.durationMs > 0) {
                        val remaining = dec.durationMs - dec.positionUs / 1000
                        if (remaining in 1..xfMs.toLong()) {
                            val oldGain = audioEngine.replayGainDb
                            val next = openNext(dec)
                            if (next != null) {
                                outgoing = PullBuffer(dec)
                                xfadeFrames = (dec.sampleRate * remaining / 1000).toInt().coerceAtLeast(1)
                                xfadeDone = 0
                                dec = next.first
                                adopt(next.first, next.second, closeOld = null)
                                // The engine now applies the new track's ReplayGain; keep the old track at its own
                                outgoingGain = 10f.pow((oldGain - audioEngine.replayGainDb) / 20f)
                            } else {
                                xfadeFrames = -1 // no compatible next track: don't retry every buffer
                            }
                        }
                    }

                    val rawDsd = (dec as? DsdDecoder)?.nativeDsd == true
                    val count = if (rawDsd) (dec as DsdDecoder).readRaw(rawBuffer) else dec.read(floatBuffer)
                    if (count < 0) {
                        // Gapless: same output format, so hand the open output straight to the next track
                        val next = if (settings.gapless) openNext(dec) else null
                        if (next != null) {
                            val old = dec
                            dec = next.first
                            adopt(next.first, next.second, closeOld = old)
                            if (xfadeFrames < 0) xfadeFrames = 0
                            if (settings.silenceMs > 0 && !silence(settings.silenceMs)) break
                            continue
                        }
                        // End of track: advance from outside this job, since loadTrack joins it (a child would deadlock)
                        serviceScope.launch(Dispatchers.Main) { onTrackEnded() }
                        break
                    }
                    if (count == 0) continue

                    if (rawDsd) {
                        val usb = usbOutput ?: break
                        try {
                            usb.setVolumeDb(audioEngine.volumeDb())
                            usb.writeRaw(rawBuffer, count)
                        } catch (e: java.io.IOException) {
                            android.util.Log.w(TAG, "USB output failed", e)
                            outputManager.usbDirectSuspended = true
                            serviceScope.launch(Dispatchers.Main) { reopenOutput() }
                            break
                        }
                        continue
                    }

                    if (xfadeFrames > 0) {
                        val got = outgoing?.take(mixBuffer, count) ?: 0
                        if (got < count) java.util.Arrays.fill(mixBuffer, got, count, 0f)
                        var i = 0
                        while (i < count - 1) {
                            val t = ((xfadeDone + i / 2).toFloat() / xfadeFrames).coerceAtMost(1f) * (PI.toFloat() / 2)
                            val gOut = cos(t) * outgoingGain
                            val gIn = sin(t)
                            floatBuffer[i] = floatBuffer[i] * gIn + mixBuffer[i] * gOut
                            floatBuffer[i + 1] = floatBuffer[i + 1] * gIn + mixBuffer[i + 1] * gOut
                            i += 2
                        }
                        xfadeDone += count / 2
                        if (got < count) { outgoing?.close(); outgoing = null }
                        if (xfadeDone >= xfadeFrames) { outgoing?.close(); outgoing = null; xfadeFrames = 0 }
                    }

                    var data = floatBuffer
                    var n = count
                    if (!audioEngine.bitPerfect) {
                        // Tempo changes the sample count, so it runs ahead of the in-place DSP chain
                        val tp = audioEngine.tempoProcessor
                        val need = tp.maxOut(count)
                        if (stretched.size < need) stretched = FloatArray(need)
                        n = tp.process(floatBuffer, count, stretched)
                        data = stretched
                    }
                    if (n == 0) continue

                    // 64-bit float DSP pipeline execution (a no-op in bit-perfect mode)
                    audioEngine.processInterleavedStereo(data, 0, n)
                    if (!emit(data, n)) break

                    if (fadingOut && audioEngine.fadeDone && !_isPlaying.value) break
                }
            } finally {
                outgoing?.close()
            }
        }
    }

    /** Pulls exact sample counts from a decoder whose reads return whatever fits. */
    private class PullBuffer(private val dec: AudioDecoder) {
        private val chunk = FloatArray(2048)
        private var len = 0
        private var pos = 0
        private var ended = false

        fun take(dst: FloatArray, n: Int): Int {
            var got = 0
            while (got < n && !ended) {
                if (pos == len) {
                    val r = dec.read(chunk)
                    if (r < 0) ended = true else { len = r; pos = 0 }
                    continue
                }
                val k = minOf(n - got, len - pos)
                System.arraycopy(chunk, pos, dst, got, k)
                got += k
                pos += k
            }
            return got
        }

        fun close() = dec.close()
    }

    private fun fadeApplies() = settings.fades && !audioEngine.bitPerfect

    /** Queue entry to play next; null to stop (end of queue with repeat off). */
    private fun nextIndex(auto: Boolean): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val cur = _currentQueueIndex.value
        if (auto && repeatMode == REPEAT_ONE) return cur
        if (isShuffle && q.size > 1) return (q.indices - cur).random()
        return if (cur + 1 < q.size) cur + 1 else if (repeatMode == REPEAT_ALL) 0 else null
    }

    /**
     * Opens the next track if it can continue on the output as it is now open: same rate, and for bit-perfect
     * output the same channels and depth too. Runs on the audio thread.
     */
    private fun openNext(cur: AudioDecoder): Pair<AudioDecoder, Int>? {
        val idx = nextIndex(auto = true) ?: return null
        val next = runCatching { openDecoder(_queue.value[idx].uri) }.getOrNull() ?: return null
        val fits = next.sampleRate == cur.sampleRate && (!audioEngine.bitPerfect || (next.javaClass == cur.javaClass &&
            next.channels == cur.channels && next.bitDepth == cur.bitDepth &&
            (next !is DsdDecoder || next.dsdRate == (cur as DsdDecoder).dsdRate)))
        if (!fits) { next.close(); return null }
        next.outChannels = cur.outChannels
        if (next is DsdDecoder) { next.useDop = (cur as DsdDecoder).useDop; next.nativeDsd = cur.nativeDsd }
        return next to idx
    }

    /** Makes [next] the current track without touching the output. */
    private fun adopt(next: AudioDecoder, idx: Int, closeOld: AudioDecoder?) {
        decoder = next
        closeOld?.close()
        val track = _queue.value[idx]
        _currentQueueIndex.value = idx
        _currentTrack.value = track
        _durationMs.value = if (next.durationMs > 0) next.durationMs else track.durationMs
        _currentPositionMs.value = 0L
        _currentLyrics.value = null
        audioEngine.replayGainDb = replayGainFor(track)
        serviceScope.launch(Dispatchers.Main) { if (_isPlaying.value) startForeground(NOTIFICATION_ID, buildNotification(track, true)) }
    }

    private fun replayGainFor(track: Track): Float {
        val s = settings
        if (s.replayGain == ReplayGainMode.OFF) return 0f
        val info = runCatching {
            contentResolver.openFileDescriptor(track.uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    ReplayGainInfo.read { at, n ->
                        val b = java.nio.ByteBuffer.allocate(n.coerceIn(0, 1 shl 24))
                        ch.read(b, at)
                        b.array().copyOf(b.position())
                    }
                }
            }
        }.getOrNull() ?: ReplayGainInfo()
        return info.gainDb(s.replayGain, s.replayGainPreampDb, s.untaggedGainDb, s.preventClipping)
    }

    /** Re-reads the current track's ReplayGain after a settings change. */
    fun refreshReplayGain() {
        audioEngine.replayGainDb = _currentTrack.value?.let(::replayGainFor) ?: 0f
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive && _isPlaying.value) {
                delay(200L)
                // Decoder time leads what is audible by the AudioTrack buffer; fine for a progress bar
                if (pendingSeekMs < 0) decoder?.let { _currentPositionMs.value = it.positionUs / 1000 }
            }
        }
    }

    private fun onTrackEnded() {
        val idx = nextIndex(auto = true) ?: return pause()
        _currentQueueIndex.value = idx
        loadTrack(_queue.value[idx], autoPlay = true)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (settings.ignoreShortFocusLoss) return
                resumeOnFocusGain = _isPlaying.value
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (settings.ignoreShortFocusLoss) return
                if (settings.pauseInsteadOfDuck) {
                    resumeOnFocusGain = _isPlaying.value
                    pause()
                    return
                }
                // Restore the user's own volume afterwards, not a hardcoded one
                if (volumeBeforeDuck.isNaN()) volumeBeforeDuck = audioEngine.volume
                audioEngine.volume = volumeBeforeDuck * 0.2f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!volumeBeforeDuck.isNaN()) {
                    audioEngine.volume = volumeBeforeDuck
                    volumeBeforeDuck = Float.NaN
                }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(track: Track, playing: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(playing)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        try { contentResolver.unregisterContentObserver(volumeObserver) } catch (_: Throwable) {}
        runBlocking { playbackJob?.cancelAndJoin() } // the loop may be inside a USB write
        saveSettings()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        closeUsbOutput()
        nativeOut?.close()
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        serviceScope.cancel()
        audioTrack?.release()
        decoder?.close()
        audioManager.abandonAudioFocusRequest(focusRequest)
        outputManager.releaseBitPerfect(mediaAttributes)
    }

    companion object {
        const val CHANNEL_ID = "clash_playback_channel"
        const val NOTIFICATION_ID = 101

        private const val TAG = "PlaybackService"
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2
    }
}
