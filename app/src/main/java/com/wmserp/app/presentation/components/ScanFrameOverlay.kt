package com.wmserp.app.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Viewfinder corners plus a moving scan line while [animate] is true; drawn over a camera preview. */
@Composable
fun ScanFrameOverlay(accent: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanline_progress",
    )
    Canvas(modifier = modifier.fillMaxSize().padding(28.dp)) {
        val w = size.width
        val h = size.height
        val len = minOf(w, h) * 0.14f
        val stroke = 5.dp.toPx()
        val corners = listOf(
            Offset(0f, 0f) to listOf(Offset(len, 0f), Offset(0f, len)),
            Offset(w, 0f) to listOf(Offset(w - len, 0f), Offset(w, len)),
            Offset(0f, h) to listOf(Offset(len, h), Offset(0f, h - len)),
            Offset(w, h) to listOf(Offset(w - len, h), Offset(w, h - len)),
        )
        corners.forEach { (origin, ends) ->
            ends.forEach { end -> drawLine(Color.White, origin, end, strokeWidth = stroke, cap = StrokeCap.Round) }
        }
        if (animate) {
            val y = h * progress
            drawLine(accent.copy(alpha = 0.9f), Offset(len * 0.6f, y), Offset(w - len * 0.6f, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
