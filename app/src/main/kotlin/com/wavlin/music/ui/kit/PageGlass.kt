/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * The moving dynamic backdrop recorded as a layer, so glass inside pages can refract it. Pages are not allowed to sample
 * themselves, but the backdrop sits beside them. Null unless Apple Music style and the dynamic backdrop are both on.
 */
val LocalAmbientLayer = staticCompositionLocalOf<LayerBackdrop?> { null }

/** True when [pageGlass] draws real liquid glass instead of doing nothing. */
@Composable
fun pageGlassAvailable(): Boolean =
    LocalInterfaceAppleMusic.current && LocalAmbientLayer.current != null && appleGlassSupported()

/**
 * Liquid glass for cards and buttons inside pages (Apple Music style). It bends the moving backdrop behind it. Without
 * the layer this leaves the modifier unchanged, so callers keep their own flat look as the fallback.
 */
@Composable
fun Modifier.pageGlass(
    shape: Shape,
    tintAlpha: Float = 0.12f,
    lens: Boolean = true,
): Modifier {
    val layer = LocalAmbientLayer.current
    if (!pageGlassAvailable() || layer == null) return this
    return appleGlass(
        shape = shape,
        tintAlpha = tintAlpha,
        backdrop = layer,
        lensEnabled = lens && LocalLensEnabled.current,
    )
}
