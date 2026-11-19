package com.clashplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.DitherType
import com.clashplayer.dsp.ReplayGainMode
import com.clashplayer.service.OutputDevice
import com.clashplayer.service.OutputProfile
import com.clashplayer.service.PlaybackSettings
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Phosphor
import kotlinx.coroutines.flow.StateFlow
import com.clashplayer.ui.components.NotchToggle
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.components.RotaryKnob
import com.clashplayer.ui.components.SectionRule
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim

/** Transitions, tempo, loudness, output precision and interruption handling. */
@Composable
internal fun EngineTab(
    audioEngine: AudioEngine,
    settings: PlaybackSettings,
    onReplayGainChanged: () -> Unit,
    output: OutputControls? = null,
    onOpenSignalPath: () -> Unit = {}
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionRule(1, "Transitions")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Toggle("Gapless", "Same-format tracks run on without reopening the output, so nothing is lost between them.", settings.gapless) { settings.gapless = it }
                    Toggle("DJ AutoMix", "Beat-aware mixing: downbeat phase-locking, sub-bass swap and Linkwitz-Riley spectral crossover.", settings.autoMixEnabled) { settings.autoMixEnabled = it }
                    if (settings.autoMixEnabled) {
                        Ticks("AutoMix Bars", "Transition length in musical bars.", listOf(8, 16), settings.autoMixBars, { "$it bars" }) { settings.autoMixBars = it }
                        Toggle("AutoMix Tempo Match", "Slightly re-time incoming tempo (within ±8%) to match outgoing groove.", settings.autoMixTempoMatch) { settings.autoMixTempoMatch = it }
                    }
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
                    Ticks("Dither", "Used when samples are changed on the way to an integer output (resampling, software volume). F- and E-weighted apply at 44.1 and 48 kHz; Shibata types are designed for any rate.",
                        DitherType.entries, audioEngine.ditherer.type, { it.label }) { audioEngine.ditherer.type = it }
                    Toggle("Bit Xact USB Direct", "Pure 1:1 bit-exact streaming to USB DAC. Bypasses software mixers and filters for uncompromising bit-exact transmission.", settings.bitXactMode) {
                        settings.bitXactMode = it
                    }
                    Toggle("-3 dB Inter-Sample Headroom", "Leaves +3 dB true-peak headroom margin to prevent DAC reconstruction filters from clipping on hot commercial masters.", settings.interSampleHeadroom) {
                        settings.interSampleHeadroom = it
                        audioEngine.setInterSampleHeadroomEnabled(it)
                    }
                    Toggle("Safe Volume Ramp & DAC Guard", "Smooth volume ramping and clamp on DAC connect to protect ears and gear from unexpected volume blasts.", settings.safeVolumeRamp) {
                        settings.safeVolumeRamp = it
                    }
                    Toggle("Resample for USB DAC", "If the DAC lacks a track's rate, convert to its cleanest rate and stay on USB. Takes effect at the next track.", settings.usbResample) { settings.usbResample = it }
                    Ticks("USB warm-up", "Silence after opening the DAC, for ones that mute or distort their first moments.", listOf(0, 100, 250, 500), settings.usbWarmupMs,
                        { if (it == 0) "Off" else "$it ms" }) { settings.usbWarmupMs = it }
                }
            }
        }
        item {
            SectionRule(5, "DSD")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Toggle("Native DSD", "Send DSD files raw when the DAC has a native DSD mode; otherwise DoP.", settings.preferNativeDsd) { settings.preferNativeDsd = it }
                    Toggle("DSD remaster", "Convert PCM tracks to DSD after the DSP chain and send it as DoP. USB direct only; heavy on the CPU at 128 and 256.", settings.dsdRemaster) { settings.dsdRemaster = it }
                    Ticks("Remaster rate", "Supports DSD64, DSD128, DSD256, DSD512 and DSD1024 native rates.", listOf(64, 128, 256, 512, 1024), settings.remasterMultiple, { "DSD$it" }) { settings.remasterMultiple = it }
                    Toggle("48 kHz-based DSD", "Use 48 kHz-family DSD rates for 48 kHz-family tracks.", settings.remasterAllow48k) { settings.remasterAllow48k = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        var g by remember { mutableStateOf(settings.remasterGainDb) }
                        RotaryKnob("Remaster gain", g, -12f, 0f, "dB", onValueChange = { g = it; settings.remasterGainDb = it }, size = 72.dp)
                    }
                }
            }
        }
        item {
            SectionRule(6, "Bluetooth")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Toggle("No DVC on Bluetooth", "Headsets usually set their own volume; software volume on top would make it too quiet.", settings.noDvcOnBluetooth) {
                        settings.noDvcOnBluetooth = it; output?.reapply?.invoke()
                    }
                    Toggle("LDAC 990 kbps Quality Priority", "Locks LDAC codec to maximum 990/909 kbps bitrate mode (24-bit/96kHz) for uncompromising Hi-Res Bluetooth fidelity.", settings.ldacQualityLock) {
                        settings.ldacQualityLock = it
                    }
                    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { output?.reapply?.invoke() }
                    Ticks("Codec sample rate", "Asks the headset codec for this rate. Many phones only allow system apps to change it; the result shows below.",
                        listOf(0, -1, 44100, 48000, 96000), settings.btCodecRate, { when (it) { 0 -> "Leave"; -1 -> "Follow track"; else -> "${it / 1000.0}k".replace(".0k", "k") } }) {
                        settings.btCodecRate = it
                        if (it != 0 && android.os.Build.VERSION.SDK_INT >= 31) askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT) else output?.reapply?.invoke()
                    }
                    output?.btResult?.collectAsState()?.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Phosphor) }
                }
            }
        }
        if (output != null) item { DeviceProfile(output) }
        item {
            val metrics by audioEngine.fidelityAnalyzer.metricsFlow.collectAsState()
            SectionRule(8, "Mastering & Fidelity")
            Spacer(Modifier.height(10.dp))
            ObsidianPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("DYNAMIC RANGE", style = HudStyle.copy(color = InkDim))
                            Text(
                                "DR${metrics.dynamicRange}",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = if (metrics.dynamicRange >= 10) Phosphor else if (metrics.dynamicRange >= 7) Ember else Color(0xFFFF5252)
                                )
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("INTEGRATED LOUDNESS", style = HudStyle.copy(color = InkDim))
                            Text(
                                String.format(java.util.Locale.US, "%.1f LUFS", metrics.lufs),
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("4x TRUE-PEAK (dBTP)", style = HudStyle.copy(color = InkDim))
                            val tpText = String.format(java.util.Locale.US, "%.2f dBTP", metrics.truePeakDb)
                            Text(
                                tpText,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (metrics.truePeakDb > 0.0f) Color(0xFFFF5252) else Ink
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("INTER-SAMPLE CLIPS", style = HudStyle.copy(color = InkDim))
                            Text(
                                "${metrics.trueClippingCount} overs",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (metrics.trueClippingCount > 0) Color(0xFFFF5252) else Phosphor
                            )
                        }
                    }

                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("STEREO PHASE CORRELATION", style = HudStyle.copy(color = InkDim))
                            Text(
                                String.format(java.util.Locale.US, "%+.2f", metrics.stereoCorrelation),
                                style = HudStyle.copy(color = if (metrics.stereoCorrelation >= 0f) Phosphor else Color(0xFFFF5252))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        CorrelationBar(metrics.stereoCorrelation)
                    }

                    Column {
                        Text("SPECTRAL PURITY & AUTHENTIC HI-RES", style = HudStyle.copy(color = InkDim))
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (metrics.isAuthenticHiRes) Phosphor.copy(alpha = 0.15f) else Color(0xFFFF5252).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (metrics.isAuthenticHiRes) "AUTHENTIC ${metrics.spectralCutoffHz} Hz" else "SPECTRALLY CONSTRAINED",
                                    style = HudStyle.copy(
                                        color = if (metrics.isAuthenticHiRes) Phosphor else Color(0xFFFF5252)
                                    )
                                )
                            }
                            if (metrics.fakeHiResWarning != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    metrics.fakeHiResWarning!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFF5252)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Phosphor.copy(alpha = 0.12f))
                            .clickable(onClick = onOpenSignalPath)
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("VIEW LIVE SIGNAL PATH INSPECTOR", style = HudStyle.copy(color = Phosphor))
                    }
                }
            }
        }
        item {
            SectionRule(9, "Interruptions")
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

/** What the Engine tab needs from the player for device profiles and Bluetooth. */
class OutputControls(
    val current: StateFlow<OutputDevice>,
    val btResult: StateFlow<String?>,
    val profileFor: (OutputDevice) -> OutputProfile?,
    val setProfile: (OutputDevice, OutputProfile?) -> Unit,
    val reapply: () -> Unit,
)

@Composable
private fun DeviceProfile(output: OutputControls) {
    val device by output.current.collectAsState()
    SectionRule(7, "This output")
    Spacer(Modifier.height(10.dp))
    ObsidianPanel(Modifier.fillMaxWidth()) {
        key(device.key) {
            var profile by remember { mutableStateOf(output.profileFor(device)) }
            fun set(p: OutputProfile?) { profile = p; output.setProfile(device, p) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(device.label.uppercase(), style = HudStyle.copy(color = Phosphor))
                Toggle("Own settings for this device", "Remembered per device; others keep the global settings.", profile != null) { set(if (it) OutputProfile() else null) }
                profile?.let { p ->
                    Toggle("No DVC", "Leave volume to the device or system.", p.noDvc) { set(p.copy(noDvc = it)) }
                    Toggle("No EQ and tone", "Equalizer, tone and preamp off for this device.", p.noEqTone) { set(p.copy(noEqTone = it)) }
                    Toggle("No headroom reduction", "Full level when DVC is off. EQ boosts may clip.", p.noHeadroom) { set(p.copy(noHeadroom = it)) }
                    Toggle("Allow DoP", "DSD over PCM and DSD remaster. From the next track.", p.allowDop) { set(p.copy(allowDop = it)) }
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

@Composable
private fun CorrelationBar(correlation: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF161A22))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = size.width / 2f
            drawLine(
                color = Color.Gray.copy(alpha = 0.5f),
                start = Offset(center, 0f),
                end = Offset(center, size.height),
                strokeWidth = 1.dp.toPx()
            )
            val norm = ((correlation + 1f) / 2f).coerceIn(0f, 1f)
            val markerX = norm * size.width
            val barColor = if (correlation >= 0f) Phosphor else Color(0xFFFF5252)
            if (markerX >= center) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(center, 0f),
                    size = Size((markerX - center).coerceAtLeast(1f), size.height)
                )
            } else {
                drawRect(
                    color = barColor,
                    topLeft = Offset(markerX, 0f),
                    size = Size((center - markerX).coerceAtLeast(1f), size.height)
                )
            }
        }
    }
}
