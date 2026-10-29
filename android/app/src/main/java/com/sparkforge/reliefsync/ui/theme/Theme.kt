package com.sparkforge.reliefsync.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object RS {
    val Bg = Color(0xFF070B16)
    val Bg2 = Color(0xFF0C1222)
    val Panel = Color(0xFF0F1629)
    val Line = Color(0xFF1D2742)
    val Line2 = Color(0xFF2A3758)
    val Text = Color(0xFFE8EDF8)
    val Muted = Color(0xFF8B97B5)
    val Dim = Color(0xFF5D6A8A)
    val Red = Color(0xFFFF4D5E)
    val Orange = Color(0xFFFF7A45)
    val Amber = Color(0xFFFFB547)
    val Green = Color(0xFF2EE6A6)
    val Blue = Color(0xFF4DA3FF)
    val Violet = Color(0xFFA78BFA)
    val Cyan = Color(0xFF34D8F5)

    fun urgency(u: String?) = when (u) { "P0" -> Red; "P1" -> Amber; else -> Blue }
    fun kind(k: String) = when (k) {
        "THOUGHT" -> Violet; "TOOL_CALL" -> Cyan; "ACTION" -> Amber; "ERROR" -> Red; else -> Green
    }
}

private val Brand = darkColorScheme(
    primary = RS.Red, onPrimary = Color.White, secondary = RS.Green, onSecondary = Color(0xFF03261A),
    tertiary = RS.Amber, background = RS.Bg, onBackground = RS.Text, surface = RS.Panel, onSurface = RS.Text,
    surfaceVariant = RS.Bg2, onSurfaceVariant = RS.Muted, outline = RS.Line2, error = RS.Red,
    surfaceContainer = RS.Panel, surfaceContainerHigh = Color(0xFF131C33), surfaceContainerLow = RS.Bg2,
)

val Mono = FontFamily.Monospace

@Composable
fun ReliefSyncTheme(content: @Composable () -> Unit) {
    // Material You: borrow the wallpaper's tonal palette for neutral containers, keep emergency brand colors.
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        val dyn = dynamicDarkColorScheme(LocalContext.current)
        Brand.copy(surfaceContainerHighest = dyn.surfaceContainerHighest, secondaryContainer = dyn.secondaryContainer,
            onSecondaryContainer = dyn.onSecondaryContainer)
    } else Brand
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        ),
        content = content,
    )
}
