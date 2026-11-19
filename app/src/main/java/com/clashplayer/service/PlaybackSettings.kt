package com.clashplayer.service

import com.clashplayer.dsp.ReplayGainMode

/** Player-level options read by the audio loop. Set from the UI; the loop picks changes up at the next buffer. */
class PlaybackSettings {
    @Volatile var gapless = true
    /** Crossfade between tracks of the same sample rate; 0 is off. Never applied to bit-perfect output. */
    @Volatile var crossfadeMs = 0
    /** Fade on play/pause; also a short fade-in after seeks. DSP path only. */
    @Volatile var fades = true
    @Volatile var fadeMs = 120
    @Volatile var seekFadeMs = 40
    /** Silence inserted between tracks. */
    @Volatile var silenceMs = 0
    @Volatile var replayGain = ReplayGainMode.OFF
    @Volatile var replayGainPreampDb = 0f
    /** Gain for files without ReplayGain tags. */
    @Volatile var untaggedGainDb = 0f
    @Volatile var preventClipping = true
    /** Keep playing through short focus losses (notifications, navigation prompts). */
    @Volatile var ignoreShortFocusLoss = false
    /** Pause instead of lowering the volume when another app asks to duck. */
    @Volatile var pauseInsteadOfDuck = false
    /** USB direct: when the DAC lacks the track's rate, resample to the cleanest rate it has instead of leaving USB. */
    @Volatile var usbResample = true
    /** USB direct: play this much silence after opening, for DACs that mute or distort their first moments. */
    @Volatile var usbWarmupMs = 0
}
