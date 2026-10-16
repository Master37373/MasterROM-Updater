package com.masterrom.updater.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masterrom.updater.ui.theme.LocalGlass

/**
 * Gradient progress ring with a soft glow, the percentage in the middle and an optional caption.
 * [progress] < 0 shows a spinning arc instead (while searching servers).
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    caption: String? = null,
    active: Boolean = true,
    size: Dp = 176.dp,
) {
    val g = LocalGlass.current
    val indeterminate = progress < 0f
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(900),
        label = "ring",
    )
    val transition = rememberInfiniteTransition(label = "ringSpin")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    val colors = listOf(g.violet, g.accent, g.accent2, g.violet)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val radius = (this.size.minDimension - stroke) / 2f - 6.dp.toPx()
            val c = center
            val glowAlpha = if (active) 0.30f * pulse else 0.12f
            drawCircle(
                brush = Brush.radialGradient(listOf(g.accent.copy(alpha = glowAlpha), Color.Transparent), c, radius * 1.35f),
                radius = radius * 1.35f,
                center = c,
            )
            drawCircle(
                color = if (g.dark) Color.White.copy(alpha = 0.08f) else g.accent.copy(alpha = 0.10f),
                radius = radius,
                center = c,
                style = Stroke(stroke),
            )
            val topLeft = Offset(c.x - radius, c.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            val brush = Brush.sweepGradient(colors, c)
            if (indeterminate) {
                rotate(spin, c) {
                    drawArc(brush, -90f, 100f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            } else if (animated > 0f) {
                rotate(-90f, c) {
                    drawArc(brush, 0f, 360f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (indeterminate) "…" else "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                color = g.text,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
            )
            if (caption != null) {
                Text(caption, color = g.subText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
