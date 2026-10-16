package com.masterrom.updater.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masterrom.updater.ui.theme.LocalGlass
import kotlin.math.cos
import kotlin.math.sin

private val CardShape = RoundedCornerShape(28.dp)
private val PillShape = RoundedCornerShape(50)

/** Slowly drifting soft colour blobs behind the glass cards. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    val transition = rememberInfiniteTransition(label = "blobs")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(28_000, easing = LinearEasing)),
        label = "blobPhase",
    )
    Box(modifier.fillMaxSize().background(g.base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a = g.blobAlpha
            blob(Offset(w * (0.12f + 0.16f * sin(t)), h * (0.10f + 0.08f * cos(2 * t))), w * 0.95f, g.accent.copy(alpha = 0.34f * a))
            blob(Offset(w * (0.92f + 0.12f * sin(2 * t + 1)), h * (0.38f + 0.14f * cos(t + 2))), w * 0.85f, g.accent2.copy(alpha = 0.26f * a))
            blob(Offset(w * (0.30f + 0.18f * sin(t + 2)), h * (0.88f + 0.06f * cos(3 * t))), w * 1.0f, g.violet.copy(alpha = 0.22f * a))
        }
        content()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.35f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Translucent card with a hairline border and a soft highlight at the top edge. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(g.glassFill)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(listOf(g.glassHighlight, Color.Transparent), endY = 56.dp.toPx()),
                    size = Size(size.width, 56.dp.toPx()),
                )
            }
            .border(0.8.dp, g.glassStroke, CardShape)
            .padding(padding),
        content = content,
    )
}

/** One UI large title. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalGlass.current.text,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 18.dp),
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        color = LocalGlass.current.subText,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** Pressed state shrinks the element a little, like One UI buttons. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "press",
    )
    return scale
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 54.dp) {
    val g = LocalGlass.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .height(height)
            .clip(PillShape)
            .background(Brush.linearGradient(listOf(g.violet, g.accent, g.accent2)))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 48.dp) {
    val g = LocalGlass.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(height)
            .clip(PillShape)
            .background(g.glassFill)
            .border(1.dp, g.accent.copy(alpha = 0.28f), PillShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = g.chipText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 26.dp))
    }
}

@Composable
fun Chip(text: String, color: Color? = null) {
    val g = LocalGlass.current
    val c = color ?: g.chipText
    Text(
        text = text,
        color = c,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (color == null) g.chipBg else c.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 6.dp),
    ) { content() }
}

/** Label / value row inside a card, with an optional divider above. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun InfoRow(
    label: String,
    value: String,
    divider: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val g = LocalGlass.current
    Column {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(g.divider))
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick ?: {},
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = g.text, fontSize = 16.sp)
            Text(value, color = g.subText, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** Round glass button with an icon (settings, back). */
@Composable
fun GlassIconButton(
    icon: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlass.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(44.dp)
            .clip(PillShape)
            .background(if (g.dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.78f))
            .border(1.dp, if (g.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f), PillShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = contentDescription, tint = g.text, modifier = Modifier.size(24.dp))
    }
}

/**
 * Screen with a large One UI title at the top. When the content is scrolled, the title shrinks
 * with a spring animation and stays at the top (no toolbar bar); scrolled back to the top it
 * grows again. [navigation] (back button) sits left of the title.
 */
@Composable
fun CollapsingScreen(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val threshold = with(density) { 8.dp.toPx() }
    val collapsed = scroll.value > threshold
    val p by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
        label = "title",
    )

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(lerpDp(84.dp, 56.dp, p))
                    .padding(start = 14.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigation?.invoke()
                Text(
                    text = title,
                    color = g.text,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = if (navigation != null) 12.dp else 12.dp)
                        .graphicsLayer {
                            val scale = 1f - 0.38f * p
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        },
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun lerpDp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t
