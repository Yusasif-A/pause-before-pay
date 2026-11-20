package com.pausebeforepay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One dark scheme, used in both light and dark system settings. The camera
 * screen is the centre of this app and a dark surround keeps the preview and
 * the live numbers readable.
 */
object Pbp {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF151C30)
    val SurfaceHigh = Color(0xFF1E2740)
    val OnSurface = Color(0xFFF2F5FF)
    val Muted = Color(0xFF9AA6C4)
    val Accent = Color(0xFF6EE7B7)
    val AccentPressed = Color(0xFF3FBF93)
    val Warn = Color(0xFFFBBF24)
    val Alert = Color(0xFFFF8A7A)
    val Pulse = Color(0xFFFF6B6B)
    val Breath = Color(0xFF4FCCC4)
}

private val scheme = darkColorScheme(
    primary = Pbp.Accent,
    onPrimary = Color(0xFF07251A),
    secondary = Pbp.Breath,
    background = Pbp.Background,
    onBackground = Pbp.OnSurface,
    surface = Pbp.Surface,
    onSurface = Pbp.OnSurface,
    surfaceVariant = Pbp.SurfaceHigh,
    onSurfaceVariant = Pbp.Muted,
    error = Pbp.Alert,
)

/** Larger than Material defaults throughout: this has to be readable at a glance. */
private val typography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold, lineHeight = 29.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PauseBeforePayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
