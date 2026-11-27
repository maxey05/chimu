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
    /** The Home balance card, the one solid gradient block on a screen. */
    val heroGradient: List<Color>,
    /** The soft wash behind the top of Home and Transactions: four colours from top to the page colour, at 0, 38, 68 and 100 %. */
    val topWash: List<Color>,
    /** The quiet fill behind a transaction's details panel and its transfer icon. */
    val detailWash: Color,
    /** The Home total and its panels are dark ink on the light card. */
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
    topWash = listOf(Color(0xFFD8CAE4), Color(0xFFE9E0F0), Color(0xFFF6F2F9), Color(0xFFFFFFFF)),
    detailWash = Color(0xFFF7F5FA),
    onHero = IndigoInk,
)

val LocalChimuColors = staticCompositionLocalOf { LightChimuColors }

val MaterialTheme.chimu: ChimuColors
    @Composable @ReadOnlyComposable get() = LocalChimuColors.current
