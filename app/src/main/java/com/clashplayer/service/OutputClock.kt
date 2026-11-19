package com.clashplayer.service

/** A snapshot of the live output path, for the format HUD and clock monitor. */
data class OutputClock(
    val source: String,        // e.g. "FLAC 24/96", "DSD64"
    val path: String,          // e.g. "USB UAC2 direct", "Android mixer"
    val nominalHz: Int,
    val measuredHz: Double?,   // the DAC's clock from feedback, or the rate the AudioTrack is consuming at
    val bits: Int,
    val channels: Int,
    val sync: String,
    val bitPerfect: Boolean,
    val transport: String,     // e.g. "DoP", "PCM", "DSP"
) {
    val driftPpm: Double? get() = measuredHz?.let { (it - nominalHz) / nominalHz * 1e6 }

    companion object {
        val Idle = OutputClock("—", "No output", 0, null, 0, 0, "—", false, "—")
    }
}
