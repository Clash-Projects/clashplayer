package com.clashplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkMuted
import com.clashplayer.ui.theme.Phosphor
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * A row of mono caps labels with one ember tick under the selection that slides (on a spring) between labels.
 * Replaces tab rows and chip groups; scrolls horizontally when it doesn't fit.
 */
@Composable
fun TickSelector(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    LaunchedEffect(selected, bounds[selected]) {
        val b = bounds[selected] ?: return@LaunchedEffect
        val first = w.value == 0f
        if (first) { x.snapTo(b.first); w.snapTo(b.second) } else {
            launch { x.animateTo(b.first, spring(dampingRatio = 0.7f, stiffness = 500f)) }
            w.animateTo(b.second, spring(dampingRatio = 0.7f, stiffness = 500f))
        }
    }
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .drawBehind {
                if (w.value > 0f) {
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                    drawRect(Ember, Offset(x.value, size.height - 2.dp.toPx()), Size(w.value, 2.dp.toPx()))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Ember.copy(alpha = 0.18f)), size.height - 14.dp.toPx(), size.height),
                        Offset(x.value, size.height - 14.dp.toPx()), Size(w.value, 12.dp.toPx()))
                }
            }
    ) {
        options.forEachIndexed { i, label ->
            Text(
                label.uppercase(Locale.US),
                style = HudStyle.copy(color = if (i == selected) Ink else InkMuted),
                modifier = Modifier
                    .onPlaced { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                        if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(i)
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
}

/** Two-position toggle: a recessed slot with a lit slider; replaces the stock switch. */
@Composable
fun NotchToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f)) }
    Box(
        modifier
            .size(44.dp, 24.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress); onCheckedChange(!checked)
            }
            .drawBehind {
                val h = size.height
                drawRoundRect(Color.Black.copy(alpha = 0.7f), size = size, cornerRadius = CornerRadius(4.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(0f, 1f), size, CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                val p = pos.value
                val kw = size.width * 0.46f
                val kx = 2.dp.toPx() + (size.width - kw - 4.dp.toPx()) * p
                val tint = Color(
                    red = InkMuted.red + (Phosphor.red - InkMuted.red) * p,
                    green = InkMuted.green + (Phosphor.green - InkMuted.green) * p,
                    blue = InkMuted.blue + (Phosphor.blue - InkMuted.blue) * p
                )
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF8C9199), Color(0xFF3A3D43))), Offset(kx, 2.dp.toPx()), Size(kw, h - 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                drawLine(tint.copy(alpha = 0.4f), Offset(kx + kw / 2, 6.dp.toPx()), Offset(kx + kw / 2, h - 6.dp.toPx()), 4.dp.toPx())
                drawLine(tint, Offset(kx + kw / 2, 6.dp.toPx()), Offset(kx + kw / 2, h - 6.dp.toPx()), 1.5.dp.toPx())
            }
    )
}
