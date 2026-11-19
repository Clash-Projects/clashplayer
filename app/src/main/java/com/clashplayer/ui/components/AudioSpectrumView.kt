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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.clashplayer.ui.theme.Phosphor
import com.clashplayer.ui.theme.Ember
import kotlin.math.cos
import kotlin.math.sin

enum class VisualizerMode {
    SPECTRUM_BARS,
    OSCILLOSCOPE,
    CIRCULAR_AURORA
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
        }
    }
}
