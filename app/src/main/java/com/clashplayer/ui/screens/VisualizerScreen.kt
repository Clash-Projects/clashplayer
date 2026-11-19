package com.clashplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.ui.components.AudioSpectrumView
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.components.VisualizerMode
import com.clashplayer.ui.theme.ObsidianRaised
import com.clashplayer.ui.theme.Phosphor
import com.clashplayer.ui.theme.Signal
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim

@Composable
fun VisualizerScreen(
    audioEngine: AudioEngine,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fftData by audioEngine.fftSpectrumFlow.collectAsState()
    val waveData by audioEngine.waveformFlow.collectAsState()

    var visualMode by remember { mutableStateOf(VisualizerMode.SPECTRUM_BARS) }
    var colorTheme by remember { mutableStateOf(0) }

    val (accent, secondary) = when (colorTheme) {
        0 -> Phosphor to Ember
        1 -> Ember to Signal
        else -> Signal to Phosphor
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Full Canvas Visualizer
        AudioSpectrumView(
            magnitudes = fftData,
            waveform = waveData,
            mode = visualMode,
            accentColor = accent,
            secondaryColor = secondary,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp, top = 80.dp)
        )

        // Top Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ObsidianRaised.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink
                )
            }

            Text(
                text = "Audio Visualizer",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )

            Spacer(modifier = Modifier.size(40.dp))
        }

        // Bottom Controls HUD
        ObsidianPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .align(Alignment.BottomCenter)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                val modes = listOf(VisualizerMode.SPECTRUM_BARS, VisualizerMode.OSCILLOSCOPE, VisualizerMode.CIRCULAR_AURORA, VisualizerMode.STFT_SPECTROGRAM)
                TickSelector(listOf("Bars", "Waveform", "Aurora", "STFT Spectrogram"), modes.indexOf(visualMode), { visualMode = modes[it] })
                TickSelector(listOf("Phosphor", "Ember", "Signal"), colorTheme, { colorTheme = it })
            }
        }
    }
}
