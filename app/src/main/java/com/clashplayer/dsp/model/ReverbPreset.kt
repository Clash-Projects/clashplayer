package com.clashplayer.dsp.model

enum class ReverbPreset(val roomSize: Float, val damping: Float, val wetMix: Float) {
    STUDIO(0.30f, 0.60f, 0.12f),
    ROOM(0.45f, 0.50f, 0.18f),
    HALL(0.70f, 0.40f, 0.25f),
    CATHEDRAL(0.88f, 0.30f, 0.32f),
    SPACE(0.97f, 0.15f, 0.40f)
}
