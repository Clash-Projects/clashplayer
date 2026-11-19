package com.clashplayer.service

import android.media.AudioDeviceInfo
import org.json.JSONObject

/** The output audio is going to right now. [key] identifies it across sessions. */
data class OutputDevice(val key: String, val label: String, val bluetooth: Boolean = false) {
    companion object {
        val Speaker = OutputDevice("speaker", "Phone speaker")

        fun of(info: AudioDeviceInfo?): OutputDevice {
            val name = info?.productName?.toString()?.takeIf { it.isNotBlank() }
            return when (info?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                    OutputDevice("bt:${name ?: "bluetooth"}", name ?: "Bluetooth", bluetooth = true)
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> usb(name)
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputDevice("wired", "Wired headphones")
                null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Speaker
                else -> OutputDevice("other:${info.type}", name ?: "Other output")
            }
        }

        fun usb(name: String?) = OutputDevice("usb:${name ?: "dac"}", name ?: "USB DAC")

        /** Most likely active output among [devices], by Android's own routing priority for media. */
        fun guess(devices: Array<AudioDeviceInfo>): OutputDevice {
            val order = listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            return of(order.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } })
        }
    }
}

/** Overrides for one output device. Absent profile = global settings. */
data class OutputProfile(
    val noDvc: Boolean = false,
    val noEqTone: Boolean = false,
    val noHeadroom: Boolean = false,
    val allowDop: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("noDvc", noDvc).put("noEqTone", noEqTone).put("noHeadroom", noHeadroom).put("allowDop", allowDop)

    companion object {
        fun fromJson(o: JSONObject) = OutputProfile(o.optBoolean("noDvc"), o.optBoolean("noEqTone"), o.optBoolean("noHeadroom"), o.optBoolean("allowDop", true))
    }
}
