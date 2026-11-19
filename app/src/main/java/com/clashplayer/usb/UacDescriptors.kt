package com.clashplayer.usb

/** Playback-relevant parts of one USB configuration of a USB Audio Class device. */
data class UacDevice(
    val configValue: Int,
    val acInterface: Int,
    val uacVersion: Int, // 1, 2, or 3 (UAC3 class descriptors aren't in the configuration, so its alts are empty)
    val alts: List<UacAlt>,
    val featureUnits: List<UacFeatureUnit>,
    val clockSelectors: Map<Int, List<Int>>, // UAC2 selector id -> its input clock ids
) {
    val playbackAlts get() = alts.filter { !it.isCapture }
    val captureAlts get() = alts.filter { it.isCapture }
}

/** One streaming alternate setting with an isochronous data endpoint (OUT for playback, IN for capture). */
data class UacAlt(
    val interfaceNumber: Int,
    val altSetting: Int,
    val terminalLink: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val endpoint: Int,
    val maxPacketBytes: Int,      // includes the high-bandwidth multiplier
    val interval: Int,            // bInterval
    val syncType: Int,            // 1 async, 2 adaptive, 3 synchronous
    val feedbackEndpoint: Int?,   // explicit feedback IN endpoint, if any
    val feedbackMaxPacket: Int,
    val sampleRates: List<Int>,   // UAC1 discrete rates
    val rateRange: IntRange?,     // UAC1 continuous range
    val clockId: Int?,            // UAC2 clock entity of the linked terminal
    val isPcm: Boolean,
    val isCapture: Boolean = false,
    val implicitFeedbackData: Boolean = false, // capture endpoint flagged as implicit feedback (usage 2)
    val isRawDsd: Boolean = false, // UAC2 type I with bmFormats bit 31 (TYPE_I_RAW_DATA): native DSD
)

/** A feature unit fed by [sourceId], with the channels (0 = master) whose volume / mute are host-settable. */
data class UacFeatureUnit(val id: Int, val sourceId: Int, val volumeChannels: List<Int>, val muteChannels: List<Int> = emptyList()) {
    val hasVolume get() = volumeChannels.isNotEmpty()
    val hasMute get() = muteChannels.isNotEmpty()
}

object UacDescriptors {
    private const val CONFIGURATION = 0x02
    private const val INTERFACE = 0x04
    private const val ENDPOINT = 0x05
    private const val CS_INTERFACE = 0x24
    private const val AUDIO = 0x01
    private const val AUDIOCONTROL = 0x01
    private const val AUDIOSTREAMING = 0x02

    /** bConfigurationValue of every configuration in [raw] (Android returns all of them, not just the active one). */
    fun configValues(raw: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            if (raw[i + 1].toInt() == CONFIGURATION && len >= 6) out += raw[i + 5].toInt() and 0xFF
            i += len
        }
        return out
    }

    /** Parses configuration [configValue] (or the first one when null). Returns null if it has no audio control. */
    fun parse(raw: ByteArray, configValue: Int? = null): UacDevice? {
        fun u8(i: Int) = raw[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)

        var inConfig = false
        var seenConfig = false
        var cfgValue = -1
        var acInterface = -1
        var version = 1
        val alts = mutableListOf<UacAlt>()
        val fus = mutableListOf<UacFeatureUnit>()
        val terminalClock = mutableMapOf<Int, Int>()
        val selectors = mutableMapOf<Int, List<Int>>()

        var ifNum = -1; var alt = -1; var subclass = -1
        var link = -1; var channels = 0; var subslot = 0; var bits = 0; var pcm = false; var rawDsd = false
        var rates = emptyList<Int>(); var range: IntRange? = null
        var dataEp = -1; var dataMax = 0; var dataInterval = 1; var sync = 0; var synchAddr = 0; var dataUsage = 0
        var fbEp: Int? = null; var fbMax = 0

        fun flushAlt() {
            if (inConfig && subclass == AUDIOSTREAMING && dataEp >= 0 && channels > 0 && version != 3) {
                val capture = dataEp and 0x80 != 0
                alts += UacAlt(ifNum, alt, link, channels, subslot, bits, dataEp, dataMax, dataInterval, sync,
                    if (capture) null else fbEp ?: synchAddr.takeIf { it and 0x80 != 0 }, fbMax, rates, range, null, pcm,
                    capture, capture && dataUsage == 2, rawDsd)
            }
            link = -1; channels = 0; subslot = 0; bits = 0; pcm = false; rawDsd = false; rates = emptyList(); range = null
            dataEp = -1; dataMax = 0; dataInterval = 1; sync = 0; synchAddr = 0; dataUsage = 0; fbEp = null; fbMax = 0
        }

        var i = 0
        while (i + 1 < raw.size) {
            val len = u8(i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(i + 1)
            if (type == CONFIGURATION && len >= 6) {
                flushAlt()
                subclass = -1
                val v = u8(i + 5)
                inConfig = if (configValue == null) !seenConfig else v == configValue
                if (inConfig) cfgValue = v
                seenConfig = true
            } else if (inConfig) when (type) {
                INTERFACE -> {
                    flushAlt()
                    ifNum = u8(i + 2); alt = u8(i + 3)
                    subclass = if (u8(i + 5) == AUDIO) u8(i + 6) else -1
                    if (subclass == AUDIOCONTROL) {
                        acInterface = ifNum
                        version = when (u8(i + 7)) { 0x20 -> 2; 0x30 -> 3; else -> 1 }
                    }
                }
                CS_INTERFACE -> if (subclass == AUDIOCONTROL && version != 3) {
                    when (u8(i + 2)) {
                        0x02 -> if (version == 2 && len >= 8) terminalClock[u8(i + 3)] = u8(i + 7)       // input terminal
                        0x03 -> if (version == 2 && len >= 9) terminalClock[u8(i + 3)] = u8(i + 8)       // output terminal
                        0x06 -> if (version == 2) {
                            val n = (len - 6) / 4 // 4-byte bmaControls per channel, master first
                            val ctl = (0 until n).map { u8(i + 5 + 4 * it) }
                            fus += UacFeatureUnit(u8(i + 3), u8(i + 4),
                                pick(ctl.indices.filter { (ctl[it] shr 2) and 0x3 == 0x3 }, n),
                                pick(ctl.indices.filter { ctl[it] and 0x3 == 0x3 }, n))
                        } else {
                            val size = u8(i + 5).coerceAtLeast(1)
                            val n = (len - 7) / size
                            val ctl = (0 until n).map { u8(i + 6 + size * it) }
                            fus += UacFeatureUnit(u8(i + 3), u8(i + 4),
                                pick(ctl.indices.filter { ctl[it] and 0x02 != 0 }, n),
                                pick(ctl.indices.filter { ctl[it] and 0x01 != 0 }, n))
                        }
                        0x0B -> if (version == 2) selectors[u8(i + 3)] = (0 until u8(i + 4)).map { u8(i + 5 + it) }
                    }
                } else if (subclass == AUDIOSTREAMING && version != 3) {
                    when (u8(i + 2)) {
                        0x01 -> if (version == 2) {
                            link = u8(i + 3)
                            pcm = u8(i + 5) == 1 && (u8(i + 6) and 1) != 0
                            rawDsd = u8(i + 5) == 1 && (u8(i + 9) and 0x80) != 0
                            channels = u8(i + 10)
                        } else {
                            link = u8(i + 3)
                            pcm = u16(i + 5) == 0x0001
                        }
                        0x02 -> if (u8(i + 3) == 1) { // format type I
                            if (version == 2) {
                                subslot = u8(i + 4); bits = u8(i + 5)
                            } else {
                                channels = u8(i + 4); subslot = u8(i + 5); bits = u8(i + 6)
                                val n = u8(i + 7)
                                if (n == 0) range = u24(i + 8)..u24(i + 11)
                                else rates = (0 until n).map { u24(i + 8 + 3 * it) }
                            }
                        }
                    }
                }
                ENDPOINT -> if (subclass == AUDIOSTREAMING) {
                    val addr = u8(i + 2); val attrs = u8(i + 3)
                    val wMax = u16(i + 4)
                    val maxBytes = (wMax and 0x7FF) * (((wMax shr 11) and 0x3) + 1)
                    if (attrs and 0x3 == 1) { // isochronous
                        val usage = (attrs shr 4) and 0x3
                        if (usage == 1 && addr and 0x80 != 0) {
                            fbEp = addr; fbMax = maxBytes
                        } else if (dataEp < 0) {
                            dataEp = addr; dataMax = maxBytes; dataInterval = u8(i + 6); sync = (attrs shr 2) and 0x3
                            dataUsage = usage
                            if (len >= 9) synchAddr = u8(i + 8)
                        }
                    }
                }
            }
            i += len
        }
        flushAlt()
        if (acInterface < 0) return null
        return UacDevice(cfgValue, acInterface, version, alts.map { it.copy(clockId = terminalClock[it.terminalLink]) }, fus, selectors)
    }

    // Master (0) is preferred; otherwise every channel (1 until n) must be controllable, or none is used
    private fun pick(channels: List<Int>, n: Int): List<Int> = when {
        0 in channels -> listOf(0)
        n > 1 && (1 until n).all { it in channels } -> (1 until n).toList()
        else -> emptyList()
    }
}
