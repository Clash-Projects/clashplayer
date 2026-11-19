package com.clashplayer.dsp.model

/**
 * Filter types for parametric and graphic equalizers.
 */
enum class FilterType(val id: Int, val displayName: String) {
    PEAKING(0, "Peaking (Bell)"),
    LOW_SHELF(1, "Low Shelf"),
    HIGH_SHELF(2, "High Shelf"),
    LOW_PASS(3, "Low Pass"),
    HIGH_PASS(4, "High Pass"),
    BAND_PASS(5, "Band Pass"),
    NOTCH(6, "Notch (Band Stop)");

    companion object {
        fun fromId(id: Int): FilterType = entries.find { it.id == id } ?: PEAKING
    }
}
