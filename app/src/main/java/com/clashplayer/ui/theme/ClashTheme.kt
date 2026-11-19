package com.clashplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.clashplayer.R

// Obsidian: near-black with a cold blue cast; panels are lifted by light, not by colour
val Obsidian = Color(0xFF06070A)
val ObsidianRaised = Color(0xFF0D0F14)
val PanelFill = Color(0xFF0F1117)
val PanelFillLight = Color(0xFF181B23)
val EdgeLight = Color.White.copy(alpha = 0.07f)
val EdgeSpecular = Color.White.copy(alpha = 0.55f)

// Phosphor is the live signal; Ember marks peaks, warnings and the signature accent
val Phosphor = Color(0xFF7CF5C8)
val Ember = Color(0xFFFF8A3D)
val EmberDeep = Color(0xFF8A3A12)
val Signal = Color(0xFFFF4D6D)

val Ink = Color(0xFFF2F1EC)
val InkDim = Color(0xFF9B9C97)
val InkMuted = Color(0xFF5C5E5B)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Grotesk = FontFamily(
    variable(R.font.space_grotesk, 300), variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500), variable(R.font.space_grotesk, 700),
)
val Mono = FontFamily(
    variable(R.font.jetbrains_mono, 300), variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 600), variable(R.font.jetbrains_mono, 800),
)

/** Technical readouts: tracked-out mono caps. */
val HudStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 10.sp, letterSpacing = 0.14.em, color = InkDim)

private val ClashTypography = Typography(
    // Display is set tight and heavy; body is light and airy, so the hierarchy reads from weight, not size alone
    displayLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-0.04).em),
    headlineLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = (-0.03).em),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp, letterSpacing = (-0.025).em),
    titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, letterSpacing = (-0.01).em),
    bodyLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Light, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 12.sp, letterSpacing = 0.08.em),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 11.sp, letterSpacing = 0.1.em),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 10.sp, letterSpacing = 0.12.em),
)

private val DarkColorScheme = darkColorScheme(
    primary = Phosphor,
    secondary = Ember,
    tertiary = Signal,
    background = Obsidian,
    surface = ObsidianRaised,
    onPrimary = Obsidian,
    onBackground = Ink,
    onSurface = Ink
)

@Composable
fun ClashTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = ClashTypography, content = content)
}
