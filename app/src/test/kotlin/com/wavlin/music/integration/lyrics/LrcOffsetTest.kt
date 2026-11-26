/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcOffsetTest {
    private val eps = 1e-6

    @Test
    fun readsPositiveAndNegativeOffsets() {
        assertEquals(500L, LrcOffset.read("[ti:Song]\n[offset:+500]\n[00:01.00]Hi"))
        assertEquals(-250L, LrcOffset.read("[offset: -250]\n[00:01.00]Hi"))
        assertEquals(300L, LrcOffset.read("[offset:300]\n[00:01.00]Hi"))
    }

    @Test
    fun noTagOrBrokenTagMeansZero() {
        assertEquals(0L, LrcOffset.read("[00:01.00]Hi"))
        assertEquals(0L, LrcOffset.read("[offset:abc]\n[00:01.00]Hi"))
        assertEquals(0L, LrcOffset.read("[offset:999999]\n[00:01.00]Hi"))
    }

    @Test
    fun positiveOffsetMakesLyricsAppearSooner() {
        val result = LrcOffset.apply(listOf(LyricsEntry(10_000, "Hi")), 500L)
        assertEquals(9_500L, result[0].time)
    }

    @Test
    fun wordTimesMoveWithTheLine() {
        val entry = LyricsEntry(10_000, "Hi", listOf(WordTimestamp("Hi", 10.0, 10.4)))
        val word = LrcOffset.apply(listOf(entry), -500L)[0].words!![0]
        assertEquals(10.5, word.startTime, eps)
        assertEquals(10.9, word.endTime, eps)
    }

    @Test
    fun timeNeverGoesNegative() {
        assertEquals(0L, LrcOffset.apply(listOf(LyricsEntry(200, "Hi")), 1_000L)[0].time)
    }

    @Test
    fun zeroOffsetKeepsTheSameList() {
        val list = listOf(LyricsEntry(1_000, "Hi"))
        assertEquals(list, LrcOffset.apply(list, 0L))
    }
}
