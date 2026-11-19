package com.clashplayer.dsp

import kotlin.math.cos
import kotlin.math.sin

class StereoProcessor {
    var stereoWidth: Float = 1.0f  // 0.0 (Mono) to 2.0 (Double Wide)
    var balance: Float = 0.0f      // -1.0 (Left) to +1.0 (Right)
    var forceMono: Boolean = false

    fun process(left: Float, right: Float, output: FloatArray) {
        if (forceMono || stereoWidth <= 0.0f) {
            val mono = (left + right) * 0.5f
            output[0] = mono
            output[1] = mono
            applyBalance(output)
            return
        }

        // Mid-Side extraction
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f

        // Apply width expansion
        val widenedSide = side * stereoWidth

        output[0] = mid + widenedSide
        output[1] = mid - widenedSide

        applyBalance(output)
    }

    private fun applyBalance(output: FloatArray) {
        if (balance == 0.0f) return

        val normBalance = balance.coerceIn(-1.0f, 1.0f)
        // Constant power pan curve
        val angle = (normBalance + 1.0f) * (Math.PI / 4.0)
        val leftGain = cos(angle).toFloat() * 1.4142f
        val rightGain = sin(angle).toFloat() * 1.4142f

        output[0] *= leftGain
        output[1] *= rightGain
    }
}
