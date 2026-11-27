/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

/**
 * Decides when the dock should shrink. Scroll has to travel [thresholdPx] in one direction before the
 * state flips, so a shaky finger does not make the dock flicker.
 */
class DockScrollTracker(private val thresholdPx: Float) {
    var compact = false
        private set

    private var travel = 0f

    /**
     * @param consumedY scroll the content used, negative when moving further down the list.
     * @param availableY scroll nobody used, positive when the list is already at its top.
     */
    fun onScroll(consumedY: Float, availableY: Float): Boolean {
        if (availableY > 0f) {
            expand()
            return compact
        }
        if (consumedY == 0f) return compact
        if (travel * consumedY < 0f) travel = 0f
        travel = (travel + consumedY).coerceIn(-thresholdPx, thresholdPx)
        if (travel <= -thresholdPx) compact = true
        if (travel >= thresholdPx) compact = false
        return compact
    }

    fun expand() {
        compact = false
        travel = 0f
    }
}
