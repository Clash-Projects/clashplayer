package com.clashplayer.dsp

import com.clashplayer.dsp.model.AutoEqRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqIndexTest {
    @Test
    fun testParsesIndexLinesIncludingParenthesesAndOptionalRig() {
        val text = """
            # Index
            This is a list of all equalization profiles.

            - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
            - [1MORE ComfoBuds Mini](./DHRME/in-ear/1MORE%20ComfoBuds%20Mini) by DHRME
        """.trimIndent()
        val e = AutoEqRepository.parseIndex(text)
        assertEquals(2, e.size)
        assertEquals("1MORE Aero (ANC Off)", e[0].name)
        assertEquals("HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)", e[0].path)
        assertEquals("HypetheSonics", e[0].source)
        assertEquals("GRAS RA0045", e[0].rig)
        assertEquals("DHRME", e[1].source)
        assertNull(e[1].rig)
    }

    @Test
    fun testRealProfileFormatParses() {
        val text = """
            Preamp: -6.0 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
            Filter 2: ON PK Fc 180 Hz Gain -3.3 dB Q 0.57
            Filter 6: ON HSC Fc 10000 Hz Gain -1.1 dB Q 0.70
        """.trimIndent()
        val p = com.clashplayer.dsp.model.AutoEqParser.parseParametric("x", text)
        assertEquals(-6.0f, p.preamp, 1e-4f)
        assertEquals(3, p.parametricBands.size)
        assertEquals(5.5f, p.parametricBands[0].getGainDb(), 1e-4f)
    }
}
