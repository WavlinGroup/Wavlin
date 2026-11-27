/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Constraints
import coil3.compose.AsyncImage
import com.wavlin.music.ui.kit.ArtworkMorphShape
import kotlin.math.roundToInt

/**
 * Links the mini player cover to the full player cover so that expanding the sheet moves one picture
 * between them. Both report where they sit inside the sheet; [PlayerMorphOverlay] draws the cover in
 * between while the two originals stay hidden.
 */
@Stable
class PlayerMorphState(private val sheetProgress: () -> Float) {
    var enabled by mutableStateOf(false)
    var miniBounds by mutableStateOf<Rect?>(null)
        private set
    var fullBounds by mutableStateOf<Rect?>(null)
        private set

    /** Current angle of the spinning mini cover. */
    var miniRotation: () -> Float = { 0f }

    var host: LayoutCoordinates? = null

    val fraction: Float
        get() = sheetProgress().coerceIn(0f, 1f)

    /** True only between the two resting positions. */
    val active by derivedStateOf {
        enabled && miniBounds != null && fullBounds != null && sheetProgress() > 0.001f && sheetProgress() < 0.999f
    }

    fun report(coordinates: LayoutCoordinates, mini: Boolean) {
        val sheet = host?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        val bounds = sheet.localBoundingBoxOf(coordinates, clipBounds = false)
        // A pager page that is scrolled away is not a place to fly to.
        if (bounds.center.x < 0f || bounds.center.x > sheet.size.width) return
        if (mini) miniBounds = bounds else fullBounds = bounds
    }
}

/** Null unless the Wavlin design is on. */
val LocalPlayerMorph = staticCompositionLocalOf<PlayerMorphState?> { null }

/** Marks one end of the cover motion and hides it while the moving copy is on screen. */
fun Modifier.playerMorphEnd(morph: PlayerMorphState?, mini: Boolean): Modifier =
    if (morph == null) {
        this
    } else {
        this
            .onGloballyPositioned { morph.report(it, mini) }
            .graphicsLayer { alpha = if (morph.active) 0f else 1f }
    }

@Composable
fun BoxScope.PlayerMorphOverlay(
    morph: PlayerMorphState,
    artworkUrl: String?,
) {
    Box(
        Modifier
            .matchParentSize()
            .onPlaced { morph.host = it },
    ) {
        if (morph.active && artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    // Position and size are read in the layout phase, shape and angle in the draw phase,
                    // so following the finger does not recompose anything.
                    .layout { measurable, constraints ->
                        val from = morph.miniBounds ?: Rect.Zero
                        val to = morph.fullBounds ?: Rect.Zero
                        val bounds = lerp(from, to, morph.fraction)
                        val placeable = measurable.measure(
                            Constraints.fixed(
                                bounds.width.roundToInt().coerceAtLeast(0),
                                bounds.height.roundToInt().coerceAtLeast(0),
                            ),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                        }
                    }
                    .graphicsLayer {
                        val fraction = morph.fraction
                        // Unwind the spinning disc by the shorter way round.
                        var angle = morph.miniRotation() % 360f
                        if (angle > 180f) angle -= 360f
                        rotationZ = angle * (1f - fraction)
                        shape = ArtworkMorphShape(fraction)
                        clip = true
                    },
            )
        }
    }
}
