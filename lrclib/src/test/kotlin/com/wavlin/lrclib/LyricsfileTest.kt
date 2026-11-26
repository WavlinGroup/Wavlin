package com.wavlin.lrclib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsfileTest {
    @Test
    fun convertsWordsToRichSyncLine() {
        val yaml = "version: '1.0'\nmetadata:\n  title: Word Song\n  instrumental: false\nlines:\n  - text: Hello world\n    start_ms: 1000\n    end_ms: 2500\n    words:\n      - text: 'Hello '\n        start_ms: 1000\n      - text: world\n        start_ms: 1500\nplain: |\n  Hello world\n"
        assertEquals("[00:01.000]<00:01.000>Hello <00:01.500>world<00:02.500>", Lyricsfile.toWordSyncedLrc(yaml))
    }

    @Test
    fun keepsLinesWithoutWordsAsLineSync() {
        val yaml = "lines:\n  - text: First\n    start_ms: 500\n    words:\n      - text: First\n        start_ms: 500\n  - text: Second\n    start_ms: 3000\n"
        assertEquals("[00:00.500]<00:00.500>First\n[00:03.000]Second", Lyricsfile.toWordSyncedLrc(yaml))
    }

    @Test
    fun readsDoubleQuotedEscapesAndComments() {
        val yaml = "lines:\n  - text: x\n    start_ms: 61500 # comment\n    words:\n      - text: \"Don\\u0027t \"\n        start_ms: 61500\n      - text: 'it''s'\n        start_ms: 62000\n"
        assertEquals("[01:01.500]<01:01.500>Don't <01:02.000>it's", Lyricsfile.toWordSyncedLrc(yaml))
    }

    @Test
    fun readsIndentlessSequences() {
        val yaml = "lines:\n- text: Hi you\n  start_ms: 0\n  words:\n  - text: 'Hi '\n    start_ms: 0\n  - text: you\n    start_ms: 400\n"
        assertEquals("[00:00.000]<00:00.000>Hi <00:00.400>you", Lyricsfile.toWordSyncedLrc(yaml))
    }

    @Test
    fun lineOnlyFileReturnsNull() {
        assertNull(Lyricsfile.toWordSyncedLrc("lines:\n  - text: Hello\n    start_ms: 1000\n"))
    }

    @Test
    fun unsupportedYamlReturnsNull() {
        assertNull(Lyricsfile.toWordSyncedLrc("lines:\n  - text: |\n      Hello\n    start_ms: 1000\n    words:\n      - text: Hello\n        start_ms: 1000\n"))
        assertNull(Lyricsfile.toWordSyncedLrc("not yaml at all"))
    }

    @Test
    fun wordsGoingBackwardsFallBackToLineSync() {
        val yaml = "lines:\n  - text: A B\n    start_ms: 1000\n    words:\n      - text: 'A '\n        start_ms: 2000\n      - text: B\n        start_ms: 1500\n  - text: C\n    start_ms: 4000\n    words:\n      - text: C\n        start_ms: 4000\n"
        assertEquals("[00:01.000]A B\n[00:04.000]<00:04.000>C", Lyricsfile.toWordSyncedLrc(yaml))
    }
}
