package com.clashplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CoverReflection(
    painter: Painter,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    reflectionAlpha: Float = 0.35f
) {
    Column(modifier = modifier) {
        // Main Artwork
        Image(
            painter = painter,
            contentDescription = "Cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.0f)
                .clip(RoundedCornerShape(cornerRadius))
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Flipped gradient reflection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius))
        ) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.0f)
                    .scale(scaleX = 1.0f, scaleY = -1.0f)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 1.0f - reflectionAlpha),
                                Color.Black
                            )
                        )
                    )
            )
        }
    }
}
