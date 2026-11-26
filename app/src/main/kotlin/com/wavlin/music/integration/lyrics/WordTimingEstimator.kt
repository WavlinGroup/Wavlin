/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.lyrics

/**
 * Plain LRC (LrcLib, KuGou, ...) only has a start time per line, so there is no real word timing.
 * This estimates it so the word-by-word highlight works for every synced song:
 *
 * 1. Each word gets a weight in syllables, because singing follows syllables, not letters.
 * 2. The singing speed (syllables per second) is learned per song from its own lines, using the
 *    dense lines that have little room for held notes.
 * 3. Words move at that speed from the line start. When the gap to the next line is short enough
 *    to be one sung phrase, the line is slowed to fill most of that gap, so a slow line is not
 *    swept early by the song-wide speed. Room still left goes mostly to a held last word.
 *
 * The result is an estimate, not real word sync. Lines that already carry word timings are left
 * untouched. Pure Kotlin, no Android dependencies. All constants are untuned heuristics.
 */
object WordTimingEstimator {
    /** Used when a song has too few usable lines to learn its own speed. */
    private const val DEFAULT_SYLLABLES_PER_SECOND = 3.5
    private const val MIN_SYLLABLES_PER_SECOND = 2.0
    private const val MAX_SYLLABLES_PER_SECOND = 6.0

    /** Lines with a longer gap are breaks or held passages, not a sample of singing speed. */
    private const val MAX_SAMPLE_GAP_SECONDS = 6.0
    private const val MIN_SAMPLE_SYLLABLES = 3
    private const val MIN_SAMPLES = 4

    /** Dense lines have the least slack, so a high percentile approximates the real speed. */
    private const val SPEED_PERCENTILE = 0.75

    /** Share of a short gap that the sung words fill, and how far a line may slow down to do it. */
    private const val LINE_FILL_SHARE = 0.9
    private const val MAX_LINE_STRETCH = 2.0

    /** Share of the leftover room given to the held last word, and its upper bound. */
    private const val HOLD_SHARE = 0.6
    private const val MAX_HOLD_SECONDS = 1.5

    /** Hold of the last line of a song, which has no next line to measure against. */
    private const val LAST_LINE_HOLD_SECONDS = 0.5

    /** Silent weight, in syllables, after a word that ends a phrase (comma, period, ...). */
    private const val PHRASE_PAUSE_SYLLABLES = 0.5

    private val PAUSE_CHARS = setOf(',', ';', ':', '.', '!', '?', '\u2026', '\u2014', '\u2013', '\uFF0C', '\u3002', '\uFF01', '\uFF1F', '\u3001')
    private val VOWELS_LATIN = "aeiouy\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF\u00F2\u00F3\u00F4\u00F5\u00F6\u00F8\u00F9\u00FA\u00FB\u00FC\u00FD\u0153".toSet()
    private val VOWELS_CYRILLIC = "\u0430\u0435\u0451\u0438\u043E\u0443\u044B\u044D\u044E\u044F\u0456\u0457\u0454".toSet()
    private val VOWELS_GREEK = "\u03B1\u03B5\u03B7\u03B9\u03BF\u03C5\u03C9\u03AC\u03AD\u03AE\u03AF\u03CC\u03CD\u03CE".toSet()

    private data class Token(
        val text: String,
        val hasTrailingSpace: Boolean,
        val syllables: Double,
        val pauseSyllables: Double,
    )

    fun estimate(entries: List<LyricsEntry>): List<LyricsEntry> {
        if (entries.none { it.words.isNullOrEmpty() && it.text.isNotBlank() }) return entries
        val speed = songSpeed(entries)
        return entries.map { entry ->
            if (!entry.words.isNullOrEmpty() || entry.text.isBlank()) {
                entry
            } else {
                val words = estimateLine(entry.text, entry.time, nextStartMs(entries, entry), speed)
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

    /** Singing speed of the whole song, in syllables per second. */
    internal fun songSpeed(entries: List<LyricsEntry>): Double {
        val samples = entries.asSequence()
            .filter { it.words.isNullOrEmpty() && it.text.isNotBlank() && !it.isBackground }
            .mapNotNull { entry ->
                val next = nextStartMs(entries, entry) ?: return@mapNotNull null
                val gap = (next - entry.time) / 1000.0
                val syllables = tokenize(entry.text).sumOf { it.syllables }
                if (gap in 0.5..MAX_SAMPLE_GAP_SECONDS && syllables >= MIN_SAMPLE_SYLLABLES) syllables / gap else null
            }
            .sorted()
            .toList()
        if (samples.size < MIN_SAMPLES) return DEFAULT_SYLLABLES_PER_SECOND
        val index = ((samples.size - 1) * SPEED_PERCENTILE).toInt()
        return samples[index].coerceIn(MIN_SYLLABLES_PER_SECOND, MAX_SYLLABLES_PER_SECOND)
    }

    internal fun estimateLine(
        text: String,
        startMs: Long,
        nextStartMs: Long?,
        syllablesPerSecond: Double = DEFAULT_SYLLABLES_PER_SECOND,
    ): List<WordTimestamp> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        // The pause after the last word is silence at the end of the line, not part of the sweep.
        val timedSyllables = tokens.sumOf { it.syllables } + tokens.dropLast(1).sumOf { it.pauseSyllables }
        val bodySeconds = timedSyllables / syllablesPerSecond
        val availableSeconds = nextStartMs?.let { (it - startMs) / 1000.0 }
        val sungSeconds = sungSeconds(bodySeconds, timedSyllables, availableSeconds)
        val secondsPerSyllable = sungSeconds / timedSyllables

        val holdSeconds = if (availableSeconds != null) {
            minOf((availableSeconds - sungSeconds) * HOLD_SHARE, MAX_HOLD_SECONDS)
        } else {
            LAST_LINE_HOLD_SECONDS
        }

        var cursor = startMs / 1000.0
        return tokens.mapIndexed { index, token ->
            val start = cursor
            var end = start + token.syllables * secondsPerSyllable
            cursor = if (index == tokens.lastIndex) end else end + token.pauseSyllables * secondsPerSyllable
            if (index == tokens.lastIndex) end += holdSeconds
            WordTimestamp(token.text, start, end, token.hasTrailingSpace)
        }
    }

    /**
     * Time the words sweep over. A line that does not fit its gap is compressed to it. A line in a
     * short gap is slowed toward the gap, limited by [MAX_LINE_STRETCH] and the slowest believable
     * speed, so one fast song-wide speed cannot rush the words of a slow line. Long gaps are breaks
     * and keep the song-wide speed.
     */
    private fun sungSeconds(bodySeconds: Double, timedSyllables: Double, availableSeconds: Double?): Double {
        if (availableSeconds == null) return bodySeconds
        if (availableSeconds <= bodySeconds) return availableSeconds
        if (availableSeconds > MAX_SAMPLE_GAP_SECONDS) return bodySeconds
        val stretchLimit = minOf(bodySeconds * MAX_LINE_STRETCH, timedSyllables / MIN_SYLLABLES_PER_SECOND)
        return maxOf(bodySeconds, minOf(availableSeconds * LINE_FILL_SHARE, stretchLimit))
    }

    private fun tokenize(text: String): List<Token> {
        val rawTokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val tokens = ArrayList<Token>()
        rawTokens.forEachIndexed { rawIndex, raw ->
            val isLastRaw = rawIndex == rawTokens.lastIndex
            val parts = splitUnspacedScripts(raw)
            parts.forEachIndexed { partIndex, part ->
                val isLastPart = partIndex == parts.lastIndex
                val pause = if (isLastPart && part.last() in PAUSE_CHARS) PHRASE_PAUSE_SYLLABLES else 0.0
                tokens += Token(
                    text = part,
                    hasTrailingSpace = isLastPart && !isLastRaw,
                    syllables = syllablesOf(part).toDouble(),
                    pauseSyllables = pause,
                )
            }
        }
        return tokens
    }

    /** Han, Hiragana and Katakana have no spaces between words, so each character is its own unit. */
    private fun splitUnspacedScripts(raw: String): List<String> {
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

    /**
     * Syllables of one word, at least 1. Latin, Cyrillic and Greek count vowel groups; Han, Kana and
     * Hangul count one per character; other scripts use about one syllable per 2.5 letters.
     */
    internal fun syllablesOf(word: String): Int {
        var total = 0
        var otherLetters = 0
        val run = StringBuilder()
        var runVowels: Set<Char> = VOWELS_LATIN

        fun flushRun() {
            if (run.isNotEmpty()) total += countVowelGroups(run.toString().lowercase(), runVowels)
            run.setLength(0)
        }

        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetterOrDigit(cp)) {
                flushRun()
                continue
            }
            val script = Character.UnicodeScript.of(cp)
            val vowels = when (script) {
                Character.UnicodeScript.LATIN -> VOWELS_LATIN
                Character.UnicodeScript.CYRILLIC -> VOWELS_CYRILLIC
                Character.UnicodeScript.GREEK -> VOWELS_GREEK
                else -> null
            }
            when {
                isUnspacedScript(cp) || script == Character.UnicodeScript.HANGUL -> {
                    flushRun()
                    total += 1
                }
                vowels != null -> {
                    if (run.isNotEmpty() && runVowels !== vowels) flushRun()
                    runVowels = vowels
                    run.appendCodePoint(cp)
                }
                else -> {
                    flushRun()
                    otherLetters++
                }
            }
        }
        flushRun()
        if (otherLetters > 0) total += maxOf(1, Math.round(otherLetters / 2.5f))
        return maxOf(total, 1)
    }

    private fun countVowelGroups(letters: String, vowels: Set<Char>): Int {
        var groups = 0
        var inGroup = false
        for (c in letters) {
            val isVowel = c in vowels
            if (isVowel && !inGroup) groups++
            inGroup = isVowel
        }
        // Silent final "e" as in "love" or "space", but not "table" or "free".
        if (vowels === VOWELS_LATIN && groups > 1 && letters.length > 2 && letters.endsWith('e')) {
            val before = letters[letters.length - 2]
            if (before != 'l' && before !in vowels) groups--
        }
        return groups
    }
}
