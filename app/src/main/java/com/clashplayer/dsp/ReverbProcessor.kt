package com.clashplayer.dsp

class ReverbProcessor(
    private var sampleRate: Int = 44100
) {
    var enabled: Boolean = false
    var wetMix: Float = 0.25f       // 0.0 to 1.0
    var dryMix: Float = 1.0f        // 0.0 to 1.0
    var roomSize: Float = 0.5f      // 0.0 to 1.0
    var damping: Float = 0.5f       // 0.0 to 1.0
    var width: Float = 1.0f         // 0.0 to 1.0

    // Comb filter delay lines (stereospread tuned)
    private val combDelays = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassDelays = intArrayOf(556, 441, 341, 225)

    private val combBuffersL = Array(8) { FloatArray(2048) }
    private val combBuffersR = Array(8) { FloatArray(2048) }
    private val combIndicesL = IntArray(8)
    private val combIndicesR = IntArray(8)
    private val combFiltersL = FloatArray(8)
    private val combFiltersR = FloatArray(8)

    private val allpassBuffersL = Array(4) { FloatArray(1024) }
    private val allpassBuffersR = Array(4) { FloatArray(1024) }
    private val allpassIndicesL = IntArray(4)
    private val allpassIndicesR = IntArray(4)

    fun updateSampleRate(newRate: Int) {
        if (sampleRate != newRate && newRate > 0) {
            sampleRate = newRate
            reset()
        }
    }

    fun process(inputL: Float, inputR: Float, output: FloatArray) {
        if (!enabled || wetMix <= 0.001f) {
            output[0] = inputL
            output[1] = inputR
            return
        }

        val input = (inputL + inputR) * 0.015f
        var outL = 0.0f
        var outR = 0.0f

        val feedback = roomSize * 0.28f + 0.7f
        val damp = damping * 0.4f

        // Process 8 parallel comb filters
        for (i in 0 until 8) {
            val delayL = combDelays[i]
            val bufL = combBuffersL[i]
            val idxL = combIndicesL[i]
            val sampleL = bufL[idxL]

            combFiltersL[i] = sampleL * (1.0f - damp) + combFiltersL[i] * damp
            bufL[idxL] = input + combFiltersL[i] * feedback
            combIndicesL[i] = (idxL + 1) % delayL
            outL += sampleL

            // Right channel with stereo spread offset
            val delayR = delayL + 23
            val bufR = combBuffersR[i]
            val idxR = combIndicesR[i]
            val sampleR = bufR[idxR]

            combFiltersR[i] = sampleR * (1.0f - damp) + combFiltersR[i] * damp
            bufR[idxR] = input + combFiltersR[i] * feedback
            combIndicesR[i] = (idxR + 1) % delayR
            outR += sampleR
        }

        // Process 4 series allpass filters
        for (i in 0 until 4) {
            val delayL = allpassDelays[i]
            val bufL = allpassBuffersL[i]
            val idxL = allpassIndicesL[i]
            val bufSampleL = bufL[idxL]
            outL = -outL + bufSampleL
            bufL[idxL] = outL * 0.5f + bufSampleL * 0.5f
            allpassIndicesL[i] = (idxL + 1) % delayL

            val delayR = delayL + 23
            val bufR = allpassBuffersR[i]
            val idxR = allpassIndicesR[i]
            val bufSampleR = bufR[idxR]
            outR = -outR + bufSampleR
            bufR[idxR] = outR * 0.5f + bufSampleR * 0.5f
            allpassIndicesR[i] = (idxR + 1) % delayR
        }

        val wet1 = wetMix * (width * 0.5f + 0.5f)
        val wet2 = wetMix * ((1.0f - width) * 0.5f)

        output[0] = inputL * dryMix + outL * wet1 + outR * wet2
        output[1] = inputR * dryMix + outR * wet1 + outL * wet2
    }

    fun reset() {
        for (i in 0 until 8) {
            combBuffersL[i].fill(0.0f)
            combBuffersR[i].fill(0.0f)
            combIndicesL[i] = 0
            combIndicesR[i] = 0
            combFiltersL[i] = 0.0f
            combFiltersR[i] = 0.0f
        }
        for (i in 0 until 4) {
            allpassBuffersL[i].fill(0.0f)
            allpassBuffersR[i].fill(0.0f)
            allpassIndicesL[i] = 0
            allpassIndicesR[i] = 0
        }
    }
}
