package com.clashplayer.dsp.model

data class EqualizerPreset(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val type: Int = TYPE_USER,
    val isParametric: Boolean = false,
    val bandGains: List<Float> = emptyList(),
    val parametricBands: List<EqualizerBand> = emptyList(),
    val bass: Float = 0.0f,
    val treble: Float = 0.0f,
    val preamp: Float = 0.0f
) {
    companion object {
        const val TYPE_USER = 0
        const val TYPE_BUILT_IN = 10
        const val TYPE_AUTO_EQ = 20

        val BUILT_IN_PRESETS: List<EqualizerPreset> by lazy { builtInPresets() }

        fun builtInPresets(): List<EqualizerPreset> = listOf(
            EqualizerPreset(1, "Flat", TYPE_BUILT_IN, bandGains = List(10) { 0.0f }),
            EqualizerPreset(2, "Bass Boost", TYPE_BUILT_IN, bandGains = listOf(6.0f, 5.0f, 4.0f, 2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), bass = 4.0f),
            EqualizerPreset(3, "Vocal Booster", TYPE_BUILT_IN, bandGains = listOf(-2.0f, -1.0f, 0.0f, 2.0f, 4.0f, 4.5f, 3.0f, 1.0f, 0.0f, -1.0f)),
            EqualizerPreset(4, "Electronic / Dance", TYPE_BUILT_IN, bandGains = listOf(5.0f, 4.0f, 2.0f, 0.0f, -1.5f, 2.0f, 3.5f, 4.0f, 4.5f, 5.0f), bass = 3.0f, treble = 2.0f),
            EqualizerPreset(5, "Rock", TYPE_BUILT_IN, bandGains = listOf(4.5f, 3.5f, 2.0f, -1.0f, -0.5f, 1.0f, 2.5f, 3.5f, 4.0f, 4.5f)),
            EqualizerPreset(6, "Acoustic", TYPE_BUILT_IN, bandGains = listOf(3.5f, 3.0f, 2.0f, 1.0f, 1.5f, 2.0f, 3.0f, 3.5f, 3.0f, 2.5f)),
            EqualizerPreset(7, "Treble Boost", TYPE_BUILT_IN, bandGains = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 1.5f, 3.0f, 5.0f, 6.0f, 6.5f), treble = 4.0f),
            EqualizerPreset(8, "Harman In-Ear 2019", TYPE_BUILT_IN, bandGains = listOf(7.7f, 5.6f, 1.4f, -1.6f, -1.4f, 0.0f, 5.8f, 8.9f, 3.6f, -10.2f), bass = 3.5f),
            EqualizerPreset(9, "Harman Over-Ear 2018", TYPE_BUILT_IN, bandGains = listOf(3.9f, 2.8f, -0.1f, -2.0f, -0.8f, 0.0f, 5.3f, 8.2f, 2.4f, -9.2f), bass = 2.0f)
        )
    }
}
