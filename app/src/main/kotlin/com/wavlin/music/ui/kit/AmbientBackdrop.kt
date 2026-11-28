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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** True while the app draws the moving dynamic-theme backdrop, so screens skip their own static wash. */
val LocalAmbientBackdrop = staticCompositionLocalOf { false }

private const val DriftMillis = 24_000

/**
 * Slowly drifting colour light behind the pages, built from the current colour scheme. With the dynamic theme on, that
 * scheme follows the playing artwork, so the light follows it too. It sits on its own layer, so the motion redraws only
 * this layer and never the page above it; positions are read in the draw phase.
 */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    /** False draws one still frame, softer, as the surface for glass when the dynamic theme is off. */
    animated: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val first by animateColorAsState(scheme.primaryContainer, tween(900), label = "ambientFirst")
    // Neighbouring hues of the same colour, so the pools read as different colours that flow into each other.
    // A grey cover has no hue to shift, so it stays grey like the player background.
    val secondTarget = remember(scheme.primaryContainer) { scheme.primaryContainer.shiftHue(42f) }
    val thirdTarget = remember(scheme.primaryContainer) { scheme.primaryContainer.shiftHue(-42f) }
    val second by animateColorAsState(secondTarget, tween(900), label = "ambientSecond")
    val third by animateColorAsState(thirdTarget, tween(900), label = "ambientThird")
    val glow by animateColorAsState(scheme.primary, tween(900), label = "ambientGlow")
    val strength = (if (scheme.background.luminance() < 0.5f) 1f else 0.7f) * (if (animated) 1f else 0.55f)
    val phase by if (animated) {
        rememberInfiniteTransition(label = "ambientDrift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(DriftMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "ambientPhase",
        )
    } else {
        remember { mutableStateOf(0.3f) }
    }

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
                drawLight(first, 0.62f * strength, Offset(size.width * (0.5f + 0.40f * sin(turn)), size.height * (0.20f + 0.16f * sin(turn * 2f + 0.5f))), reach)
                drawLight(second, 0.58f * strength, Offset(size.width * (0.5f + 0.42f * cos(turn + 1.3f)), size.height * (0.52f + 0.22f * sin(turn + 2.1f))), reach)
                drawLight(third, 0.54f * strength, Offset(size.width * (0.5f + 0.38f * sin(turn * 2f + 2.5f)), size.height * (0.84f + 0.10f * cos(turn + 0.9f))), reach)
                drawLight(glow, 0.24f * strength, Offset(size.width * (0.5f + 0.44f * cos(turn * 2f + 0.8f)), size.height * (0.5f + 0.30f * sin(turn + 0.6f))), reach * 0.65f)
            },
    )
}

private fun Color.shiftHue(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
