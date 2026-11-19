package com.clashplayer.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDriverTest {

    private fun d(vararg b: Int) = byteArrayOf((b.size + 1).toByte(), *b.map { it.toByte() }.toByteArray()) // bLength counts itself
    private fun blob(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { acc, p -> acc + p }
    private fun le24(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF)

    // Typical UAC2 DAC: clock 0x29 -> input terminal 1 -> feature unit 2 (master volume) -> speaker; async OUT + feedback
    private val uac2 = blob(
        d(0x02, 0, 0, 2, 1, 0, 0x80, 50),                    // configuration
        d(0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0),               // AC interface, protocol 0x20 = UAC2
        d(0x24, 0x01, 0x00, 0x02, 0x0A, 0x40, 0, 0),         // AC header
        d(0x24, 0x0A, 0x29, 0x03, 0x07, 0, 0),               // clock source 0x29
        d(0x24, 0x02, 0x01, 0x01, 0x01, 0, 0x29, 2, 0, 0, 0, 0, 0, 0, 0, 0), // input terminal 1, USB streaming
        d(0x24, 0x06, 0x02, 0x01, 0x0F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // feature unit 2: master mute+volume
        d(0x24, 0x03, 0x03, 0x01, 0x03, 0, 0x02, 0x29, 0, 0, 0),             // output terminal (headphones)
        d(0x04, 1, 0, 0, 0x01, 0x02, 0x20, 0),               // AS alt 0 (zero bandwidth)
        d(0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0),               // AS alt 1
        d(0x24, 0x01, 0x01, 0, 0x01, 0x01, 0, 0, 0, 2, 0x03, 0, 0, 0, 0),   // AS_GENERAL: link 1, PCM, 2 ch
        d(0x24, 0x02, 0x01, 4, 24),                          // format type I: 4-byte subslot, 24 bits
        d(0x05, 0x01, 0x05, 0x00, 0x04, 0x01),               // iso async OUT ep 1, 1024 bytes, interval 1
        d(0x25, 0x01, 0, 0, 0, 0),                           // CS endpoint
        d(0x05, 0x81, 0x11, 0x04, 0x00, 0x04),               // iso feedback IN ep 0x81, 4 bytes
    )

    // Typical UAC1 adaptive DAC with 44.1/48 kHz, 16-bit
    private val uac1 = blob(
        d(0x02, 0, 0, 2, 1, 0, 0x80, 50),
        d(0x04, 0, 0, 0, 0x01, 0x01, 0x00, 0),
        d(0x24, 0x01, 0x00, 0x01, 0x27, 0, 1, 1),
        d(0x24, 0x02, 0x01, 0x01, 0x01, 0, 2, 3, 0, 0, 0),   // input terminal 1
        d(0x24, 0x06, 0x02, 0x01, 0x01, 0x03, 0x00, 0x00, 0), // feature unit 2: master mute+volume
        d(0x04, 1, 0, 0, 0x01, 0x02, 0x00, 0),
        d(0x04, 1, 1, 1, 0x01, 0x02, 0x00, 0),
        d(0x24, 0x01, 0x01, 1, 0x01, 0x00),                  // AS_GENERAL: link 1, PCM
        d(0x24, 0x02, 0x01, 2, 2, 16, 2, *le24(44100), *le24(48000)),
        d(0x05, 0x01, 0x09, 0xC0, 0x00, 0x01, 0, 0),         // iso adaptive OUT, 192 bytes
        d(0x25, 0x01, 0x01, 0, 0, 0),
    )

    @Test
    fun testParsesUac2StreamingFormatClockAndVolume() {
        val dev = UacDescriptors.parse(uac2)!!
        assertEquals(2, dev.uacVersion)
        assertEquals(0, dev.acInterface)
        val alt = dev.alts.single()
        assertEquals(1, alt.interfaceNumber); assertEquals(1, alt.altSetting)
        assertEquals(2, alt.channels); assertEquals(4, alt.subslotBytes); assertEquals(24, alt.bitResolution)
        assertEquals(0x01, alt.endpoint); assertEquals(1024, alt.maxPacketBytes)
        assertEquals(1, alt.syncType)
        assertEquals(0x81, alt.feedbackEndpoint)
        assertEquals(0x29, alt.clockId)
        assertTrue(alt.isPcm)
        val fu = dev.featureUnits.single()
        assertEquals(2, fu.id); assertEquals(1, fu.sourceId); assertEquals(listOf(0), fu.volumeChannels)
    }

    @Test
    fun testParsesUac1RatesAndVolume() {
        val dev = UacDescriptors.parse(uac1)!!
        assertEquals(1, dev.uacVersion)
        val alt = dev.alts.single()
        assertEquals(listOf(44100, 48000), alt.sampleRates)
        assertEquals(16, alt.bitResolution); assertEquals(2, alt.subslotBytes)
        assertEquals(192, alt.maxPacketBytes); assertEquals(2, alt.syncType)
        assertNull(alt.feedbackEndpoint)
        assertNull(alt.clockId)
        assertTrue(dev.featureUnits.single().hasVolume)
    }

    @Test
    fun testNonAudioDeviceIsRejected() {
        assertNull(UacDescriptors.parse(blob(d(0x02, 0, 0, 1, 1, 0, 0x80, 50), d(0x04, 0, 0, 1, 0x08, 0x06, 0x50, 0))))
    }

    @Test
    fun testPacketizerIsExactOverTime() {
        val p = IsoPacketizer(44100, 1000, 48)
        val frames = IntArray(1000) { p.nextPacketFrames() }
        assertEquals(44100, frames.sum())
        assertTrue(frames.all { it == 44 || it == 45 })
        val hs = IsoPacketizer(192000, 8000, 64)
        assertTrue(IntArray(8000) { hs.nextPacketFrames() }.all { it == 24 })
    }

    @Test
    fun testFeedbackFormatsAndOutlierRejection() {
        val nominal = (44100L shl 16) / 1000
        // Full speed 10.14: 44.1 frames/ms
        val fs = (44.1 * (1 shl 14)).toLong()
        assertEquals(fs shl 2, IsoPacketizer.feedbackToQ16(fs, highSpeed = false, busFramesPerPacket = 1, nominalQ16 = nominal))
        // High speed 16.16 per microframe, 8 microframes per packet
        val hsNominal = (44100L shl 16) / 1000
        val hs = (5.5125 * 65536).toLong()
        assertNotNull(IsoPacketizer.feedbackToQ16(hs, highSpeed = true, busFramesPerPacket = 8, nominalQ16 = hsNominal))
        // A device sending 10.14 on a high-speed bus is still understood
        val hs1014 = (5.5125 * (1 shl 14)).toLong()
        assertNotNull(IsoPacketizer.feedbackToQ16(hs1014, highSpeed = true, busFramesPerPacket = 8, nominalQ16 = hsNominal))
        // Garbage is rejected and the packetizer stays nominal
        assertNull(IsoPacketizer.feedbackToQ16(12345, highSpeed = false, busFramesPerPacket = 1, nominalQ16 = nominal))
        val p = IsoPacketizer(44100, 1000, 48)
        p.applyFeedback(nominal * 2)
        assertEquals(nominal, p.currentQ16)
    }

    @Test
    fun testOnlyTheRequestedConfigurationIsParsed() {
        // Config 1: UAC3 (no class descriptors in the configuration); config 2: the UAC2 fallback
        val uac3 = blob(
            d(0x02, 0, 0, 2, 1, 0, 0x80, 50),
            d(0x04, 0, 0, 0, 0x01, 0x01, 0x30, 0),
            d(0x04, 1, 1, 1, 0x01, 0x02, 0x30, 0),
            d(0x05, 0x01, 0x05, 0x00, 0x02, 0x01),
        )
        val fallback = uac2.copyOf().also { it[5] = 2 } // bConfigurationValue = 2
        val raw = uac3 + fallback
        assertEquals(listOf(1, 2), UacDescriptors.configValues(raw))
        val first = UacDescriptors.parse(raw, 1)!!
        assertEquals(3, first.uacVersion)
        assertTrue(first.playbackAlts.isEmpty())
        val second = UacDescriptors.parse(raw, 2)!!
        assertEquals(2, second.uacVersion)
        assertEquals(1, second.playbackAlts.size)
        assertEquals(2, second.configValue)
    }

    @Test
    fun testCaptureStreamForImplicitFeedbackAndMuteControl() {
        val withCapture = uac2.copyOfRange(0, uac2.size - 7) + blob( // drop the explicit feedback endpoint
            d(0x04, 2, 0, 0, 0x01, 0x02, 0x20, 0),
            d(0x04, 2, 1, 1, 0x01, 0x02, 0x20, 0),
            d(0x24, 0x01, 0x03, 0, 0x01, 0x01, 0, 0, 0, 2, 0x03, 0, 0, 0, 0),
            d(0x24, 0x02, 0x01, 4, 24),
            d(0x05, 0x82, 0x25, 0x00, 0x04, 0x01), // iso async IN, usage 2 = implicit feedback data
        )
        val dev = UacDescriptors.parse(withCapture)!!
        val play = dev.playbackAlts.single()
        assertNull(play.feedbackEndpoint)
        assertEquals(1, play.syncType)
        val cap = dev.captureAlts.single()
        assertEquals(0x82, cap.endpoint)
        assertTrue(cap.implicitFeedbackData)
        assertEquals(listOf(0), dev.featureUnits.single().muteChannels)
    }

    @Test
    fun testImplicitFeedbackDrivesPacketSizes() {
        val p = IsoPacketizer(48000, 1000, 64)
        p.applyFeedback((48L shl 16) + (1L shl 15)) // device clock: 48.5 frames per packet
        val frames = IntArray(1000) { p.nextPacketFrames() }
        assertEquals(48500, frames.sum())
    }
}
