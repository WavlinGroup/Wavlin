/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.wavlin.music.ui.style.ArtworkPalette
import com.wavlin.music.ui.style.WavlinMotion

/**
 * Player background built from an [ArtworkPalette]: a two-tone vertical wash plus one soft accent
 * light near the top. When the palette changes it blends from what is on screen to the new palette.
 * Colors are read in the draw phase, so the blend redraws this layer only and never recomposes.
 */
@Composable
fun ArtworkAuraBackground(
    palette: ArtworkPalette?,
    modifier: Modifier = Modifier,
) {
    var from by remember { mutableStateOf<ArtworkPalette?>(null) }
    var to by remember { mutableStateOf<ArtworkPalette?>(null) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(palette) {
        val target = palette ?: return@LaunchedEffect
        val current = to
        if (current == null) {
            from = target
            to = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        from = from?.lerpTo(current, progress.value) ?: current
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(WavlinMotion.ArtworkSwapMs, easing = WavlinMotion.Standard))
    }

    Box(
        modifier.drawBehind {
            val start = from ?: return@drawBehind
            val end = to ?: return@drawBehind
            val p = start.lerpTo(end, progress.value)
            drawRect(Brush.verticalGradient(listOf(p.background, p.backgroundDeep)))
            drawRect(
                Brush.radialGradient(
                    colors = listOf(p.accent.copy(alpha = 0.26f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.2f),
                    radius = size.width * 0.95f,
                ),
            )
        },
    )
}
