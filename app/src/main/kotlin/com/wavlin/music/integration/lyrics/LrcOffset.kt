/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.lyrics

/**
 * Header tag `[offset:+/-ms]` of an LRC file. Per the LRC convention a positive value makes the
 * lyrics appear sooner, so every timestamp moves by minus the offset. Pure Kotlin, no Android types.
 */
object LrcOffset {
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d{1,6})\s*]""", RegexOption.IGNORE_CASE)

    /** The tag is a header: only the start of the text is searched. */
    private const val HEADER_SEARCH_LENGTH = 1024

    /** Larger values are treated as a broken tag. */
    private const val MAX_ABS_OFFSET_MS = 30_000L

    fun read(lyrics: String): Long {
        val value = OFFSET_TAG.find(lyrics.take(HEADER_SEARCH_LENGTH))
            ?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        return if (kotlin.math.abs(value) <= MAX_ABS_OFFSET_MS) value else 0L
    }

    fun apply(entries: List<LyricsEntry>, offsetMs: Long): List<LyricsEntry> {
        if (offsetMs == 0L) return entries
        val offsetSeconds = offsetMs / 1000.0
        return entries.map { entry ->
            entry.copy(
                time = (entry.time - offsetMs).coerceAtLeast(0L),
                words = entry.words?.map {
                    it.copy(
                        startTime = (it.startTime - offsetSeconds).coerceAtLeast(0.0),
                        endTime = (it.endTime - offsetSeconds).coerceAtLeast(0.0),
                    )
                },
            )
        }
    }
}
