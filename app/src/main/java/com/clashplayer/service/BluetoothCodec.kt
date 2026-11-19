package com.clashplayer.service

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Asks the connected A2DP headset to run its codec at a given sample rate. Android only exposes this to
 * privileged apps, so on most phones the request is refused; [request] reports exactly what happened.
 */
object BluetoothCodec {

    /** Calls [done] (on a binder thread) with a one-line result. */
    fun request(context: Context, rateHz: Int, done: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return done("Needs Android 13 or later")
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return done("Bluetooth permission not granted")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return done("No Bluetooth")
        val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val a2dp = proxy as BluetoothA2dp
                try {
                    val device = a2dp.connectedDevices.firstOrNull() ?: return done("No Bluetooth headset connected")
                    done(apply(a2dp, device, rateHz))
                } catch (e: Exception) {
                    // Reflection wraps the real failure
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    done(when (cause) {
                        is SecurityException -> "Refused by Android: codec changes need system privileges on this phone"
                        is NoSuchMethodException -> "Not available to apps on this Android version"
                        else -> "Not supported: ${cause.message}"
                    })
                } finally {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        if (!ok) done("Bluetooth audio service unavailable")
    }

    private fun apply(a2dp: BluetoothA2dp, device: BluetoothDevice, rateHz: Int): String {
        val rate = when (rateHz) {
            44100 -> BluetoothCodecConfig.SAMPLE_RATE_44100
            48000 -> BluetoothCodecConfig.SAMPLE_RATE_48000
            88200 -> BluetoothCodecConfig.SAMPLE_RATE_88200
            96000 -> BluetoothCodecConfig.SAMPLE_RATE_96000
            else -> return "Unsupported rate $rateHz"
        }
        // Both calls are system APIs, reached by name
        val status = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device) as? BluetoothCodecStatus
            ?: return "Headset reported no codec"
        val cur = status.codecConfig ?: return "Headset reported no codec"
        if (status.codecsSelectableCapabilities.none { it.codecType == cur.codecType && it.sampleRate and rate != 0 }) {
            return "${codecName(cur.codecType)} on this headset can't run at ${rateHz / 1000.0} kHz"
        }
        val config = BluetoothCodecConfig.Builder()
            .setCodecType(cur.codecType)
            .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
            .setSampleRate(rate)
            .setBitsPerSample(cur.bitsPerSample)
            .setChannelMode(cur.channelMode)
            .setCodecSpecific1(cur.codecSpecific1)
            .build()
        a2dp.javaClass.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, BluetoothCodecConfig::class.java).invoke(a2dp, device, config)
        return "${codecName(cur.codecType)} set to ${rateHz / 1000.0} kHz"
    }

    private fun codecName(type: Int) = when (type) {
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
        else -> "Codec"
    }
}
