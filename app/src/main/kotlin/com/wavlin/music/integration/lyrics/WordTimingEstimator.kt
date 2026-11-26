/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.lyrics

/**
 * Plain LRC (LrcLib, KuGou, ...) only has a start time per line, so there is no real word timing.
 * This spreads each line's text across the window in which it is sung, so the word-by-word
 * highlight works for every synced song.
 *
 * The result is an estimate, not real word sync. Lines that already carry word timings are left
 * untouched. Pure Kotlin, no Android dependencies.
 *
 * Constants are untuned heuristics: check them on real songs before trusting them.
 */
object WordTimingEstimator {
    /** Upper bound of sung seconds per weight unit (about one letter). Longer gaps count as a break. */
    private const val MAX_SECONDS_PER_WEIGHT = 0.25

    /** Shortest cap on the sung window, so very short lines still get a readable sweep. */
    private const val MIN_SUNG_CAP_SECONDS = 3.0

    /** Weight of a CJK character or Hangul syllable: about one syllable, so several letters. */
    private const val SYLLABLE_WEIGHT = 2.5

    /** Extra silent weight after a word that ends a phrase (comma, period, ...). */
    private const val PHRASE_PAUSE_WEIGHT = 1.5

    private val PAUSE_CHARS = setOf(',', ';', ':', '.', '!', '?', '\u2026', '\u2014', '\u2013', '\uFF0C', '\u3002', '\uFF01', '\uFF1F', '\u3001')

    private data class Token(
        val text: String,
        val hasTrailingSpace: Boolean,
        val weight: Double,
        val pauseWeight: Double,
    )

    fun estimate(entries: List<LyricsEntry>): List<LyricsEntry> {
        if (entries.none { it.words.isNullOrEmpty() && it.text.isNotBlank() }) return entries
        return entries.map { entry ->
            if (!entry.words.isNullOrEmpty() || entry.text.isBlank()) {
                entry
            } else {
                val words = estimateLine(entry.text, entry.time, nextStartMs(entries, entry))
                if (words.isEmpty()) entry else entry.copy(words = words)
            }
        }
    }

    /**
     * Start of the next line that ends this one. A main line ignores background lines (they start
     * in the middle of it); a background line ends at the next line of any kind.
     */
    private fun nextStartMs(entries: List<LyricsEntry>, entry: LyricsEntry): Long? =
        entries.asSequence()
            .filter { it.time > entry.time && (entry.isBackground || !it.isBackground) }
            .minOfOrNull { it.time }

    internal fun estimateLine(text: String, startMs: Long, nextStartMs: Long?): List<WordTimestamp> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val totalWeight = tokens.sumOf { it.weight }
        val maxSungSeconds = maxOf(MIN_SUNG_CAP_SECONDS, totalWeight * MAX_SECONDS_PER_WEIGHT)
        val availableSeconds = nextStartMs?.let { (it - startMs) / 1000.0 }
        val sungSeconds = if (availableSeconds != null && availableSeconds <= maxSungSeconds) {
            availableSeconds
        } else {
            maxSungSeconds
        }

        // The pause after the last word is silence at the end of the line, not part of the sweep.
        val timedWeight = tokens.sumOf { it.weight } + tokens.dropLast(1).sumOf { it.pauseWeight }
        val secondsPerWeight = sungSeconds / timedWeight

        var cursor = startMs / 1000.0
        return tokens.mapIndexed { index, token ->
            val start = cursor
            val end = start + token.weight * secondsPerWeight
            cursor = if (index == tokens.lastIndex) end else end + token.pauseWeight * secondsPerWeight
            WordTimestamp(token.text, start, end, token.hasTrailingSpace)
        }
    }

    private fun tokenize(text: String): List<Token> {
        val rawTokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val tokens = ArrayList<Token>()
        rawTokens.forEachIndexed { rawIndex, raw ->
            val isLastRaw = rawIndex == rawTokens.lastIndex
            val parts = splitSyllabicScripts(raw)
            parts.forEachIndexed { partIndex, part ->
                val isLastPart = partIndex == parts.lastIndex
                val pause = if (isLastPart && part.last() in PAUSE_CHARS) PHRASE_PAUSE_WEIGHT else 0.0
                tokens += Token(
                    text = part,
                    hasTrailingSpace = isLastPart && !isLastRaw,
                    weight = weightOf(part),
                    pauseWeight = pause,
                )
            }
        }
        return tokens
    }

    /** Han, Hiragana and Katakana have no spaces between words, so each character is its own unit. */
    private fun splitSyllabicScripts(raw: String): List<String> {
        if (raw.codePoints().noneMatch { isUnspacedScript(it) }) return listOf(raw)
        val parts = ArrayList<String>()
        val pending = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val len = Character.charCount(cp)
            if (isUnspacedScript(cp)) {
                if (pending.isNotEmpty()) {
                    parts += pending.toString()
                    pending.clear()
                }
                parts += raw.substring(i, i + len)
            } else {
                pending.appendCodePoint(cp)
            }
            i += len
        }
        if (pending.isNotEmpty()) parts += pending.toString()
        return mergePunctuation(parts)
    }

    /** Punctuation-only fragments join the character before them, or the first one after them. */
    private fun mergePunctuation(parts: List<String>): List<String> {
        val merged = ArrayList<String>()
        var prefix = ""
        for (part in parts) {
            val hasSound = part.codePoints().anyMatch { Character.isLetterOrDigit(it) }
            when {
                hasSound -> {
                    merged += prefix + part
                    prefix = ""
                }
                merged.isNotEmpty() -> merged[merged.lastIndex] = merged.last() + part
                else -> prefix += part
            }
        }
        if (prefix.isNotEmpty()) merged += prefix
        return merged
    }

    private fun isUnspacedScript(codePoint: Int): Boolean {
        val script = Character.UnicodeScript.of(codePoint)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
    }

    private fun weightOf(token: String): Double {
        var weight = 0.0
        token.codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp)) {
                weight += if (isUnspacedScript(cp) || isHangul(cp)) SYLLABLE_WEIGHT else 1.0
            }
        }
        return maxOf(weight, 1.0)
    }

    private fun isHangul(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL
}
