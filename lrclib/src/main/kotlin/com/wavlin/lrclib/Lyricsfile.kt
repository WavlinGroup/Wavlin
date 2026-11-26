package com.wavlin.lrclib

import java.util.Locale

/**
 * Reads the `lyricsfile` YAML that LRCLIB stores next to the plain and line-synced lyrics, and
 * converts it to the rich-sync text the app parses: `[mm:ss.mmm]<mm:ss.mmm>word <mm:ss.mmm>word`.
 *
 * Only the fields seen in the LRCLIB server sources are read: per line `text`, `start_ms`,
 * `end_ms` and `words`, per word `text` and `start_ms`. A word's end time is the start of the next
 * word. Spaces between words are part of the word text, as the server derives plain text by joining
 * word texts. YAML outside this subset (block scalars, flow collections, multi-line scalars) makes
 * the whole conversion return null, and the caller keeps the line-synced lyrics.
 */
internal object Lyricsfile {
    private class Unsupported : Exception()

    private class WordDraft {
        var text: String? = null
        var startMs: Long? = null
    }

    private class LineDraft {
        var text: String? = null
        var startMs: Long? = null
        var endMs: Long? = null
        val words = ArrayList<WordDraft>()
    }

    fun toWordSyncedLrc(yaml: String): String? {
        val lines = try {
            parseLines(yaml)
        } catch (e: Unsupported) {
            return null
        }
        if (lines.none { line -> usableWords(line) != null }) return null
        val formatted = lines
            .filter { it.startMs != null }
            .sortedBy { it.startMs }
            .mapNotNull(::formatLine)
        return formatted.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun usableWords(line: LineDraft): List<WordDraft>? {
        val words = line.words.filter { !it.text.isNullOrEmpty() }
        if (words.isEmpty()) return null
        if (words.any { it.startMs == null || it.text!!.contains('<') || it.text!!.contains('>') }) return null
        val starts = words.map { it.startMs!! }
        if (starts.zipWithNext().any { (a, b) -> b < a }) return null
        return words
    }

    private fun formatLine(line: LineDraft): String? {
        val lineStart = line.startMs ?: return null
        val words = usableWords(line)
        if (words == null) {
            val text = line.text ?: line.words.mapNotNull { it.text }.joinToString("").ifEmpty { null }
            return "[${timestamp(lineStart)}]${text.orEmpty().trim()}"
        }
        val sb = StringBuilder("[").append(timestamp(lineStart)).append(']')
        words.forEach { word -> sb.append('<').append(timestamp(word.startMs!!)).append('>').append(word.text) }
        val lastWordStart = words.last().startMs!!
        line.endMs?.takeIf { it > lastWordStart }?.let { sb.append('<').append(timestamp(it)).append('>') }
        return sb.toString()
    }

    internal fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d.%03d", safe / 60_000, (safe / 1000) % 60, safe % 1000)
    }

    private fun parseLines(yaml: String): List<LineDraft> {
        val rows = yaml.lines()
        val header = rows.indexOfFirst { stripComment(it).trim() == "lines:" }
        if (header < 0) throw Unsupported()

        val result = ArrayList<LineDraft>()
        var lineItemIndent = -1
        var lineKeyIndent = -1
        var line: LineDraft? = null
        var word: WordDraft? = null
        var inWords = false

        for (index in header + 1 until rows.size) {
            val raw = rows[index]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (raw.contains('\t')) throw Unsupported()
            val indent = raw.length - raw.trimStart().length
            val isItem = trimmed == "-" || trimmed.startsWith("- ")
            if (indent == 0 && !isItem) break

            var body = trimmed
            var keyIndent = indent
            if (isItem) {
                if (lineItemIndent < 0) lineItemIndent = indent
                body = trimmed.removePrefix("-").trimStart()
                keyIndent = indent + (trimmed.length - body.length)
                if (indent == lineItemIndent) {
                    line = LineDraft().also { result += it }
                    lineKeyIndent = keyIndent
                    word = null
                    inWords = false
                } else if (inWords && line != null && indent > lineItemIndent) {
                    word = WordDraft().also { line.words += it }
                } else {
                    throw Unsupported()
                }
                if (body.isEmpty()) continue
            }

            val current = line ?: throw Unsupported()
            val colon = body.indexOf(':')
            if (colon <= 0) throw Unsupported()
            val key = body.substring(0, colon)
            val rest = body.substring(colon + 1).trim()
            if (!key.all { it.isLetterOrDigit() || it == '_' }) throw Unsupported()

            val wordTarget = word
            if (wordTarget != null && keyIndent > lineKeyIndent) {
                when (key) {
                    "text" -> wordTarget.text = scalar(rest)
                    "start_ms" -> wordTarget.startMs = number(rest)
                }
            } else {
                word = null
                when (key) {
                    "text" -> current.text = scalar(rest)
                    "start_ms" -> current.startMs = number(rest)
                    "end_ms" -> current.endMs = number(rest)
                    "words" -> {
                        val value = stripComment(rest).trim()
                        if (value.isNotEmpty() && value != "[]") throw Unsupported()
                        inWords = value.isEmpty()
                    }
                }
            }
        }
        return result
    }

    private fun number(rest: String): Long {
        val value = scalar(rest)
        return value.toLongOrNull() ?: value.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: throw Unsupported()
    }

    private fun scalar(rest: String): String {
        if (rest.isEmpty()) return ""
        return when (rest[0]) {
            '\'' -> singleQuoted(rest)
            '"' -> doubleQuoted(rest)
            '|', '>', '[', '{', '&', '*', '!' -> throw Unsupported()
            else -> stripComment(rest).trim()
        }
    }

    private fun stripComment(text: String): String {
        val hash = text.indexOf(" #")
        return if (hash >= 0) text.substring(0, hash) else text
    }

    private fun singleQuoted(rest: String): String {
        val sb = StringBuilder()
        var i = 1
        while (i < rest.length) {
            val c = rest[i]
            if (c == '\'') {
                if (i + 1 < rest.length && rest[i + 1] == '\'') {
                    sb.append('\'')
                    i += 2
                    continue
                }
                requireOnlyCommentAfter(rest, i + 1)
                return sb.toString()
            }
            sb.append(c)
            i++
        }
        throw Unsupported()
    }

    private fun doubleQuoted(rest: String): String {
        val sb = StringBuilder()
        var i = 1
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '"' -> {
                    requireOnlyCommentAfter(rest, i + 1)
                    return sb.toString()
                }
                c == '\\' -> {
                    if (i + 1 >= rest.length) throw Unsupported()
                    when (val next = rest[i + 1]) {
                        '\\', '"', '/' -> sb.append(next)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '0' -> sb.append('\u0000')
                        'u' -> {
                            val hex = rest.substring(i + 2, minOf(i + 6, rest.length))
                            if (hex.length != 4) throw Unsupported()
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: throw Unsupported())
                            i += 4
                        }
                        else -> throw Unsupported()
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        throw Unsupported()
    }

    private fun requireOnlyCommentAfter(rest: String, from: Int) {
        val tail = rest.substring(from).trim()
        if (tail.isNotEmpty() && !tail.startsWith("#")) throw Unsupported()
    }
}
