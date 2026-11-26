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
    fun countsSyllables() {
        val expected = mapOf("love" to 1, "always" to 2, "people" to 2, "baby" to 2, "space" to 1, "you" to 1, "table" to 2, "me" to 1, "\u4F60" to 1)
        expected.forEach { (word, count) -> assertEquals(word, count, WordTimingEstimator.syllablesOf(word)) }
    }

    @Test
    fun wordsAdvanceInOrderFromTheLineStart() {
        val words = WordTimingEstimator.estimateLine("There will always be space", 10_000, 14_000)
        assertEquals(listOf("There", "will", "always", "be", "space"), words.map { it.text })
        assertEquals(10.0, words.first().startTime, eps)
        assertTrue(words.zipWithNext().all { (a, b) -> a.endTime <= b.startTime + eps })
        assertTrue(words.last().endTime <= 14.0 + eps)
        assertFalse(words.last().hasTrailingSpace)
        assertTrue(words.dropLast(1).all { it.hasTrailingSpace })
    }

    @Test
    fun roomBeforeTheNextLineIsNotSpreadOverTheWords() {
        val tight = WordTimingEstimator.estimateLine("There will always be space", 0, 2_000)
        val roomy = WordTimingEstimator.estimateLine("There will always be space", 0, 8_000)
        assertTrue(tight.last().endTime <= 2.0 + eps)
        // Same speed, so the first words take the same time however long the gap is.
        assertTrue(roomy[2].endTime < 3.0)
        assertTrue(roomy.last().endTime - roomy.last().startTime > roomy[0].endTime - roomy[0].startTime)
    }

    @Test
    fun slowLineInShortGapIsSlowedInsteadOfRushed() {
        val fitted = WordTimingEstimator.estimateLine("There will always be space", 0, 6_000, 6.0)
        val songWide = WordTimingEstimator.estimateLine("There will always be space", 0, 8_000, 6.0)
        assertTrue(fitted[2].endTime > songWide[2].endTime)
        assertTrue(fitted.last().endTime <= 6.0 + eps)
    }

    @Test
    fun stretchIsLimitedSoBreaksStayQuiet() {
        val normal = WordTimingEstimator.estimateLine("Hello you", 0, 20_000)
        val shortGap = WordTimingEstimator.estimateLine("Hello you", 0, 6_000)
        assertTrue(shortGap.last().endTime <= normal.last().endTime + 2.0)
        assertTrue(shortGap.last().endTime <= 6.0 + eps)
    }

    @Test
    fun heldLastWordIsCapped() {
        val words = WordTimingEstimator.estimateLine("Hello you", 0, 60_000)
        assertTrue(words.last().endTime < 4.0)
    }

    @Test
    fun moreSyllablesGetMoreTime() {
        val words = WordTimingEstimator.estimateLine("a wonderful", 0, 3_000)
        assertTrue(words[1].endTime - words[1].startTime > words[0].endTime - words[0].startTime)
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
    fun learnsSpeedFromDenseLines() {
        val lines = (0 until 6).map { LyricsEntry(it * 2_000L, "There will always be space") }
        assertEquals(3.0, WordTimingEstimator.songSpeed(lines), eps)
    }

    @Test
    fun fewSamplesFallBackToDefaultSpeed() {
        val lines = listOf(LyricsEntry(0, "There will always be space"), LyricsEntry(2_000, "Hi"))
        assertEquals(3.5, WordTimingEstimator.songSpeed(lines), eps)
    }

    @Test
    fun speedIsClamped() {
        val lines = (0 until 6).map { LyricsEntry(it * 600L, "There will always be space") }
        assertEquals(6.0, WordTimingEstimator.songSpeed(lines), eps)
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
        val end = result[0].words!!.last().endTime
        assertTrue(end > 2.0)
        assertTrue(end <= 4.0 + eps)
    }

    @Test
    fun cjkTextIsSplitPerCharacterWithoutSpaces() {
        val words = WordTimingEstimator.estimateLine("\u4F60\u597D\u4E16\u754C", 0, 2_000)
        assertEquals(listOf("\u4F60", "\u597D", "\u4E16", "\u754C"), words.map { it.text })
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
