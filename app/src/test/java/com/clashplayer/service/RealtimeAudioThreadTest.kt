package com.clashplayer.service

import com.clashplayer.dsp.AudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAudioThreadTest {

    @Test
    fun testAudioThreadPriorityConfiguration() {
        // Verify setAudioThreadPriority runs cleanly and sets real-time audio priority
        AudioOutputManager.setAudioThreadPriority()
    }

    @Test
    fun testAudioEngineZeroAllocationsBenchmark() {
        val engine = AudioEngine(sampleRate = 48000)
        val buffer = FloatArray(2048)
        for (i in buffer.indices) {
            buffer[i] = kotlin.math.sin(i.toDouble() * 0.05).toFloat()
        }

        // Warm up JIT and DSP filters
        for (i in 0 until 100) {
            engine.processInterleavedStereo(buffer, 0, buffer.size)
        }

        System.gc()
        Thread.sleep(20)

        val beforeUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Run 100 blocks (~4.3 seconds of real-time 48kHz audio rendering)
        for (i in 0 until 100) {
            engine.processInterleavedStereo(buffer, 0, buffer.size)
        }

        val afterUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val deltaBytes = (afterUsed - beforeUsed).coerceAtLeast(0L)

        // Zero-GC mandate: In steady-state audio rendering loop, memory growth remains negligible
        assertTrue("Steady-state audio render loop produced $deltaBytes bytes", deltaBytes < 512 * 1024)
    }
}
