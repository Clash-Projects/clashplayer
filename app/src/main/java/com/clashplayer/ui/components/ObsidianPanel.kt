package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.clashplayer.ui.theme.EdgeLight
import com.clashplayer.ui.theme.EdgeSpecular
import com.clashplayer.ui.theme.PanelFill
import kotlinx.coroutines.launch

/** Signature panel outline: soft corners with the top-end corner sheared off. */
class ObsidianShape(private val radius: Dp = 14.dp, private val cut: Dp = 18.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }.coerceAtMost(size.minDimension / 2)
        val c = with(density) { cut.toPx() }.coerceAtMost(size.minDimension / 2)
        val w = size.width; val h = size.height
        val rtl = layoutDirection == LayoutDirection.Rtl
        val p = Path().apply {
            moveTo(r, 0f)
            if (rtl) { lineTo(w - r, 0f); quadraticTo(w, 0f, w, r) } else { lineTo(w - c, 0f); lineTo(w, c) }
            lineTo(w, h - r); quadraticTo(w, h, w - r, h)
            lineTo(r, h); quadraticTo(0f, h, 0f, h - r)
            if (rtl) { lineTo(0f, c); lineTo(c, 0f) } else { lineTo(0f, r); quadraticTo(0f, 0f, r, 0f) }
            close()
        }
        return Outline.Generic(p)
    }
}

/**
 * Obsidian glass panel: a dark lacquered fill whose rim catches light along the top edge, and a specular
 * highlight that slides along the rim under the user's finger. Touch is only observed, never consumed.
 */
@Composable
fun ObsidianPanel(
    modifier: Modifier = Modifier,
    shape: Shape = ObsidianShape(),
    backgroundColor: Color = PanelFill,
    borderColor: Color = EdgeLight,
    borderWidth: Dp = 0.75.dp,
    content: @Composable BoxScope.() -> Unit
) {
    var touch by remember { mutableStateOf(Offset.Unspecified) }
    val glow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var wasPressed = false
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                        touch = change.position
                        if (change.pressed != wasPressed) {
                            wasPressed = change.pressed
                            val on = wasPressed
                            scope.launch { glow.animateTo(if (on) 1f else 0f, tween(if (on) 120 else 700)) }
                        }
                    }
                }
            }
            .drawWithCache {
                val path = (shape.createOutline(size, layoutDirection, this) as? Outline.Generic)?.path
                    ?: Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                val stroke = Stroke(borderWidth.toPx())
                val fill = Brush.verticalGradient(listOf(backgroundColor.copy(alpha = (backgroundColor.alpha + 0.08f).coerceAtMost(1f)), backgroundColor))
                val rim = Brush.linearGradient(
                    0f to EdgeSpecular.copy(alpha = 0.32f), 0.35f to borderColor, 0.8f to Color.Black.copy(alpha = 0.4f),
                    start = Offset.Zero, end = Offset(size.width * 0.55f, size.height)
                )
                onDrawBehind {
                    drawPath(path, fill)
                    drawPath(path, rim, style = stroke)
                    val g = glow.value
                    if (g > 0.01f && touch.isSpecified) {
                        drawPath(
                            path,
                            Brush.radialGradient(listOf(EdgeSpecular.copy(alpha = 0.9f * g), Color.Transparent), touch, size.maxDimension * 0.45f),
                            style = Stroke(borderWidth.toPx() * 1.6f)
                        )
                    }
                }
            }
            .clip(shape),
        content = content
    )
}
