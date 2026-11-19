package com.clashplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeCurveTest {
    @Test
    fun testMediaCurvePointsAndInterpolation() {
        val c = VolumeCurve.MEDIA
        assertNull(c.stepToDb(0, 100))
        assertEquals(-58f, c.stepToDb(1, 100)!!, 1e-4f)
        assertEquals(-40f, c.stepToDb(20, 100)!!, 1e-4f)
        assertEquals(-28.5f, c.stepToDb(40, 100)!!, 1e-4f)
        assertEquals(0f, c.stepToDb(100, 100)!!, 1e-4f)
    }

    @Test
    fun testFewerStepsScaleOntoTheHundredIndexScale() {
        // 15 steps: step 1 maps to index 6
        assertEquals(-58f + 18f * 5f / 19f, VolumeCurve.MEDIA.stepToDb(1, 15)!!, 1e-4f)
        assertEquals(-11f, VolumeCurve.SPEAKER.stepToDb(9, 15)!!, 1e-4f) // index 60
    }
}
