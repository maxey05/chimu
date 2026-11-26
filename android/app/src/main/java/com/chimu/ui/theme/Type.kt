package com.chimu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.chimu.R
import androidx.compose.ui.unit.sp

/** Archivo for all app text (DESIGN.md -> Typography). Files live in res/font. */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

/** Barlow, only for section subheadings such as "Expenditures" and "Recent Transactions". */
val Barlow = FontFamily(
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

// Larger than the Material defaults: the main user is older, so text is big and easy to read.
private val chimuStyles = Typography(
    bodyLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.1.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.3.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Barlow,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp
    ),
)

/** The Material defaults for every style we don't set above, switched to Archivo so no text falls back to Roboto. */
val Typography: Typography = Typography().let { base ->
    fun TextStyle.archivo() = if (fontFamily == null || fontFamily == FontFamily.Default) copy(fontFamily = Archivo) else this
    Typography(
        displayLarge = base.displayLarge.archivo(), displayMedium = base.displayMedium.archivo(), displaySmall = base.displaySmall.archivo(),
        headlineLarge = base.headlineLarge.archivo(), headlineMedium = chimuStyles.headlineMedium, headlineSmall = base.headlineSmall.archivo(),
        titleLarge = chimuStyles.titleLarge, titleMedium = chimuStyles.titleMedium, titleSmall = chimuStyles.titleSmall,
        bodyLarge = chimuStyles.bodyLarge, bodyMedium = chimuStyles.bodyMedium, bodySmall = chimuStyles.bodySmall,
        labelLarge = chimuStyles.labelLarge, labelMedium = chimuStyles.labelMedium, labelSmall = base.labelSmall.archivo().copy(fontSize = 14.sp, lineHeight = 20.sp),
    )
}
