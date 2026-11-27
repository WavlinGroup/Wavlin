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

/** Wavlin design cover art: round top corners and a bottom edge that dips in one soft curve. */
object ArtworkCurveShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 28.dp.toPx() }.coerceAtMost(size.minDimension / 4f)
        val depth = with(density) { 22.dp.toPx() }.coerceAtMost(size.height / 8f)
        val diameter = radius * 2f
        val path = Path().apply {
            arcTo(Rect(0f, 0f, diameter, diameter), 180f, 90f, forceMoveTo = true)
            arcTo(Rect(size.width - diameter, 0f, size.width, diameter), 270f, 90f, forceMoveTo = false)
            lineTo(size.width, size.height - depth)
            // The control point sits below the box so the middle of the curve lands exactly on its bottom.
            quadraticTo(size.width / 2f, size.height + depth, 0f, size.height - depth)
            close()
        }
        return Outline.Generic(path)
    }
}
