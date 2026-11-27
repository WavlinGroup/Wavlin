/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * Cover art outline on its way from the mini player to the full player. [fraction] 0 is a circle,
 * 1 has round top corners and a bottom edge that dips in one soft curve.
 */
data class ArtworkMorphShape(val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val f = fraction.coerceIn(0f, 1f)
        val circle = size.minDimension / 2f
        val finalRadius = with(density) { 28.dp.toPx() }.coerceAtMost(size.minDimension / 4f)
        val finalDepth = with(density) { 22.dp.toPx() }.coerceAtMost(size.height / 8f)
        val top = lerp(circle, finalRadius, f) * 2f
        val bottom = lerp(circle, 0f, f) * 2f
        val depth = finalDepth * f
        val sideEnd = size.height - depth

        val path = Path().apply {
            arcTo(Rect(0f, 0f, top, top), 180f, 90f, forceMoveTo = true)
            arcTo(Rect(size.width - top, 0f, size.width, top), 270f, 90f, forceMoveTo = false)
            // Explicit line: an arc with no size does not move the pen, and bottom is 0 at the end.
            lineTo(size.width, sideEnd - bottom / 2f)
            arcTo(Rect(size.width - bottom, sideEnd - bottom, size.width, sideEnd), 0f, 90f, forceMoveTo = false)
            // The control point sits below the box so the middle of the curve lands exactly on its bottom.
            quadraticTo(size.width / 2f, size.height + depth, bottom / 2f, sideEnd)
            arcTo(Rect(0f, sideEnd - bottom, bottom, sideEnd), 90f, 90f, forceMoveTo = false)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Wavlin design cover art outline in the full player. */
val ArtworkCurveShape = ArtworkMorphShape(1f)
