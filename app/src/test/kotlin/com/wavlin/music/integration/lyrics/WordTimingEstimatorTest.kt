/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingEstimatorTest {
    private val eps = 1e-6

    @Test
    fun wordsFillTheGapToTheNextLine() {
        val entries = WordTimingEstimator.estimate(
            listOf(LyricsEntry(10_000, "There will always be space"), LyricsEntry(14_000, "Right where you left it")),
        )
        val words = entries[0].words!!
        assertEquals(listOf("There", "will", "always", "be", "space"), words.map { it.text })
        assertEquals(10.0, words.first().startTime, eps)
        assertEquals(14.0, words.last().endTime, eps)
        assertTrue(words.zipWithNext().all { (a, b) -> a.endTime <= b.startTime + eps })
        assertFalse(words.last().hasTrailingSpace)
        assertTrue(words.dropLast(1).all { it.hasTrailingSpace })
    }

    @Test
    fun longerWordsGetMoreTime() {
        val words = WordTimingEstimator.estimateLine("a wonderful", 0, 3_000)
        assertTrue(words[1].endTime - words[1].startTime > words[0].endTime - words[0].startTime)
    }

    @Test
    fun longGapIsTreatedAsABreak() {
        val words = WordTimingEstimator.estimateLine("Hello you", 0, 60_000)
        assertTrue(words.last().endTime < 10.0)
    }

    @Test
    fun lastLineHasNoNextStart() {
        val words = WordTimingEstimator.estimateLine("The end", 5_000, null)
        assertEquals(5.0, words.first().startTime, eps)
        assertTrue(words.last().endTime > 5.0)
    }

    @Test
    fun phraseEndingPunctuationLeavesAPause() {
        val words = WordTimingEstimator.estimateLine("Well, okay", 0, 3_000)
        assertTrue(words[1].startTime > words[0].endTime)
    }

    @Test
    fun existingWordTimingsAreKept() {
        val timed = LyricsEntry(0, "Hi", listOf(WordTimestamp("Hi", 0.2, 0.4)))
        val result = WordTimingEstimator.estimate(listOf(timed, LyricsEntry(5_000, "Next")))
        assertSame(timed, result[0])
    }

    @Test
    fun blankLinesStayWithoutWords() {
        val result = WordTimingEstimator.estimate(listOf(LyricsEntry(0, ""), LyricsEntry(1_000, "Hi")))
        assertNull(result[0].words)
    }

    @Test
    fun backgroundLineDoesNotCutTheMainLineShort() {
        val result = WordTimingEstimator.estimate(
            listOf(
                LyricsEntry(0, "Main line of the song"),
                LyricsEntry(2_000, "(oh)", isBackground = true),
                LyricsEntry(4_000, "Next main line"),
            ),
        )
        assertEquals(4.0, result[0].words!!.last().endTime, eps)
    }

    @Test
    fun cjkTextIsSplitPerCharacterWithoutSpaces() {
        val words = WordTimingEstimator.estimateLine("你好世界", 0, 2_000)
        assertEquals(listOf("你", "好", "世", "界"), words.map { it.text })
        assertTrue(words.none { it.hasTrailingSpace })
    }

    @Test
    fun wordsRebuildTheLineText() {
        val text = "Don't stop, believin' - hold on"
        val words = WordTimingEstimator.estimateLine(text, 0, 4_000)
        val rebuilt = words.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
        assertEquals(text, rebuilt)
    }
}
