/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Liquid-glass approach adapted from SimpMusic (GPL-3.0, maxrave-dev/SimpMusic):
 * Kyant backdrop effect stack (vibrancy, colorControls, blur, lens).
 */

package com.wavlin.music.ui.kit

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.wavlin.music.core.config.ThumbnailCornerRadius
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import dev.chrisbanes.haze.HazeStyle

/** True when the user picked the Apple Music interface style. */
val LocalInterfaceAppleMusic = staticCompositionLocalOf { false }

/** Source layer that sibling glass surfaces refract. Null unless Apple Music style is on and supported. */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Source layer for glass drawn on top of the expanded player (its blurred artwork background). Null unless Apple style is on. */
val LocalPlayerGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Per-surface switch for the lens AGSL shader. Provided as false when the user enables reduced glass. */
val LocalLensEnabled = staticCompositionLocalOf { true }

/** Lens refraction runs on a RuntimeShader (AGSL), which needs Android 13+. Older devices fall back to Haze. */
fun appleGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Liquid glass for the few key surfaces (tab bar, mini player). Uses Kyant backdrop where
 * supported, and falls back to the existing Haze [glassPanel] otherwise.
 * The surface MUST be a sibling of the backdrop source, never a child of it.
 */
@Composable
fun Modifier.appleGlass(
    shape: Shape,
    fallbackStyle: HazeStyle? = null,
    /** Dark/light tint laid over the glass. Big surfaces (dialogs) pass a higher value so text stays readable. */
    tintAlpha: Float? = null,
    backdrop: LayerBackdrop? = LocalGlassBackdrop.current,
    /** The lens is an AGSL shader that runs on every pixel of the surface, so big surfaces (the menu sheet) turn it off. */
    lensEnabled: Boolean = LocalLensEnabled.current,
): Modifier {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pink = LocalPinkLilyTheme.current
    val tint = tintAlpha ?: if (pink) 0.38f else if (isDark) 0.22f else 0.28f
    val tintColor = if (pink) PinkGlassTint else if (isDark) Color.Black else Color.White
    if (backdrop == null || !appleGlassSupported()) {
        // No sampleable backdrop (inside screen content, or pre-Android 13): Haze blur with the same tint.
        return this
            .legacyGlassPanel(shape = shape, style = fallbackStyle)
            .background(tintColor.copy(alpha = tint), shape)
            .then(if (pink) Modifier else Modifier.border(1.dp, glassRimBrush(isDark), shape))
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(
                brightness = if (pink) 0.1f else 0.05f,
                contrast = 1f,
                saturation = if (pink) 1.05f else 1.5f,
            )
            blur(7f.dp.toPx())
            if (lensEnabled) {
                lens(minOf(size.minDimension / 4f, 24.dp.toPx()), minOf(size.minDimension / 2f, 32.dp.toPx()), false)
            }
        },
        onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
        onDrawSurface = {
            drawRect(tintColor.copy(alpha = tint))
            if (pink) drawRect(Color.White.copy(alpha = 0.12f)) else drawGlassEdge(shape, isDark)
        },
    )
}

/** Artwork corner shape: Apple Music uses noticeably rounder cover art than the Material default. */
@Composable
fun artworkShape(): RoundedCornerShape =
    RoundedCornerShape(if (LocalInterfaceAppleMusic.current) 8.dp else ThumbnailCornerRadius)

/** Rim light: bright where a top-left light would catch the edge, nearly gone along the sides, a faint return at the bottom right. */
private fun glassRimBrush(isDark: Boolean, end: Offset = Offset(600f, 600f)): Brush {
    val bright = Color.White.copy(alpha = if (isDark) 0.5f else 0.8f)
    val dim = Color.White.copy(alpha = if (isDark) 0.05f else 0.18f)
    return Brush.linearGradient(
        0f to bright,
        0.42f to dim,
        0.58f to dim,
        1f to bright.copy(alpha = bright.alpha * 0.45f),
        start = Offset.Zero,
        end = end,
    )
}

/** Edge treatment for the lens path: a 1.5 dp rim light plus a soft sheen over the upper half, so the surface reads as a thick pane. */
private fun DrawScope.drawGlassEdge(shape: Shape, isDark: Boolean) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(
        outline = outline,
        brush = glassRimBrush(isDark, Offset(size.width, size.height)),
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = if (isDark) 0.09f else 0.2f), Color.Transparent),
            endY = size.height * 0.55f,
        ),
    )
}
