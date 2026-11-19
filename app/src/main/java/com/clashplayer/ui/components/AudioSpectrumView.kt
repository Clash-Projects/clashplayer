package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.clashplayer.ui.theme.Phosphor
import com.clashplayer.ui.theme.Ember
import kotlin.math.cos
import kotlin.math.sin

enum class VisualizerMode {
    SPECTRUM_BARS,
    OSCILLOSCOPE,
    CIRCULAR_AURORA,
    STFT_SPECTROGRAM
}

@Composable
fun AudioSpectrumView(
    magnitudes: FloatArray,
    waveform: FloatArray = FloatArray(0),
    mode: VisualizerMode = VisualizerMode.SPECTRUM_BARS,
    modifier: Modifier = Modifier,
    barCount: Int = 64,
    accentColor: Color = Phosphor,
    secondaryColor: Color = Ember
) {
    val peakDecay = remember { FloatArray(barCount) { 0f } }

    val spectrogramHistory = remember { Array(60) { FloatArray(barCount) } }
    var spectrogramHead by remember { mutableIntStateOf(0) }

    LaunchedEffect(magnitudes) {
        if (mode == VisualizerMode.STFT_SPECTROGRAM && magnitudes.isNotEmpty()) {
            val target = spectrogramHistory[spectrogramHead % spectrogramHistory.size]
            val count = minOf(target.size, magnitudes.size)
            System.arraycopy(magnitudes, 0, target, 0, count)
            spectrogramHead++
        }
    }

    val gradient = remember(accentColor, secondaryColor) {
        Brush.verticalGradient(
            colors = listOf(secondaryColor, accentColor, accentColor.copy(alpha = 0.2f))
        )
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        when (mode) {
            VisualizerMode.SPECTRUM_BARS -> {
                val totalBars = barCount.coerceAtMost(magnitudes.size)
                if (totalBars == 0) return@Canvas

                val barWidth = (width / totalBars) * 0.72f
                val barSpacing = (width / totalBars) * 0.28f

                for (i in 0 until totalBars) {
                    val rawVal = magnitudes.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    
                    if (rawVal > peakDecay[i]) {
                        peakDecay[i] = rawVal
                    } else {
                        peakDecay[i] = (peakDecay[i] - 0.015f).coerceAtLeast(0f)
                    }

                    val barHeight = (rawVal * height * 0.92f).coerceAtLeast(4f)
                    val x = i * (barWidth + barSpacing) + barSpacing / 2f
                    val y = height - barHeight

                    drawRoundRect(
                        brush = gradient,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )

                    val peakY = height - (peakDecay[i] * height * 0.92f)
                    drawCircle(
                        color = secondaryColor.copy(alpha = 0.85f),
                        radius = barWidth / 2f,
                        center = Offset(x + barWidth / 2f, peakY)
                    )
                }
            }

            VisualizerMode.OSCILLOSCOPE -> {
                if (waveform.isEmpty()) return@Canvas
                val path = Path()
                val step = width / waveform.size.toFloat()
                val centerY = height / 2f

                waveform.forEachIndexed { index, sample ->
                    val x = index * step
                    val y = centerY + (sample.coerceIn(-1f, 1f) * centerY * 0.85f)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(listOf(secondaryColor, accentColor, secondaryColor)),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )
            }

            VisualizerMode.CIRCULAR_AURORA -> {
                val total = barCount.coerceAtMost(magnitudes.size)
                if (total == 0) return@Canvas

                val centerX = width / 2f
                val centerY = height / 2f
                val baseRadius = (minOf(width, height) / 3.2f)
                val angleStep = (2f * Math.PI.toFloat()) / total

                for (i in 0 until total) {
                    val mag = magnitudes.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    val angle = i * angleStep
                    val startR = baseRadius
                    val endR = baseRadius + (mag * baseRadius * 0.9f)

                    val startX = centerX + cos(angle) * startR
                    val startY = centerY + sin(angle) * startR
                    val endX = centerX + cos(angle) * endR
                    val endY = centerY + sin(angle) * endR

                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(secondaryColor.copy(alpha = 0.4f), accentColor),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY)
                        ),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }
            }

            VisualizerMode.STFT_SPECTROGRAM -> {
                val numCols = spectrogramHistory.size
                val colWidth = width / numCols.toFloat()
                val numRows = barCount
                val rowHeight = height / numRows.toFloat()

                for (c in 0 until numCols) {
                    val sliceIndex = (spectrogramHead - numCols + c + numCols * 10) % numCols
                    val slice = spectrogramHistory[sliceIndex]
                    val x = c * colWidth

                    for (r in 0 until numRows) {
                        val mag = slice[r].coerceIn(0f, 1f)
                        val y = height - (r + 1) * rowHeight

                        val cellColor = when {
                            mag < 0.08f -> Color(0xFF06080C)
                            mag < 0.25f -> Color(0xFF0A2239)
                            mag < 0.50f -> accentColor.copy(alpha = 0.5f + mag)
                            mag < 0.75f -> secondaryColor.copy(alpha = 0.7f + 0.3f * mag)
                            else -> Color(0xFFFFFFFF)
                        }

                        drawRect(
                            color = cellColor,
                            topLeft = Offset(x, y),
                            size = Size(colWidth + 0.6f, rowHeight + 0.6f)
                        )
                    }
                }

                // Cutoff Guideline: 16 kHz (MP3 Cutoff)
                val y16k = height * (1f - 16000f / 48000f)
                drawLine(
                    color = Color(0xFFFF5252).copy(alpha = 0.75f),
                    start = Offset(0f, y16k),
                    end = Offset(width, y16k),
                    strokeWidth = 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )

                // Cutoff Guideline: 20 kHz (320kbps Cutoff)
                val y20k = height * (1f - 20000f / 48000f)
                drawLine(
                    color = Ember.copy(alpha = 0.75f),
                    start = Offset(0f, y20k),
                    end = Offset(width, y20k),
                    strokeWidth = 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )

                // Cutoff Guideline: 22.05 kHz (CD Redbook Nyquist)
                val y22k = height * (1f - 22050f / 48000f)
                drawLine(
                    color = Phosphor.copy(alpha = 0.85f),
                    start = Offset(0f, y22k),
                    end = Offset(width, y22k),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 8f))
                )
            }
        }
    }
}
