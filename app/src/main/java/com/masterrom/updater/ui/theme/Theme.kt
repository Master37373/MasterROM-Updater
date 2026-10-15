package com.masterrom.updater.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours of the One UI 8.5 "glass" look (see the design canvas). */
@Immutable
data class GlassColors(
    val dark: Boolean,
    val base: Color,
    val text: Color,
    val subText: Color,
    val accent: Color,
    val accent2: Color,
    val violet: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val divider: Color,
    val chipBg: Color,
    val chipText: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val blobAlpha: Float,
)

val LightGlass = GlassColors(
    dark = false,
    base = Color(0xFFF2F4F8),
    text = Color(0xFF101114),
    subText = Color(0xFF4A4E57),
    accent = Color(0xFF0A72E6),
    accent2 = Color(0xFF14B5C9),
    violet = Color(0xFF5B6CFF),
    glassFill = Color(0xA8FFFFFF),
    glassStroke = Color(0xEBFFFFFF),
    glassHighlight = Color(0x80FFFFFF),
    divider = Color(0x12000000),
    chipBg = Color(0x1C0A72E6),
    chipText = Color(0xFF0A66D1),
    success = Color(0xFF1E9E62),
    warning = Color(0xFFD9661B),
    error = Color(0xFFD93A3F),
    blobAlpha = 1f,
)

val DarkGlass = GlassColors(
    dark = true,
    base = Color(0xFF07080C),
    text = Color(0xFFF4F5F7),
    subText = Color(0xFFB3B7C0),
    accent = Color(0xFF3E91FF),
    accent2 = Color(0xFF2ED3C6),
    violet = Color(0xFF7B6CFF),
    glassFill = Color(0x12FFFFFF),
    glassStroke = Color(0x21FFFFFF),
    glassHighlight = Color(0x14FFFFFF),
    divider = Color(0x14FFFFFF),
    chipBg = Color(0x2E3E91FF),
    chipText = Color(0xFF9CC8FF),
    success = Color(0xFF34C17E),
    warning = Color(0xFFF29A3A),
    error = Color(0xFFFF6B6F),
    blobAlpha = 1.1f,
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

@Composable
fun MasterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val glass = if (dark) DarkGlass else LightGlass
    val scheme = if (dark) {
        darkColorScheme(primary = glass.accent, background = glass.base, surface = glass.base, onBackground = glass.text, onSurface = glass.text)
    } else {
        lightColorScheme(primary = glass.accent, background = glass.base, surface = glass.base, onBackground = glass.text, onSurface = glass.text)
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
