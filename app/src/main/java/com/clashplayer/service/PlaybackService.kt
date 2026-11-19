package com.clashplayer.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.packPcm
import com.clashplayer.model.Lyrics
import com.clashplayer.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

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

    var crossfadeDurationSec: Float = 2.0f
    var gaplessEnabled: Boolean = true

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerRemainingMinutes: StateFlow<Int?> = _sleepTimerRemainingMinutes.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        outputManager = AudioOutputManager(this)
        createNotificationChannel()
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

    private var decoder: AudioDecoder? = null
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
            openDecoder(track.uri)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot decode ${track.uri}", e)
            _isPlaying.value = false
            return
        }
        decoder = dec
        // MediaStore often has no duration for DSD; the decoder's own length is authoritative when known
        if (dec.durationMs > 0) _durationMs.value = dec.durationMs
        _currentLyrics.value = null // no lyrics source yet; never show placeholder text as if it were real

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
        // Pass every channel through when the DAC has room for them; otherwise a stereo downmix
        if (!(dec is DsdDecoder && dec.useDop)) dec.outChannels = if (usb.channels >= dec.channels) dec.channels else 2
        audioEngine.bitPerfect = true
        audioEngine.bypassSoftwareVolume = !usb.hasHardwareVolume
        audioEngine.visualizerFeed = !(dec is DsdDecoder && dec.useDop)
        audioEngine.bypassChannels = dec.outChannels
        audioEngine.updateSampleRate(dec.sampleRate)
        outputManager.onTrackFormatChanged(dec.sampleRate, dec.bitDepth)
    }

    private fun initOutput(dec: AudioDecoder) {
        audioTrack?.release()
        audioTrack = null
        closeUsbOutput()
        val retry: () -> Unit = { serviceScope.launch(Dispatchers.Main) { reopenOutput() } }

        // DSD: DoP carries it bit-exact, but needs DSD/16 Hz at >= 24 bits on every channel and the DAC's own
        // volume control (attenuating DoP in software would destroy it). Otherwise convert to PCM.
        if (dec is DsdDecoder) {
            val dop = outputManager.openUsbDirect(dec.dopRate, 24, dec.channels, retry)
            if (dop != null && dop.exact && dop.hasHardwareVolume && dop.channels >= dec.channels) {
                dec.useDop = true
                return useUsb(dop, dec)
            }
            dop?.close()
            outputManager.onUsbDirectClosed()
            dec.useDop = false
        }

        // 1. Own USB Audio driver: exclusive, any Android version, DAC clocked at the track's rate
        outputManager.openUsbDirect(dec.sampleRate, dec.bitDepth, dec.channels, retry)?.let { return useUsb(it, dec) }
        audioEngine.bypassSoftwareVolume = false
        audioEngine.visualizerFeed = true
        audioEngine.bypassChannels = 2
        dec.outChannels = 2 // the DSP chain and AudioTrack path are stereo
        audioEngine.updateSampleRate(dec.sampleRate)
        outputManager.onTrackFormatChanged(dec.sampleRate, dec.bitDepth)
        val sampleRate = dec.sampleRate
        val bitDepth = dec.bitDepth

        // 2. Android 14+ bit-perfect mixer, 3. normal mixed output
        val bitPerfect = outputManager.prepareOutput(mediaAttributes, sampleRate, bitDepth)
        outputEncoding = bitPerfect?.encoding ?: AudioFormat.ENCODING_PCM_FLOAT
        audioEngine.bitPerfect = bitPerfect != null

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, outputEncoding)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(mediaAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(outputEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

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
        startForeground(NOTIFICATION_ID, buildNotification(track, true))

        audioTrack?.play()
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
            val dop = dec is DsdDecoder && dec.useDop
            return OutputClock(source, "USB ${usb.classLabel} direct", usb.sampleRate, usb.measuredRateHz,
                usb.alt.bitResolution, usb.channels, "${usb.syncLabel} · ${usb.busLabel}", usb.exact,
                if (dop) "DoP" else if (dec is DsdDecoder) "DSD→PCM" else "PCM")
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
        _isPlaying.value = false
        audioTrack?.pause()
        playbackJob?.cancel()
        progressJob?.cancel()
        _currentTrack.value?.let {
            startForeground(NOTIFICATION_ID, buildNotification(it, false))
        }
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        var nextIdx = _currentQueueIndex.value + 1
        if (nextIdx >= q.size) {
            nextIdx = if (repeatMode == REPEAT_ALL) 0 else _currentQueueIndex.value
        }
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
            usbOutput?.flush()
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
            val bufferSize = 2048
            val floatBuffer = FloatArray(bufferSize)
            val pcmBuffer = java.nio.ByteBuffer.allocateDirect(bufferSize * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val dec = decoder ?: return@launch

            while (isActive && _isPlaying.value) {
                val seek = pendingSeekMs
                if (seek >= 0) {
                    pendingSeekMs = -1L
                    dec.seekTo(seek)
                    audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()
                    usbOutput?.flush() // drop queued packets so the new position is heard at once
                }
                val count = dec.read(floatBuffer)
                if (count < 0) {
                    // End of track: advance from outside this job, since loadTrack joins it (a child would deadlock)
                    serviceScope.launch(Dispatchers.Main) { onTrackEnded() }
                    break
                }
                if (count == 0) continue

                // 64-bit float DSP pipeline execution (a no-op in bit-perfect mode)
                audioEngine.processInterleavedStereo(floatBuffer, 0, count)

                val usb = usbOutput
                if (usb != null) {
                    try {
                        if (usb.hasHardwareVolume) usb.setVolumeDb(audioEngine.volumeDb())
                        usb.write(floatBuffer, count, dec.outChannels)
                    } catch (e: java.io.IOException) {
                        // DAC unplugged or stalled: reopen on whatever output is left, from outside this job
                        android.util.Log.w(TAG, "USB output failed", e)
                        outputManager.usbDirectSuspended = true
                        serviceScope.launch(Dispatchers.Main) { reopenOutput() }
                        break
                    }
                    continue
                }

                // Output to AudioTrack in the encoding it was opened with
                val bits = AudioOutputManager.precisionBits(outputEncoding)
                if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    audioTrack?.write(floatBuffer, 0, count, AudioTrack.WRITE_BLOCKING)
                } else {
                    packPcm(floatBuffer, count, bits, pcmBuffer)
                    audioTrack?.write(pcmBuffer, pcmBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                }
            }
        }
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
        when {
            repeatMode == REPEAT_ONE -> _currentTrack.value?.let { loadTrack(it, autoPlay = true) }
            repeatMode == REPEAT_ALL || _currentQueueIndex.value < _queue.value.size - 1 -> next()
            else -> pause()
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = _isPlaying.value
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
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
        runBlocking { playbackJob?.cancelAndJoin() } // the loop may be inside a USB write
        closeUsbOutput()
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
