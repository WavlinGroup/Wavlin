/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** True while the app draws the moving dynamic-theme backdrop, so screens skip their own static wash. */
val LocalAmbientBackdrop = staticCompositionLocalOf { false }

private const val DriftMillis = 16_000

/**
 * Slowly drifting colour light behind the pages, built from the current colour scheme. With the dynamic theme on, that
 * scheme follows the playing artwork, so the light follows it too. It sits on its own layer, so the motion redraws only
 * this layer and never the page above it; positions are read in the draw phase.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val first by animateColorAsState(scheme.primaryContainer, tween(900), label = "ambientFirst")
    val second by animateColorAsState(scheme.tertiaryContainer, tween(900), label = "ambientSecond")
    val third by animateColorAsState(scheme.secondaryContainer, tween(900), label = "ambientThird")
    val glow by animateColorAsState(scheme.primary, tween(900), label = "ambientGlow")
    val strength = if (scheme.background.luminance() < 0.5f) 1f else 0.7f
    val phase by rememberInfiniteTransition(label = "ambientDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DriftMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "ambientPhase",
    )

    Box(
        modifier
            .graphicsLayer { }
            .drawBehind {
                val turn = phase * 2f * PI.toFloat()
                val reach = max(size.width, size.height) * 0.7f
                drawRect(
                    Brush.verticalGradient(
                        0f to first.copy(alpha = 0.45f * strength),
                        0.55f to first.copy(alpha = 0.15f * strength),
                        1f to Color.Transparent,
                    ),
                )
                drawLight(first, 0.6f * strength, Offset(size.width * (0.5f + 0.38f * sin(turn)), size.height * (0.20f + 0.16f * cos(turn))), reach)
                drawLight(second, 0.55f * strength, Offset(size.width * (0.5f + 0.40f * cos(turn + 1.6f)), size.height * (0.52f + 0.20f * sin(turn * 2f))), reach * 0.9f)
                drawLight(third, 0.5f * strength, Offset(size.width * (0.5f + 0.36f * sin(turn + 3.4f)), size.height * (0.85f + 0.10f * cos(turn))), reach)
                drawLight(glow, 0.26f * strength, Offset(size.width * (0.5f + 0.44f * cos(turn * 2f + 0.8f)), size.height * (0.5f + 0.30f * sin(turn + 0.6f))), reach * 0.6f)
            },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLight(
    color: Color,
    alpha: Float,
    center: Offset,
    radius: Float,
) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
    )
}
