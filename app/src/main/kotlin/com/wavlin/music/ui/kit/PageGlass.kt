/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.wavlin.music.ui.style.LocalWavlinLayout
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

/**
 * One card surface for every page in the Wavlin layout. Apple Music style with a backdrop: liquid glass. Otherwise a tonal
 * fill with a lit rim, brighter on top. Other layouts get the plain fill, so callers can use it without checking.
 * [flat] skips the glass for long lists, where a lens or blur per row would cost too much while scrolling.
 */
@Composable
fun Modifier.wavlinPanel(
    shape: Shape,
    fill: Color,
    flat: Boolean = false,
): Modifier {
    if (!LocalWavlinLayout.current) return this.clip(shape).background(fill)
    val onSurface = MaterialTheme.colorScheme.onSurface
    if (!flat && pageGlassAvailable()) {
        return pageGlass(shape, 0.1f, lens = false).clip(shape).background(onSurface.copy(alpha = 0.05f))
    }
    val rim = Brush.verticalGradient(listOf(onSurface.copy(alpha = 0.2f), onSurface.copy(alpha = 0.04f)))
    return this.clip(shape).background(fill).border(0.75.dp, rim, shape)
}
