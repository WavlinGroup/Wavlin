/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.style

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * Screen changes of the Wavlin design. Tabs move sideways in the direction of the tapped tab. Opening a
 * detail screen brings it in over the current one, which steps back slightly; going back reverses that.
 */
object WavlinTransitions {
    private const val PushedBackScale = 0.96f

    // Same stiffness as the tab indicator, so the page and the dock pill move together.
    private val slide = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset(1, 1),
    )

    /** @param direction 1 when the target tab is right of the current one, -1 when it is left. */
    fun tabEnter(direction: Int): EnterTransition =
        slideInHorizontally(slide) { direction * it / 6 } + fadeIn(WavlinMotion.screenEnter())

    fun tabExit(direction: Int): ExitTransition =
        slideOutHorizontally(slide) { -direction * it / 6 } + fadeOut(WavlinMotion.screenExit())

    fun pushEnter(): EnterTransition =
        slideInHorizontally(slide) { it / 5 } + fadeIn(WavlinMotion.screenEnter())

    fun pushExit(): ExitTransition =
        scaleOut(WavlinMotion.screenExit(), targetScale = PushedBackScale) + fadeOut(WavlinMotion.screenExit())

    fun popEnter(): EnterTransition =
        scaleIn(WavlinMotion.screenEnter(), initialScale = PushedBackScale) + fadeIn(WavlinMotion.screenEnter())

    fun popExit(): ExitTransition =
        slideOutHorizontally(slide) { it / 5 } + fadeOut(WavlinMotion.screenExit())
}
