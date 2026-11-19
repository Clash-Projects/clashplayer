package com.clashplayer.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.clashplayer.dsp.Resampler
import com.clashplayer.dsp.packPcm
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal object UsbIso {
    init { System.loadLibrary("usbiso") }
    const val FB_NONE = 0
    const val FB_EXPLICIT = 1
    const val FB_IMPLICIT = 2
    @JvmStatic external fun nativeOpen(fd: Int, ep: Int, maxPacket: Int, packetsPerUrb: Int, numUrbs: Int,
                                       fbMode: Int, fbEp: Int, fbPacket: Int, fbPackets: Int, fbFrameBytes: Int): Long
    @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray, length: Int, packetBytes: IntArray, packets: Int): Int
    @JvmStatic external fun nativeFeedback(handle: Long): Long
    @JvmStatic external fun nativeFlush(handle: Long): Int
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeSpeed(fd: Int): Int
    @JvmStatic external fun nativeReconnect(fd: Int, interfaceNumber: Int): Int
    @JvmStatic external fun nativeDisconnect(fd: Int, interfaceNumber: Int): Int
}

/**
 * Plays PCM straight to a USB Audio Class 1/2 DAC through usbfs, bypassing the Android mixer entirely:
 * the DAC is clocked at the track's own rate and receives the samples unchanged.
 * Not thread-safe: use from the audio thread (or while it is stopped).
 */
class UsbAudioOutput private constructor(
    private val conn: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private val idleAlts: List<UsbInterface>,
    private val uac: UacDevice,
    val alt: UacAlt,
    private val capture: UacAlt?,
    val sampleRate: Int,
    private val highSpeed: Boolean,
    private val volumeUnit: UacFeatureUnit?,
    private val volumeRange: IntRange?, // device volume in 1/256 dB
) : Closeable {

    /** True when the DAC runs at the source rate in a format holding every source bit (no resampling or truncation). */
    var exact = true
        private set
    val channels get() = alt.channels
    val hasHardwareVolume get() = volumeUnit != null && volumeRange != null
    val classLabel get() = if (uac.uacVersion == 2) "UAC2" else "UAC1"
    val isNativeDsd get() = alt.isRawDsd && alt.subslotBytes == 4
    val busLabel get() = if (highSpeed) "High-speed" else "Full-speed"
    val syncLabel get() = when {
        capture != null -> "Implicit feedback"
        alt.feedbackEndpoint != null -> "Async feedback"
        alt.syncType == 2 -> "Adaptive"
        else -> "Synchronous"
    }
    val description: String get() =
        "USB $classLabel ${sampleRate / 1000.0} kHz ${alt.bitResolution}-bit ${alt.channels}ch, $syncLabel, $busLabel"

    /** The DAC's own clock as reported by its feedback, in Hz; null until feedback arrives (or without feedback). */
    val measuredRateHz: Double? get() = if (packetizer.feedbackActive) packetizer.currentQ16 / 65536.0 * pps else null

    private fun packetsPerSecond(a: UacAlt) = (if (highSpeed) 8000 else 1000) / (1 shl (a.interval.coerceIn(1, 16) - 1))
    private val busFramesPerPacket = 1 shl (alt.interval.coerceIn(1, 16) - 1)
    private val pps = packetsPerSecond(alt)
    private val bytesPerFrame = alt.subslotBytes * alt.channels
    private val packetizer = IsoPacketizer(sampleRate, pps, alt.maxPacketBytes / bytesPerFrame)
    // ~8 ms per URB, 8 URBs queued: deep enough to ride out scheduling hiccups
    private val packetsPerUrb = (pps / 125).coerceAtLeast(1)
    private val handle: Long = run {
        val fb = when {
            capture != null -> intArrayOf(UsbIso.FB_IMPLICIT, capture.endpoint, capture.maxPacketBytes,
                (packetsPerSecond(capture) / 125).coerceAtLeast(1), capture.subslotBytes * capture.channels)
            alt.feedbackEndpoint != null -> intArrayOf(UsbIso.FB_EXPLICIT, alt.feedbackEndpoint, alt.feedbackMaxPacket.coerceAtLeast(4), 1, 1)
            else -> intArrayOf(UsbIso.FB_NONE, 0, 0, 0, 0)
        }
        UsbIso.nativeOpen(conn.fileDescriptor, alt.endpoint, alt.maxPacketBytes, packetsPerUrb, 8, fb[0], fb[1], fb[2], fb[3], fb[4])
            .also { if (it == 0L) throw IOException("Out of memory opening USB stream") }
    }

    private var carry = FloatArray(0)
    private var carryLen = 0
    private var expanded = FloatArray(0)
    private val plan = IntArray(packetsPerUrb)
    private var planFrames = -1
    private val packetBytes = IntArray(packetsPerUrb)
    private val urbBuffer = ByteBuffer.allocate(alt.maxPacketBytes * packetsPerUrb).order(ByteOrder.LITTLE_ENDIAN)
    private var lastVolumeDb: Float? = Float.NaN
    private var muted = false

    /**
     * Queues [count] interleaved samples of [srcChannels] channels; source channel i plays on DAC channel i and
     * any extra DAC channels get silence. Blocks while the device's queue is full.
     */
    fun write(samples: FloatArray, count: Int, srcChannels: Int) {
        require(srcChannels <= alt.channels) { "DAC has ${alt.channels} channels, source $srcChannels" }
        if (carryLen + count > carry.size) carry = carry.copyOf((carryLen + count) * 2)
        System.arraycopy(samples, 0, carry, carryLen, count)
        carryLen += count
        var offset = 0
        while (true) {
            if (planFrames < 0) planUrb()
            val needed = planFrames * srcChannels
            if (carryLen - offset < needed) break
            val bits = alt.subslotBytes * 8
            if (srcChannels == alt.channels) {
                packPcm(carry, needed, bits, urbBuffer, offset)
            } else {
                val n = planFrames * alt.channels
                if (expanded.size < n) expanded = FloatArray(n)
                java.util.Arrays.fill(expanded, 0, n, 0f)
                for (f in 0 until planFrames) System.arraycopy(carry, offset + f * srcChannels, expanded, f * alt.channels, srcChannels)
                packPcm(expanded, n, bits, urbBuffer)
            }
            for (p in 0 until packetsPerUrb) packetBytes[p] = plan[p] * bytesPerFrame
            val rc = UsbIso.nativeWrite(handle, urbBuffer.array(), urbBuffer.remaining(), packetBytes, packetsPerUrb)
            if (rc < 0) throw IOException("USB isochronous submit failed (errno ${-rc}); DAC unplugged?")
            offset += needed
            planFrames = -1
        }
        System.arraycopy(carry, offset, carry, 0, carryLen - offset)
        carryLen -= offset
    }

    private var rawCarry = ByteArray(0)
    private var rawLen = 0

    /**
     * Native DSD: queues [count] bytes laid out as the DAC's 32-bit raw slots (per frame, 4 bytes per channel,
     * earliest bit first). Only for an output opened with nativeDsd.
     */
    fun writeRaw(bytes: ByteArray, count: Int) {
        if (rawLen + count > rawCarry.size) rawCarry = rawCarry.copyOf((rawLen + count) * 2)
        System.arraycopy(bytes, 0, rawCarry, rawLen, count)
        rawLen += count
        var offset = 0
        while (true) {
            if (planFrames < 0) planUrb()
            val needed = planFrames * bytesPerFrame
            if (rawLen - offset < needed) break
            urbBuffer.clear()
            urbBuffer.put(rawCarry, offset, needed)
            urbBuffer.flip()
            for (p in 0 until packetsPerUrb) packetBytes[p] = plan[p] * bytesPerFrame
            val rc = UsbIso.nativeWrite(handle, urbBuffer.array(), needed, packetBytes, packetsPerUrb)
            if (rc < 0) throw IOException("USB isochronous submit failed (errno ${-rc}); DAC unplugged?")
            offset += needed
            planFrames = -1
        }
        System.arraycopy(rawCarry, offset, rawCarry, 0, rawLen - offset)
        rawLen -= offset
    }

    /** Drops everything queued but not yet played (seek), keeping the DAC clock running. */
    fun flush() {
        UsbIso.nativeFlush(handle)
        carryLen = 0
        rawLen = 0
        planFrames = -1
    }

    // Packet sizes are fixed per URB once planned, so feedback only applies between URBs
    private fun planUrb() {
        val raw = UsbIso.nativeFeedback(handle)
        if (raw != 0L) {
            val q16 = if (capture != null) raw * packetsPerSecond(capture) / pps // capture frames/packet -> ours
            else IsoPacketizer.feedbackToQ16(raw, highSpeed, busFramesPerPacket, packetizer.nominalQ16)
            q16?.let(packetizer::applyFeedback)
        }
        planFrames = 0
        for (p in 0 until packetsPerUrb) { plan[p] = packetizer.nextPacketFrames(); planFrames += plan[p] }
    }

    /** Sets the DAC's own volume; null mutes (mute control if present, else the device minimum). */
    fun setVolumeDb(db: Float?) {
        val unit = volumeUnit ?: return
        val range = volumeRange ?: return
        if (db == lastVolumeDb) return
        lastVolumeDb = db
        val index = (unit.id shl 8) or uac.acInterface
        if (db == null && unit.hasMute) {
            setMute(unit, index, true)
            return
        }
        if (muted) setMute(unit, index, false)
        val v = if (db == null) range.first else (db * 256).roundToInt().coerceIn(range)
        val data = byteArrayOf(v.toByte(), (v shr 8).toByte())
        for (ch in unit.volumeChannels) conn.controlTransfer(0x21, CUR, (VOLUME_CONTROL shl 8) or ch, index, data, 2, TIMEOUT_MS)
    }

    private fun setMute(unit: UacFeatureUnit, index: Int, on: Boolean) {
        val data = byteArrayOf(if (on) 1 else 0)
        for (ch in unit.muteChannels) conn.controlTransfer(0x21, CUR, (MUTE_CONTROL shl 8) or ch, index, data, 1, TIMEOUT_MS)
        muted = on
    }

    override fun close() {
        UsbIso.nativeClose(handle)
        idleAlts.forEach { conn.setInterface(it) }
        claimed.forEach { conn.releaseInterface(it) }
        // Hand the DAC back to Android's audio driver; otherwise it stays silent for other apps until re-plugged
        claimed.map { it.id }.distinct().forEach { UsbIso.nativeReconnect(conn.fileDescriptor, it) }
        conn.close()
    }

    companion object {
        private const val CUR = 0x01
        private const val RANGE = 0x02
        private const val UAC1_GET_MIN = 0x82
        private const val UAC1_GET_MAX = 0x83
        private const val UAC1_GET_CUR = 0x81
        private const val SAMPLING_FREQ_CONTROL = 0x01
        private const val MUTE_CONTROL = 0x01
        private const val VOLUME_CONTROL = 0x02
        private const val TIMEOUT_MS = 1000
        private const val USB_SPEED_HIGH = 3
        private val STANDARD_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)

        fun isUsbAudio(device: UsbDevice): Boolean = (0 until device.configurationCount).any { c ->
            val cfg = device.getConfiguration(c)
            (0 until cfg.interfaceCount).any { cfg.getInterface(it).let { i -> i.interfaceClass == UsbConstants.USB_CLASS_AUDIO && i.interfaceSubclass == 2 } }
        }

        /**
         * Opens [device] (permission must already be granted) at exactly [sampleRate] with at least [channels]
         * channels in a format holding [bitDepth] bits. If the DAC can't run at that rate it throws, unless
         * [allowResample] lets it pick the closest clean rate instead ([sampleRate] of the result tells which).
         */
        fun open(manager: UsbManager, device: UsbDevice, sampleRate: Int, bitDepth: Int, channels: Int, allowResample: Boolean = false, nativeDsd: Boolean = false): UsbAudioOutput {
            val conn = manager.openDevice(device) ?: throw IOException("Cannot open ${device.productName}")
            val claimed = mutableListOf<UsbInterface>()
            try {
                val raw = conn.rawDescriptors
                val cfgByte = ByteArray(1)
                val active = if (conn.controlTransfer(0x80, 0x08, 0, 0, cfgByte, 1, TIMEOUT_MS) == 1) cfgByte[0].toInt() and 0xFF else null
                var uac = UacDescriptors.parse(raw, active)
                if (uac == null || uac.playbackAlts.isEmpty()) {
                    // UAC3 (or no usable streaming): switch to the UAC2/UAC1 configuration UAC3 devices must also offer
                    val fallback = UacDescriptors.configValues(raw).filter { it != active }
                        .firstNotNullOfOrNull { v -> UacDescriptors.parse(raw, v)?.takeIf { it.uacVersion != 3 && it.playbackAlts.isNotEmpty() } }
                        ?: throw IOException(if (uac?.uacVersion == 3) "UAC3-only device without a UAC2/UAC1 configuration" else "Not a USB Audio Class device")
                    val current = configuration(device, active)
                    if (current != null) for (i in 0 until current.interfaceCount) UsbIso.nativeDisconnect(conn.fileDescriptor, current.getInterface(i).id)
                    val target = configuration(device, fallback.configValue) ?: throw IOException("Configuration ${fallback.configValue} missing")
                    if (!conn.setConfiguration(target)) throw IOException("Cannot switch to configuration ${fallback.configValue}")
                    uac = fallback
                }
                val cfg = configuration(device, uac.configValue) ?: throw IOException("Active configuration missing")
                fun iface(num: Int, alt: Int) = (0 until cfg.interfaceCount).map { cfg.getInterface(it) }
                    .firstOrNull { it.id == num && it.alternateSetting == alt }

                val speed = UsbIso.nativeSpeed(conn.fileDescriptor)
                val highSpeed = if (speed > 0) speed >= USB_SPEED_HIGH else uac.uacVersion == 2

                // Detach the kernel's USB audio driver: from here on this app owns the DAC
                iface(uac.acInterface, 0)?.let { if (conn.claimInterface(it, true)) claimed += it }

                // Rates each alt can run at: discrete rates as listed, continuous ranges as the standard rates inside them
                val ratesOf: (UacAlt) -> List<Int> = { a ->
                    val ranges = if (uac.uacVersion == 2) resolveClock(uac, a.clockId)?.let { clockRanges(conn, uac.acInterface, it) }.orEmpty()
                    else a.sampleRates.map { it..it } + listOfNotNull(a.rateRange)
                    ranges.flatMap { r -> if (r.first == r.last) listOf(r.first) else (STANDARD_RATES + sampleRate).filter { it in r } }
                }
                // Native DSD runs a raw-data alt at DSD rate / 32 with 32-bit slots; otherwise PCM alts only
                val pcmAlts = uac.playbackAlts.filter { if (nativeDsd) it.isRawDsd && it.subslotBytes == 4 else it.isPcm && it.subslotBytes in 2..4 }
                val altRates = pcmAlts.associateWith(ratesOf)
                fun usableWith(min: Int, r: Int) = pcmAlts.filter { it.channels >= min && r in altRates.getValue(it) }
                fun usableAt(r: Int) = usableWith(channels.coerceAtLeast(2), r).ifEmpty { usableWith(2, r) }
                // All source channels if the DAC can take them, else stereo (the caller downmixes)
                var rate = sampleRate
                var usable = usableAt(rate)
                if (usable.isEmpty() && allowResample && !nativeDsd) {
                    rate = Resampler.pickRate(sampleRate, pcmAlts.filter { it.channels >= 2 }.flatMap { altRates.getValue(it) })
                        ?: throw IOException("DAC reports no sample rates")
                    usable = usableAt(rate)
                }
                if (usable.isEmpty()) throw IOException("DAC has no PCM mode at $sampleRate Hz")
                val rateOk: (UacAlt) -> Boolean = { rate in ratesOf(it) }
                // Fewest channels that fit, then the smallest format holding every source bit (else the deepest)
                val alt = usable.minWith(compareBy<UacAlt> { it.channels }
                    .thenBy { if (it.bitResolution >= bitDepth) 0 else 1 }
                    .thenBy { if (it.bitResolution >= bitDepth) it.bitResolution * 8 + it.subslotBytes else -it.bitResolution })

                val asIface = iface(alt.interfaceNumber, alt.altSetting) ?: throw IOException("Streaming interface missing")
                if (!conn.claimInterface(asIface, true)) throw IOException("Cannot claim streaming interface")
                claimed += asIface
                if (!conn.setInterface(asIface)) throw IOException("Cannot select alt setting ${alt.altSetting}")
                setRate(conn, uac, alt, rate)

                // Async endpoint without a feedback endpoint: the device clock comes from its capture stream
                var capture: UacAlt? = null
                if (alt.syncType == 1 && alt.feedbackEndpoint == null) {
                    capture = uac.captureAlts.filter { it.interfaceNumber != alt.interfaceNumber && it.subslotBytes > 0 && rateOk(it) }
                        .minWithOrNull(compareBy<UacAlt> { if (it.implicitFeedbackData) 0 else 1 }.thenBy { it.channels })
                }
                if (capture != null) {
                    val capIface = iface(capture.interfaceNumber, capture.altSetting) ?: throw IOException("Capture interface missing")
                    if (!conn.claimInterface(capIface, true)) throw IOException("Cannot claim capture interface")
                    claimed += capIface
                    if (!conn.setInterface(capIface)) throw IOException("Cannot select capture alt ${capture.altSetting}")
                    if (resolveClock(uac, capture.clockId) != resolveClock(uac, alt.clockId) || uac.uacVersion == 1) setRate(conn, uac, capture, rate)
                }

                val idle = listOfNotNull(iface(alt.interfaceNumber, 0), capture?.let { iface(it.interfaceNumber, 0) })
                val fu = uac.featureUnits.firstOrNull { it.sourceId == alt.terminalLink && it.hasVolume }
                val range = fu?.let { volumeRange(conn, uac, it) }
                return UsbAudioOutput(conn, claimed, idle, uac, alt, capture, rate, highSpeed, fu, range)
                    .also { it.exact = alt.bitResolution >= bitDepth && rate == sampleRate }
            } catch (e: Exception) {
                claimed.forEach { conn.releaseInterface(it) }
                conn.close()
                throw e
            }
        }

        private fun configuration(device: UsbDevice, value: Int?): UsbConfiguration? =
            (0 until device.configurationCount).map { device.getConfiguration(it) }.let { all ->
                if (value == null) all.firstOrNull() else all.firstOrNull { it.id == value }
            }

        private fun resolveClock(uac: UacDevice, id: Int?): Int? {
            var clock = id ?: return null
            repeat(4) { clock = uac.clockSelectors[clock]?.firstOrNull() ?: return clock } // selectors default to input 1
            return clock
        }

        private fun clockRanges(conn: UsbDeviceConnection, acIf: Int, clock: Int): List<IntRange> {
            val head = ByteArray(2)
            val index = (clock shl 8) or acIf
            if (conn.controlTransfer(0xA1, RANGE, SAMPLING_FREQ_CONTROL shl 8, index, head, 2, TIMEOUT_MS) < 2) return emptyList()
            val n = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
            val buf = ByteArray(2 + 12 * n)
            if (conn.controlTransfer(0xA1, RANGE, SAMPLING_FREQ_CONTROL shl 8, index, buf, buf.size, TIMEOUT_MS) < buf.size) return emptyList()
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until n).map { bb.getInt(2 + 12 * it)..bb.getInt(6 + 12 * it) }
        }

        private fun setRate(conn: UsbDeviceConnection, uac: UacDevice, alt: UacAlt, rate: Int) {
            if (uac.uacVersion == 2) {
                val clock = resolveClock(uac, alt.clockId) ?: throw IOException("No clock source for the streaming terminal")
                val index = (clock shl 8) or uac.acInterface
                val set = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rate).array()
                conn.controlTransfer(0x21, CUR, SAMPLING_FREQ_CONTROL shl 8, index, set, 4, TIMEOUT_MS)
                val got = ByteArray(4)
                if (conn.controlTransfer(0xA1, CUR, SAMPLING_FREQ_CONTROL shl 8, index, got, 4, TIMEOUT_MS) == 4) {
                    val actual = ByteBuffer.wrap(got).order(ByteOrder.LITTLE_ENDIAN).int
                    if (actual != rate) throw IOException("DAC clock runs at $actual Hz, not $rate Hz")
                }
            } else {
                // UAC1 rate is an endpoint control; devices with a single fixed rate may stall it, which is fine
                val set = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
                conn.controlTransfer(0x22, CUR, SAMPLING_FREQ_CONTROL shl 8, alt.endpoint, set, 3, TIMEOUT_MS)
                val got = ByteArray(3)
                if (conn.controlTransfer(0xA2, UAC1_GET_CUR, SAMPLING_FREQ_CONTROL shl 8, alt.endpoint, got, 3, TIMEOUT_MS) == 3) {
                    val actual = (got[0].toInt() and 0xFF) or ((got[1].toInt() and 0xFF) shl 8) or ((got[2].toInt() and 0xFF) shl 16)
                    if (actual != rate) throw IOException("DAC runs at $actual Hz, not $rate Hz")
                }
            }
        }

        private fun volumeRange(conn: UsbDeviceConnection, uac: UacDevice, fu: UacFeatureUnit): IntRange? {
            val ch = fu.volumeChannels.first()
            val value = (VOLUME_CONTROL shl 8) or ch
            val index = (fu.id shl 8) or uac.acInterface
            fun s16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
            return if (uac.uacVersion == 2) {
                val buf = ByteArray(8) // wNumSubRanges + first {min, max, res}
                if (conn.controlTransfer(0xA1, RANGE, value, index, buf, 8, TIMEOUT_MS) < 8) null else s16(buf, 2)..s16(buf, 4)
            } else {
                val min = ByteArray(2); val max = ByteArray(2)
                if (conn.controlTransfer(0xA1, UAC1_GET_MIN, value, index, min, 2, TIMEOUT_MS) < 2) return null
                if (conn.controlTransfer(0xA1, UAC1_GET_MAX, value, index, max, 2, TIMEOUT_MS) < 2) return null
                s16(min, 0)..s16(max, 0)
            }
        }
    }
}
