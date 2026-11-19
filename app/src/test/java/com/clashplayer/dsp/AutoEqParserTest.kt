package com.clashplayer.dsp

import com.clashplayer.dsp.model.AutoEqParser
import com.clashplayer.dsp.model.FilterType
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoEqParserTest {

    @Test
    fun testParseParametricAutoEqProfile() {
        val sampleText = """
            Preamp: -6.4 dB
            Filter 1: ON PK Fc 31 Hz Gain 4.8 dB Q 1.41
            Filter 2: ON LSC Fc 105 Hz Gain -3.2 dB Q 0.71
            Filter 3: ON HSC Fc 8000 Hz Gain 2.1 dB Q 0.71
        """.trimIndent()

        val preset = AutoEqParser.parseParametric("Sennheiser HD 650", sampleText)

        assertEquals("Sennheiser HD 650", preset.name)
        assertEquals(-6.4f, preset.preamp, 0.01f)
        assertEquals(3, preset.parametricBands.size)

        val b1 = preset.parametricBands[0]
        assertEquals(31, b1.frequency)
        assertEquals(4.8f / 15.0f, b1.gain, 0.01f)
        assertEquals(1.41f, b1.q, 0.01f)
        assertEquals(FilterType.PEAKING.id, b1.type)

        val b2 = preset.parametricBands[1]
        assertEquals(105, b2.frequency)
        assertEquals(-3.2f / 15.0f, b2.gain, 0.01f)
        assertEquals(0.71f, b2.q, 0.01f)
        assertEquals(FilterType.LOW_SHELF.id, b2.type)

        val b3 = preset.parametricBands[2]
        assertEquals(8000, b3.frequency)
        assertEquals(2.1f / 15.0f, b3.gain, 0.01f)
        assertEquals(0.71f, b3.q, 0.01f)
        assertEquals(FilterType.HIGH_SHELF.id, b3.type)
    }

    @Test
    fun testParseDecimalFrequency() {
        val preset = AutoEqParser.parseParametric("x", "Filter 1: ON PK Fc 1234.6 Hz Gain -2.0 dB Q 0.9")
        assertEquals(1, preset.parametricBands.size)
        assertEquals(1235, preset.parametricBands[0].frequency)
    }
}
