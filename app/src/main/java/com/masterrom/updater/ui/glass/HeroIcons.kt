package com.masterrom.updater.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.masterrom.updater.ui.theme.LocalGlass

enum class HeroKind { CHECKING, UP_TO_DATE, AVAILABLE, ERROR }

/**
 * Animated status illustration at the top of the start screen (132 dp):
 * soft glow + gradient disc + symbol. Each state animates in: the check mark draws itself,
 * the download arrow bobs, the checking ring spins, the error mark shakes once.
 */
@Composable
fun HeroIcon(kind: HeroKind, modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val g = LocalGlass.current
    val appear = remember(kind) { Animatable(0f) }
    LaunchedEffect(kind) {
        appear.snapTo(0f)
        appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f))
    }
    val loop = rememberInfiniteTransition(label = "hero")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val bob by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pulse by loop.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    val discColors = when (kind) {
        HeroKind.ERROR -> listOf(g.error, g.warning)
        else -> listOf(g.accent, g.accent2)
    }
    val glowColor = if (kind == HeroKind.ERROR) g.error else g.accent

    Canvas(modifier.size(size)) {
        val c = center
        val unit = this.size.minDimension / 132f
        val p = appear.value.coerceIn(0f, 1.2f)

        // glow
        drawCircle(
            brush = Brush.radialGradient(listOf(glowColor.copy(alpha = 0.38f * pulse), Color.Transparent), c, 66f * unit),
            radius = 66f * unit,
            center = c,
        )
        // disc (springs in)
        val discR = 46f * unit * p
        if (discR > 0f) {
            drawCircle(
                brush = Brush.linearGradient(discColors, Offset(c.x - discR, c.y - discR), Offset(c.x + discR, c.y + discR)),
                radius = discR,
                center = c,
            )
        }
        val stroke = Stroke(width = 5.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val progress = ((appear.value - 0.25f) / 0.75f).coerceIn(0f, 1f)
        when (kind) {
            HeroKind.UP_TO_DATE -> drawCheck(c, unit, progress, stroke)
            HeroKind.AVAILABLE -> drawDownload(c, unit, progress, bob, stroke)
            HeroKind.CHECKING -> rotate(spin, c) {
                drawArc(
                    color = Color.White,
                    startAngle = 0f,
                    sweepAngle = 250f,
                    useCenter = false,
                    topLeft = Offset(c.x - 22f * unit, c.y - 22f * unit),
                    size = androidx.compose.ui.geometry.Size(44f * unit, 44f * unit),
                    style = stroke,
                )
            }
            HeroKind.ERROR -> {
                val shake = if (progress < 1f) kotlin.math.sin(progress * 18f) * 6f * unit * (1f - progress) else 0f
                drawLine(Color.White, Offset(c.x + shake, c.y - 20f * unit), Offset(c.x + shake, c.y + 4f * unit), 6.5f * unit, StrokeCap.Round)
                drawCircle(Color.White, 3.8f * unit, Offset(c.x + shake, c.y + 17f * unit))
            }
        }
    }
}

private fun DrawScope.drawCheck(c: Offset, unit: Float, progress: Float, stroke: Stroke) {
    if (progress <= 0f) return
    val path = Path().apply {
        moveTo(c.x - 19f * unit, c.y + 1f * unit)
        lineTo(c.x - 6f * unit, c.y + 14f * unit)
        lineTo(c.x + 20f * unit, c.y - 13f * unit)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * progress, part, true)
    drawPath(part, Color.White, style = stroke)
}

private fun DrawScope.drawDownload(c: Offset, unit: Float, progress: Float, bob: Float, stroke: Stroke) {
    if (progress <= 0f) return
    val dy = (bob - 0.5f) * 5f * unit
    val a = progress
    drawLine(Color.White.copy(alpha = a), Offset(c.x, c.y - 20f * unit + dy), Offset(c.x, c.y + 8f * unit + dy), stroke.width, StrokeCap.Round)
    val head = Path().apply {
        moveTo(c.x - 12f * unit, c.y - 3f * unit + dy)
        lineTo(c.x, c.y + 9f * unit + dy)
        lineTo(c.x + 12f * unit, c.y - 3f * unit + dy)
    }
    drawPath(head, Color.White.copy(alpha = a), style = stroke)
    drawLine(Color.White.copy(alpha = a), Offset(c.x - 16f * unit, c.y + 20f * unit), Offset(c.x + 16f * unit, c.y + 20f * unit), stroke.width, StrokeCap.Round)
}
