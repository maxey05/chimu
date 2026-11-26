package com.chimu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Dusk palette (DESIGN.md -> Brand & visual identity -> Color).
val Periwinkle = Color(0xFF455799)
val IndigoInk = Color(0xFF10182F)
val InkSecondary = Color(0xFF5A6180)
val Dawning = Color(0xFFFEF5EE)
val PrimaryWash = Color(0xFFF4F0FA)
val Lavender = Color(0xFFB295C9)
val LoveRose = Color(0xFFE2A9CA)
val LoveApricot = Color(0xFFFFE0B9)
val Hairline = Color(0xFFEEE7EC)
val OutlineStrong = Color(0xFF8A8FA8)
val DangerLight = Color(0xFFB3261E)
val WarningLight = Color(0xFF8A5300)

// Dark mode: derived from the light palette.
val DarkPage = Color(0xFF10182F)
val DarkSurface = Color(0xFF1A2342)
val DarkInkSecondary = Color(0xFFB9BDD3)
val DangerDark = Color(0xFFFFB4AB)

/** Brand colours that Material's scheme has no slot for. Read with [MaterialTheme.chimu]. */
data class ChimuColors(
    /** Chevron and wash for money coming in. Colour is never the only cue: sign and chevron say it too. */
    val gain: Color,
    val gainContainer: Color,
    /** Chevron and amount for money going out, a little greyed against a gain. */
    val loss: Color,
    val lossContainer: Color,
    /** Chart fill and guide. A mark, never text. */
    val accent: Color,
    val dawning: Color,
    val outlineStrong: Color,
    val warning: Color,
    /** The one coloured block per screen: the Home balance card. */
    val heroGradient: List<Color>,
    /** The Home total and its panels stay dark-on-light in both modes. */
    val onHero: Color,
)

val LightChimuColors = ChimuColors(
    gain = Periwinkle,
    gainContainer = PrimaryWash,
    loss = Color(0xFF6C7396),
    lossContainer = Color(0xFFF3F2F7),
    accent = Lavender,
    dawning = Dawning,
    outlineStrong = OutlineStrong,
    warning = WarningLight,
    heroGradient = listOf(Lavender, LoveRose, LoveApricot),
    onHero = IndigoInk,
)

val DarkChimuColors = ChimuColors(
    gain = Lavender,
    gainContainer = Color(0xFF2A3358),
    loss = Color(0xFF9AA0BF),
    lossContainer = Color(0xFF232B4A),
    accent = Lavender,
    dawning = Dawning,
    outlineStrong = Color(0xFF8A8FA8),
    warning = Color(0xFFFFC27A),
    heroGradient = listOf(Lavender, LoveRose, LoveApricot),
    onHero = IndigoInk,
)

val LocalChimuColors = staticCompositionLocalOf { LightChimuColors }

val MaterialTheme.chimu: ChimuColors
    @Composable @ReadOnlyComposable get() = LocalChimuColors.current
