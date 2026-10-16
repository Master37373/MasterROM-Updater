package com.masterrom.updater.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.masterrom.updater.ui.theme.LocalGlass
import kotlin.math.abs

private val ITEM_HEIGHT = 44.dp
private const val VISIBLE = 5
private const val LOOPS = 400

/**
 * iOS-style scroll wheel: endless (the values repeat), snaps to the middle row, the rows fade and
 * shrink towards the edges, a light tick on every new value.
 */
@Composable
fun WheelPicker(
    count: Int,
    initial: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: (Int) -> String = { "%02d".format(it) },
) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val start = remember { count * (LOOPS / 2) + initial.coerceIn(0, count - 1) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val center by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: start
        }
    }
    LaunchedEffect(center) {
        onChange(center % count)
        if (center != start) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = modifier.height(ITEM_HEIGHT * VISIBLE),
        contentPadding = PaddingValues(vertical = ITEM_HEIGHT * (VISIBLE / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count * LOOPS) { i ->
            val d = abs(i - center)
            Box(Modifier.height(ITEM_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    label(i % count),
                    color = g.text,
                    fontSize = 26.sp,
                    fontWeight = if (d == 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = when (d) { 0 -> 1f; 1 -> 0.45f; else -> 0.18f }
                        val s = when (d) { 0 -> 1f; 1 -> 0.88f; else -> 0.76f }
                        scaleX = s
                        scaleY = s
                    },
                )
            }
        }
    }
}

/** Dialog with an hour and a minute wheel (24 h). [minutes] is the time of day in minutes. */
@Composable
fun TimeWheelDialog(
    title: String,
    minutes: Int,
    cancelText: String,
    okText: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val g = LocalGlass.current
    var hour by remember { mutableIntStateOf(minutes / 60) }
    var minute by remember { mutableIntStateOf(minutes % 60) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (g.dark) Color(0xFF1B1D23) else Color(0xFFF8F9FC))
                .border(1.dp, g.glassStroke, RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = g.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Box(contentAlignment = Alignment.Center) {
                // band behind the selected row
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ITEM_HEIGHT)
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.accent.copy(alpha = if (g.dark) 0.18f else 0.10f)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    WheelPicker(24, hour, { hour = it }, Modifier.width(84.dp))
                    Text(":", color = g.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    WheelPicker(60, minute, { minute = it }, Modifier.width(84.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(cancelText, onClick = onDismiss, modifier = Modifier.weight(1f), height = 46.dp)
                PrimaryButton(okText, onClick = { onConfirm(hour * 60 + minute) }, modifier = Modifier.weight(1f), height = 46.dp)
            }
        }
    }
}
