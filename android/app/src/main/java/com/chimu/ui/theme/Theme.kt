package com.chimu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Lavender,
    onPrimary = IndigoInk,
    primaryContainer = Color(0xFF2A3358),
    onPrimaryContainer = Dawning,
    secondary = Lavender,
    onSecondary = IndigoInk,
    tertiary = LoveRose,
    background = DarkPage,
    onBackground = Dawning,
    surface = DarkSurface,
    onSurface = Dawning,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkInkSecondary,
    outline = Color(0xFF8A8FA8),
    outlineVariant = Color(0xFF2E3860),
    error = DangerDark,
    onError = IndigoInk,
)

private val LightColorScheme = lightColorScheme(
    primary = Periwinkle,
    onPrimary = Color.White,
    primaryContainer = PrimaryWash,
    onPrimaryContainer = Periwinkle,
    secondary = Periwinkle,
    onSecondary = Color.White,
    tertiary = Lavender,
    background = Color.White,
    onBackground = IndigoInk,
    surface = Color.White,
    onSurface = IndigoInk,
    surfaceVariant = Color.White,
    onSurfaceVariant = InkSecondary,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color.White,
    outline = OutlineStrong,
    outlineVariant = Hairline,
    error = DangerLight,
    onError = Color.White,
)

/** Brand colours always; Android 12+ wallpaper colours are off so every phone looks the same (DESIGN.md). */
@Composable
fun ChimuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extras = if (darkTheme) DarkChimuColors else LightChimuColors
    CompositionLocalProvider(LocalChimuColors provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
