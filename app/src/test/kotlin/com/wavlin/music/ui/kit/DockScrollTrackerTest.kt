/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockScrollTrackerTest {

    private val tracker = DockScrollTracker(thresholdPx = 100f)

    @Test
    fun `shrinks only after enough downward travel`() {
        assertFalse(tracker.onScroll(consumedY = -60f, availableY = 0f))
        assertTrue(tracker.onScroll(consumedY = -60f, availableY = 0f))
    }

    @Test
    fun `direction change restarts the travel`() {
        tracker.onScroll(consumedY = -80f, availableY = 0f)
        tracker.onScroll(consumedY = 10f, availableY = 0f)
        assertFalse(tracker.onScroll(consumedY = -80f, availableY = 0f))
    }

    @Test
    fun `opens again after upward travel`() {
        tracker.onScroll(consumedY = -500f, availableY = 0f)
        assertTrue(tracker.onScroll(consumedY = 60f, availableY = 0f))
        assertFalse(tracker.onScroll(consumedY = 60f, availableY = 0f))
    }

    @Test
    fun `reaching the top opens the dock`() {
        tracker.onScroll(consumedY = -500f, availableY = 0f)
        assertFalse(tracker.onScroll(consumedY = 0f, availableY = 5f))
    }
}
