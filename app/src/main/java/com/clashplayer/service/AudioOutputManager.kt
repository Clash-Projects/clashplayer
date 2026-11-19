package com.clashplayer.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.clashplayer.usb.UsbAudioOutput
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioOutputManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _selectedDriver = MutableStateFlow(AudioDriverType.BIT_PERFECT_DIRECT)
    val selectedDriver: StateFlow<AudioDriverType> = _selectedDriver.asStateFlow()

    private val _activeSampleRate = MutableStateFlow(44100)
    val activeSampleRate: StateFlow<Int> = _activeSampleRate.asStateFlow()

    private val _activeBitDepth = MutableStateFlow(24)
    val activeBitDepth: StateFlow<Int> = _activeBitDepth.asStateFlow()

    private val _isBitPerfectActive = MutableStateFlow(false)
    val isBitPerfectActive: StateFlow<Boolean> = _isBitPerfectActive.asStateFlow()

    private val _currentDeviceDescription = MutableStateFlow("Speaker")
    val currentDeviceDescription: StateFlow<String> = _currentDeviceDescription.asStateFlow()

    // Set after a USB stream failure so a misbehaving DAC can't cause an endless reopen loop
    @Volatile var usbDirectSuspended = false

    fun selectDriver(driver: AudioDriverType) {
        usbDirectSuspended = false
        _selectedDriver.value = driver
        updateHardwareState()
    }

    fun onTrackFormatChanged(sampleRate: Int, bitDepth: Int) {
        _activeSampleRate.value = sampleRate
        _activeBitDepth.value = bitDepth
        updateHardwareState()
    }

    fun updateHardwareState() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var hasUsbDac = false
        var deviceName = "Speaker"

        for (d in devices) {
            when (d.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    hasUsbDac = true
                    deviceName = "USB DAC / Hi-Res External"
                }
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    deviceName = "Wired Headset (3.5mm / Type-C)"
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    deviceName = "Bluetooth (LDAC / aptX HD / AAC)"
                }
            }
        }

        _currentDeviceDescription.value = deviceName
        if (!hasUsbDac) _isBitPerfectActive.value = false
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var onUsbPermission: (() -> Unit)? = null
    private var permissionReceiverRegistered = false
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            permissionPending = false
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) onUsbPermission?.invoke()
        }
    }

    /**
     * Takes over an attached USB DAC with the app's own USB Audio driver (any Android version), clocking it at the
     * track's exact rate. Returns null if bit-perfect isn't selected, no DAC is attached, permission is still pending
     * ([onPermission] runs once the user grants it), or the DAC can't play this rate.
     */
    fun openUsbDirect(sampleRate: Int, bitDepth: Int, channels: Int, onPermission: () -> Unit): UsbAudioOutput? {
        if (_selectedDriver.value != AudioDriverType.BIT_PERFECT_DIRECT || usbDirectSuspended) return null
        val dac: UsbDevice = usbManager.deviceList.values.firstOrNull(UsbAudioOutput::isUsbAudio) ?: return null
        if (!usbManager.hasPermission(dac)) {
            requestUsbPermission(dac, onPermission)
            return null
        }
        return try {
            UsbAudioOutput.open(usbManager, dac, sampleRate, bitDepth, channels).also {
                _isBitPerfectActive.value = it.exact
                _currentDeviceDescription.value = "${dac.productName ?: "USB DAC"} - ${it.description}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "USB direct output unavailable: ${e.message}")
            null
        }
    }

    fun onUsbDirectClosed() {
        _isBitPerfectActive.value = false
    }

    private var permissionPending = false

    private fun requestUsbPermission(dac: UsbDevice, onGranted: () -> Unit) {
        onUsbPermission = onGranted
        if (permissionPending) return // one system dialog at a time
        permissionPending = true
        if (!permissionReceiverRegistered) {
            ContextCompat.registerReceiver(context, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            permissionReceiverRegistered = true
        }
        // Mutable: the system adds the device and grant result as extras
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        usbManager.requestPermission(dac, pi)
    }

    private var bitPerfectDevice: AudioDeviceInfo? = null

    /**
     * Ask the system for a bit-perfect USB mixer at the track's exact rate (Android 14+).
     * Returns the format the AudioTrack must be opened with, or null to use the normal mixed path.
     * Internal outputs always go through the system mixer, so only a USB DAC qualifies.
     */
    fun prepareOutput(attrs: AudioAttributes, sampleRate: Int, bitDepth: Int): AudioFormat? {
        releaseBitPerfect(attrs)
        if (_selectedDriver.value != AudioDriverType.BIT_PERFECT_DIRECT || Build.VERSION.SDK_INT < 34) return null
        val usb = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: return null
        // A different rate would be resampled, so only an exact match counts as bit-perfect
        val candidates = audioManager.getSupportedMixerAttributes(usb).filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == sampleRate &&
                it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO &&
                precisionBits(it.format.encoding) >= bitDepth
        }
        // Narrowest container that still holds the source bits
        val pick = candidates.minByOrNull { precisionBits(it.format.encoding) } ?: return null
        if (!audioManager.setPreferredMixerAttributes(attrs, usb, pick)) return null
        bitPerfectDevice = usb
        _isBitPerfectActive.value = true
        return pick.format
    }

    fun releaseBitPerfect(attrs: AudioAttributes) {
        val dev = bitPerfectDevice ?: return
        if (Build.VERSION.SDK_INT >= 34) audioManager.clearPreferredMixerAttributes(attrs, dev)
        bitPerfectDevice = null
        _isBitPerfectActive.value = false
    }

    companion object {
        private const val TAG = "AudioOutputManager"
        private const val ACTION_USB_PERMISSION = "com.clashplayer.USB_PERMISSION"

        /** Integer bits an encoding carries exactly; 0 for encodings the render loop can't write. */
        fun precisionBits(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_FLOAT -> 24 // float32 mantissa
            AudioFormat.ENCODING_PCM_32BIT -> 32
            else -> 0
        }
    }
}
