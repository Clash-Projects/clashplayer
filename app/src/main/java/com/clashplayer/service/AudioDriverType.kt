package com.clashplayer.service

enum class AudioDriverType(val id: String, val title: String, val description: String) {
    BIT_PERFECT_DIRECT("bit_perfect", "Bit-Perfect Direct", "Follows track sample rate directly with 0 resampling"),
    AAUDIO("aaudio", "AAudio Low Latency", "Direct high-performance Android HAL audio path with MMAP"),
    OPENSL_ES("opensl_es", "OpenSL ES Native", "Standard native buffer queue engine"),
    AUDIOTRACK_FLOAT("audiotrack_float", "AudioTrack 32-bit Float", "High-precision 32-bit floating-point audio engine");

    companion object {
        fun fromId(id: String): AudioDriverType = entries.find { it.id == id } ?: AUDIOTRACK_FLOAT
    }
}
