package com.clashplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.DitherType
import com.clashplayer.dsp.ReplayGainMode
import com.clashplayer.service.PlaybackSettings
import com.clashplayer.ui.components.NotchToggle
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.components.RotaryKnob
import com.clashplayer.ui.components.SectionRule
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim

/** Transitions, tempo, loudness, output precision and interruption handling. */
@Composable
internal fun EngineTab(audioEngine: AudioEngine, settings: PlaybackSettings, onReplayGainChanged: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionRule(1, "Transitions")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Toggle("Gapless", "Same-format tracks run on without reopening the output, so nothing is lost between them.", settings.gapless) { settings.gapless = it }
                    Ticks("Crossfade", "Mixed under the next track when the rates match. Off for bit-perfect output.",
                        listOf(0, 2000, 4000, 6000, 10000), settings.crossfadeMs, { if (it == 0) "Off" else "${it / 1000}s" }) { settings.crossfadeMs = it }
                    Toggle("Fade play and pause", "Short ramps instead of hard starts and stops, plus a fade-in after seeking.", settings.fades) { settings.fades = it }
                    Ticks("Silence between tracks", null, listOf(0, 500, 1000, 2000), settings.silenceMs, { if (it == 0) "None" else "${it / 1000.0}s".replace(".0s", "s") }) { settings.silenceMs = it }
                }
            }
        }
        item {
            SectionRule(2, "Tempo")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Ticks(null, "Speed changes without changing pitch. DSP path only.", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f),
                        audioEngine.tempoProcessor.tempo, { "${it}x".replace(".0x", "x") }) { audioEngine.tempoProcessor.tempo = it }
                }
            }
        }
        item {
            SectionRule(3, "Loudness")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Ticks("ReplayGain", "Reads FLAC and ID3 loudness tags. Album falls back to track gain.", ReplayGainMode.entries, settings.replayGain, { it.label }) {
                        settings.replayGain = it; onReplayGainChanged()
                    }
                    Toggle("Prevent clipping", "Never let the gain push the tagged peak past full scale.", settings.preventClipping) { settings.preventClipping = it; onReplayGainChanged() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        var pre by remember { mutableStateOf(settings.replayGainPreampDb) }
                        var untagged by remember { mutableStateOf(settings.untaggedGainDb) }
                        RotaryKnob("RG preamp", pre, -12f, 12f, "dB", onValueChange = { pre = it; settings.replayGainPreampDb = it; onReplayGainChanged() })
                        RotaryKnob("Untagged", untagged, -12f, 6f, "dB", onValueChange = { untagged = it; settings.untaggedGainDb = it; onReplayGainChanged() })
                    }
                    Toggle("Auto headroom", "Near full volume, pull EQ and tone boosts back by exactly what would clip.", audioEngine.smoothGains) { audioEngine.smoothGains = it }
                    Toggle("Mono", "Sum both channels.", audioEngine.stereoProcessor.forceMono) { audioEngine.stereoProcessor.forceMono = it }
                }
            }
        }
        item {
            val c = audioEngine.compressor
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Toggle("Compressor", "Stereo-linked, soft knee. Evens out loud and quiet passages.", c.enabled) { c.enabled = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        var th by remember { mutableStateOf(c.thresholdDb) }
                        var ratio by remember { mutableStateOf(c.ratio) }
                        var makeup by remember { mutableStateOf(c.makeupDb) }
                        RotaryKnob("Threshold", th, -40f, 0f, "dB", onValueChange = { th = it; c.thresholdDb = it }, size = 72.dp)
                        RotaryKnob("Ratio", ratio, 1f, 10f, ":1", onValueChange = { ratio = it; c.ratio = it }, size = 72.dp)
                        RotaryKnob("Makeup", makeup, 0f, 12f, "dB", onValueChange = { makeup = it; c.makeupDb = it }, size = 72.dp)
                    }
                }
            }
        }
        item {
            SectionRule(4, "Precision")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Ticks("Dither", "Used when samples are changed on the way to an integer output (resampling, software volume). Shaped types apply at 44.1 and 48 kHz.",
                        DitherType.entries, audioEngine.ditherer.type, { it.label }) { audioEngine.ditherer.type = it }
                    Toggle("Resample for USB DAC", "If the DAC lacks a track's rate, convert to its cleanest rate and stay on USB. Takes effect at the next track.", settings.usbResample) { settings.usbResample = it }
                    Ticks("USB warm-up", "Silence after opening the DAC, for ones that mute or distort their first moments.", listOf(0, 100, 250, 500), settings.usbWarmupMs,
                        { if (it == 0) "Off" else "$it ms" }) { settings.usbWarmupMs = it }
                }
            }
        }
        item {
            SectionRule(5, "Interruptions")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Toggle("Keep playing through prompts", "Ignore short interruptions such as notifications and navigation.", settings.ignoreShortFocusLoss) { settings.ignoreShortFocusLoss = it }
                    Toggle("Pause instead of ducking", "Pause when another app would lower the volume.", settings.pauseInsteadOfDuck) { settings.pauseInsteadOfDuck = it }
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = InkDim)
        }
        Spacer(Modifier.width(12.dp))
        NotchToggle(on, { on = it; onChange(it) })
    }
}

@Composable
private fun <T> Ticks(title: String?, desc: String?, options: List<T>, initial: T, label: (T) -> String, onChange: (T) -> Unit) {
    var sel by remember { mutableStateOf(options.indexOf(initial).coerceAtLeast(0)) }
    Column {
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink) }
        desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = InkDim) }
        TickSelector(options.map(label), sel, { sel = it; onChange(options[it]) }, Modifier.padding(top = 4.dp))
    }
}
