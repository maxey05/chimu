package com.chimu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

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

/**
 * Brand colours, light only (v6: the dark theme is gone, phones in dark mode get the same light app). Android 12+
 * wallpaper colours are off so every phone looks the same (DESIGN.md).
 */
@Composable
fun ChimuTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalChimuColors provides LightChimuColors) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
