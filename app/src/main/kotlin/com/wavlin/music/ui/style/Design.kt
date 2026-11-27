/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.style

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True when the Wavlin design (preview) is on. Apple Music style keeps its own look and ignores it. */
val LocalWavlinDesign = staticCompositionLocalOf { false }

/** 4 dp grid. Screens use these instead of loose numbers so spacing stays consistent. */
object WavlinSpace {
    val Hair = 2.dp
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
    val Gutter = 20.dp
}

/** Fewer, softer corner sizes: small chips and fields, cards, sheets and hero artwork. */
val WavlinShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * Editorial type scale: heavier weights and tighter tracking on big text so titles read as titles,
 * calmer body text, and the same font family as the rest of the app.
 */
val WavlinTypography: Typography = AppTypography.copy(
    displayLarge = AppTypography.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.0).sp),
    displayMedium = AppTypography.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    displaySmall = AppTypography.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineLarge = AppTypography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = AppTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = AppTypography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = AppTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = AppTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = AppTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = AppTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = AppTypography.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = AppTypography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)
