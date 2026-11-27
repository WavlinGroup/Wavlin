/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.style

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** One set of motion rules for the whole app, so screens and components move the same way. */
object WavlinMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val ScreenEnterMs = 380
    const val ScreenExitMs = 240
    const val ContentSwapMs = 280
    const val ArtworkSwapMs = 600

    /** Press feedback and small state changes. */
    fun <T> pressSpring() = spring<T>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)

    /** Things that follow a finger or settle after a drag (sheets, dock, pill). */
    fun <T> settleSpring() = spring<T>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    /** Shared-element style moves such as mini player to full player. */
    fun <T> expandSpring() = spring<T>(dampingRatio = 0.88f, stiffness = 380f)

    fun <T> screenEnter() = tween<T>(ScreenEnterMs, easing = EmphasizedDecelerate)
    fun <T> screenExit() = tween<T>(ScreenExitMs, easing = EmphasizedAccelerate)
    fun <T> contentSwap() = tween<T>(ContentSwapMs, easing = Standard)

    fun intOffsetSettle() = settleSpring<IntOffset>()
    fun intSizeSettle() = settleSpring<IntSize>()
}
