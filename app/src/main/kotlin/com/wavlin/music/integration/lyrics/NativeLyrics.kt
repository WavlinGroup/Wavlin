/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.lyrics

import timber.log.Timber

/**
 * JNI bridge to the lyrics parser in `libwavlin_audio.so` (Rust crate `rust/crates/lyrics-core`).
 *
 * The library is optional at runtime. Both functions return null when it is missing or the native
 * call fails, and [LyricsUtils] then runs its Kotlin implementation.
 */
object NativeLyrics {

    private const val TAG = "NativeLyrics"

    @JvmField
    val isAvailable: Boolean = try {
        System.loadLibrary("wavlin_audio")
        true
    } catch (e: UnsatisfiedLinkError) {
        Timber.tag(TAG).w(e, "libwavlin_audio.so not loaded, using Kotlin lyrics parser")
        false
    }

    /**
     * Column layout, in order: times (ms), texts, agents (nullable), isBackground, wordCounts
     * (-1 when a line has no word timings), wordTexts, wordStarts (s), wordEnds (s),
     * wordTrailingSpaces. Words of all lines are concatenated.
     */
    @JvmStatic external fun parseNative(text: String): Array<Any?>?

    @JvmStatic external fun filterCreditLinesNative(text: String): String?

    fun parse(lyrics: String): List<LyricsEntry>? {
        if (!isAvailable) return null
        val columns = try {
            parseNative(lyrics)
        } catch (e: UnsatisfiedLinkError) {
            return null
        } ?: return null
        return unpack(columns)
    }

    fun filterCreditLines(lyrics: String): String? {
        if (!isAvailable) return null
        return try {
            filterCreditLinesNative(lyrics)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun unpack(c: Array<Any?>): List<LyricsEntry>? {
        if (c.size != 9) return null
        val times = c[0] as? LongArray ?: return null
        val texts = c[1] as? Array<String?> ?: return null
        val agents = c[2] as? Array<String?> ?: return null
        val background = c[3] as? BooleanArray ?: return null
        val counts = c[4] as? IntArray ?: return null
        val wordTexts = c[5] as? Array<String?> ?: return null
        val starts = c[6] as? DoubleArray ?: return null
        val ends = c[7] as? DoubleArray ?: return null
        val trailing = c[8] as? BooleanArray ?: return null

        val n = times.size
        if (texts.size != n || agents.size != n || background.size != n || counts.size != n) return null
        val wordTotal = wordTexts.size
        if (starts.size != wordTotal || ends.size != wordTotal || trailing.size != wordTotal) return null

        val entries = ArrayList<LyricsEntry>(n)
        var wordIndex = 0
        for (i in 0 until n) {
            val count = counts[i]
            val words = if (count < 0) {
                null
            } else {
                if (wordIndex + count > wordTotal) return null
                val list = ArrayList<WordTimestamp>(count)
                repeat(count) {
                    val text = wordTexts[wordIndex] ?: return null
                    list.add(WordTimestamp(text, starts[wordIndex], ends[wordIndex], trailing[wordIndex]))
                    wordIndex++
                }
                list
            }
            entries.add(
                LyricsEntry(
                    time = times[i],
                    text = texts[i] ?: return null,
                    words = words,
                    agent = agents[i],
                    isBackground = background[i],
                ),
            )
        }
        return if (wordIndex == wordTotal) entries else null
    }
}
