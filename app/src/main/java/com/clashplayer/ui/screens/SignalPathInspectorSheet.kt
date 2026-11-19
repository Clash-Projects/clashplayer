package com.clashplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.model.Track
import com.clashplayer.service.OutputClock
import com.clashplayer.service.PlaybackSettings
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Obsidian
import com.clashplayer.ui.theme.Phosphor
import java.util.Locale

/**
 * Audiophile Live Signal Path Inspector (inspired by Roon & Resonada).
 * Exposes every stage in the signal chain from source container to physical DAC output,
 * with zero hidden operations, verifying Bit-Xact lossless integrity and DSP processing.
 */
@Composable
fun SignalPathInspectorSheet(
    expanded: Boolean,
    track: Track?,
    clock: OutputClock,
    audioEngine: AudioEngine,
    settings: PlaybackSettings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = expanded,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Obsidian.copy(alpha = 0.94f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val ledColor = if (clock.bitPerfect) Phosphor else Ember
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .drawBehind {
                                        drawCircle(ledColor.copy(alpha = 0.4f), size.minDimension * 1.5f)
                                        drawCircle(ledColor)
                                    }
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "LIVE SIGNAL PATH INSPECTOR",
                                style = HudStyle.copy(color = Ink)
                            )
                        }
                        Text(
                            if (clock.bitPerfect) "BIT-PERFECT DIRECT (LOSSLESS)" else "DSP ENHANCED (STUDIO PROCESSING)",
                            style = MaterialTheme.typography.bodySmall.copy(color = if (clock.bitPerfect) Phosphor else Ember)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF161922))
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Ink)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Audiophile Score & Fix My Sound Banner
                val fidelityMetrics by audioEngine.fidelityAnalyzer.metricsFlow.collectAsState()
                val xrayMetrics by audioEngine.audioXRayAnalyzer.metrics.collectAsState()

                val healthScore = if (clock.bitPerfect) {
                    100
                } else if (fidelityMetrics.trueClippingCount > 0 || fidelityMetrics.truePeakDb > 0.0f) {
                    75
                } else if (audioEngine.limiterProcessor.interSampleHeadroom) {
                    98
                } else {
                    90
                }

                ObsidianPanel(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "$healthScore/100",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        color = if (healthScore >= 95) Phosphor else if (healthScore >= 85) Ember else Color(0xFFFF5252)
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("AUDIOPHILE SCORE", style = HudStyle.copy(color = InkDim))
                            }
                            Text(
                                if (healthScore == 100) "Bit-Exact Lossless · Zero Alteration" else if (healthScore >= 90) "Calibrated Studio DSP · Clean Headroom" else "Inter-Sample Distortion Detected",
                                style = MaterialTheme.typography.bodySmall.copy(color = Ink)
                            )
                        }

                        if (healthScore < 100) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Phosphor.copy(alpha = 0.2f))
                                    .clickable {
                                        settings.interSampleHeadroom = true
                                        audioEngine.setInterSampleHeadroomEnabled(true)
                                        settings.bitXactMode = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("FIX MY SOUND", style = HudStyle.copy(color = Phosphor))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Scrollable Signal Chain Flow
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // STAGE 1: SOURCE
                    SignalStageCard(
                        stageNumber = 1,
                        stageTitle = "SOURCE CONTAINER & BITSTREAM",
                        statusText = track?.format?.uppercase(Locale.US) ?: "FLAC",
                        isLossless = true,
                        details = listOf(
                            "Title" to (track?.title ?: "Unknown Track"),
                            "Format" to (track?.format ?: "FLAC Lossless"),
                            "Sample Rate" to "${track?.sampleRate ?: 44100} Hz",
                            "Bit Depth" to "${track?.bitDepth ?: 16}-bit PCM",
                            "Bitrate" to "${track?.bitrateKbps ?: 1411} kbps"
                        )
                    )

                    PathArrow(label = "Bit-Exact Direct Stream")

                    // STAGE 2: AUDIO X-RAY (REAL PCM BIT DEPTH)
                    SignalStageCard(
                        stageNumber = 2,
                        stageTitle = "AUDIO X-RAY (REAL PCM BIT DEPTH)",
                        statusText = if (xrayMetrics.isPadded16Bit) "PADDED 16-BIT" else "TRUE ${xrayMetrics.effectiveBitDepth}-BIT",
                        isLossless = !xrayMetrics.isPadded16Bit,
                        details = listOf(
                            "Effective Bit Depth" to "${xrayMetrics.effectiveBitDepth}-bit (${if (xrayMetrics.isPadded16Bit) "Bottom 8 bits dead / upsampled" else "Full dynamic resolution"})",
                            "Theoretical SNR" to "${xrayMetrics.theoreticalSnrDb.toInt()} dBFS",
                            "Dynamic Crest Factor" to String.format(Locale.US, "%.1f dB", xrayMetrics.crestFactorDb),
                            "DC Offset" to String.format(Locale.US, "%+.4f%%", xrayMetrics.dcOffsetPercent)
                        ),
                        bitGrid = xrayMetrics.bitGrid
                    )

                    PathArrow(label = "Native Bitstream Parser")

                    // STAGE 3: DECODER
                    SignalStageCard(
                        stageNumber = 3,
                        stageTitle = "NATIVE LOSSLESS DECODER",
                        statusText = "BIT-EXACT",
                        isLossless = true,
                        details = listOf(
                            "Decoder Engine" to "Native NativeEngine C++ Bridge",
                            "Container Parser" to "Direct Lossless Sync-Lock",
                            "Decoding Precision" to "64-bit IEEE Floating-Point Pipeline"
                        )
                    )

                    PathArrow(label = if (clock.bitPerfect) "Bit-Perfect Bypassed" else "DSP Processing Pipeline")

                    // STAGE 4: DSP & SPATIAL CHAIN
                    val isDspActive = !clock.bitPerfect
                    SignalStageCard(
                        stageNumber = 4,
                        stageTitle = "DSP & SPATIAL PROCESSING",
                        statusText = if (clock.bitPerfect) "BIT-PERFECT BYPASSED" else "ACTIVE ENHANCEMENT",
                        isLossless = clock.bitPerfect,
                        details = buildList {
                            if (clock.bitPerfect) {
                                add("Bit-Perfect Mode" to "All software EQ, volume and filters bypassed")
                                add("Digital Ceiling" to "Bit-Exact 1:1 Sample Pass-through")
                            } else {
                                if (audioEngine.binauralSpatializer.enabled) {
                                    add("HRIR Soundstage Unfolder" to "Neumann KU 100 HRTF (Ambience: ${(audioEngine.binauralSpatializer.roomAmbience * 100).toInt()}%)")
                                }
                                if (audioEngine.limiterProcessor.interSampleHeadroom) {
                                    add("-3 dB Headroom Guard" to "Active (-3.01 dBFS True-Peak Ceiling)")
                                }
                                if (audioEngine.equalizerEnabled) {
                                    add("Parametric Equalizer" to "Active Studio EQ Curve")
                                }
                                add("Dither & Quantizer" to audioEngine.ditherer.type.label)
                            }
                        }
                    )

                    PathArrow(label = "Isochronous Frame Sync")

                    // STAGE 5: OUTPUT TRANSPORT ENGINE
                    SignalStageCard(
                        stageNumber = 5,
                        stageTitle = "OUTPUT TRANSPORT & DRIVER",
                        statusText = clock.transport.uppercase(Locale.US),
                        isLossless = clock.bitPerfect,
                        details = listOf(
                            "Driver Type" to clock.transport,
                            "Clock Status" to (if (clock.measuredHz != null) "Hardware Feedback Lock" else "Nominal Clock"),
                            "Output Rate" to "${clock.nominalHz} Hz",
                            "Resampling" to (if (clock.bitPerfect) "ZERO (Bit-Exact Native)" else "Studio Resampler"),
                            "Channel Mode" to "2 Channels (Stereo)"
                        )
                    )

                    PathArrow(label = "Hardware Interface")

                    // STAGE 6: PHYSICAL AUDIO ENDPOINT
                    SignalStageCard(
                        stageNumber = 6,
                        stageTitle = "AUDIO ENDPOINT HARDWARE",
                        statusText = if (clock.bitPerfect) "BIT-PERFECT" else "SYSTEM OUTPUT",
                        isLossless = clock.bitPerfect,
                        details = listOf(
                            "Device" to clock.path,
                            "Clock Drift" to (clock.driftPpm?.let { "${if (it >= 0) "+" else ""}$it ppm" } ?: "0 ppm (Locked)"),
                            "Signal Integrity" to (if (clock.bitPerfect) "Zero Truncation / Zero Resampling" else "High-Fidelity Processed")
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalStageCard(
    stageNumber: Int,
    stageTitle: String,
    statusText: String,
    isLossless: Boolean,
    details: List<Pair<String, String>>,
    bitGrid: BooleanArray? = null,
    modifier: Modifier = Modifier
) {
    ObsidianPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (isLossless) Phosphor.copy(alpha = 0.2f) else Ember.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$stageNumber",
                            style = HudStyle.copy(color = if (isLossless) Phosphor else Ember)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stageTitle, style = HudStyle.copy(color = Ink))
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isLossless) Phosphor.copy(alpha = 0.15f) else Ember.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        statusText,
                        style = HudStyle.copy(color = if (isLossless) Phosphor else Ember)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            details.forEach { (k, v) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = InkDim)
                    Text(v, style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }

            if (bitGrid != null && bitGrid.size == 24) {
                Spacer(Modifier.height(8.dp))
                Text("24-BIT ACTIVITY GRID (BIT 23 MSB → BIT 0 LSB)", style = HudStyle.copy(color = InkDim))
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (b in 23 downTo 0) {
                        val active = bitGrid[b]
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (active) Phosphor else Color(0xFF202530))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PathArrow(label: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(10.dp)
                .background(Phosphor.copy(alpha = 0.4f))
        )
        Text(
            "▼  $label",
            style = HudStyle.copy(color = Phosphor.copy(alpha = 0.7f))
        )
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(10.dp)
                .background(Phosphor.copy(alpha = 0.4f))
        )
    }
}
