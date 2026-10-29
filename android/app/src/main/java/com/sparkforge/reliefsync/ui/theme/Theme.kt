package com.sparkforge.reliefsync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** ReliefSync design tokens – light, map-first (pairs with OpenStreetMap's light basemap). */
object RS {
    val Bg = Color(0xFFF5F6FA)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF1F3F8)
    val Ink = Color(0xFF0B1220)
    val Text = Ink
    val Muted = Color(0xFF5B6475)
    val Dim = Color(0xFF98A1B3)
    val Line = Color(0xFFE6E8EF)
    val Line2 = Color(0xFFD5D9E3)
    val Red = Color(0xFFE5243B)
    val RedDeep = Color(0xFFB0102A)
    val Orange = Color(0xFFFF6B3D)
    val Amber = Color(0xFFF59E0B)
    val Green = Color(0xFF16A34A)
    val Blue = Color(0xFF2563EB)
    val Sky = Color(0xFF0EA5E9)
    val Violet = Color(0xFF7C3AED)
    val Cyan = Color(0xFF0891B2)

    // legacy names used by older components
    val Panel = Surface
    val Bg2 = Surface2

    fun urgency(u: String?) = when (u) { "P0" -> Red; "P1" -> Amber; else -> Blue }
    fun kind(k: String) = when (k) {
        "THOUGHT" -> Violet; "TOOL_CALL" -> Cyan; "ACTION" -> Amber; "ERROR" -> Red; else -> Green
    }
    fun vehicle(t: String?) = when (t) {
        "4WD" -> Color(0xFF16A34A); "CAR" -> Blue; "BOAT" -> Cyan; "BICYCLE" -> Violet; "FOOT" -> Color(0xFFEA580C); else -> Green
    }
}

private val Scheme = lightColorScheme(
    primary = RS.Red, onPrimary = Color.White, secondary = RS.Blue, onSecondary = Color.White,
    tertiary = RS.Green, background = RS.Bg, onBackground = RS.Ink, surface = RS.Surface, onSurface = RS.Ink,
    surfaceVariant = RS.Surface2, onSurfaceVariant = RS.Muted, outline = RS.Line2, outlineVariant = RS.Line,
    surfaceContainer = RS.Surface, surfaceContainerLow = RS.Surface, surfaceContainerHigh = RS.Surface2,
    surfaceContainerHighest = RS.Surface2, secondaryContainer = Color(0xFFE8EEFF), onSecondaryContainer = RS.Blue,
    error = RS.Red,
)

val Mono = FontFamily.Monospace

@Composable
fun ReliefSyncTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = Scheme,
        typography = base.copy(
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp),
        ),
        content = content,
    )
}
