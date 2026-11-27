/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.min

/** A swatch from the artwork: packed ARGB plus how many pixels it covers. */
data class WeightedColor(val argb: Int, val population: Int)

/**
 * A full set of surfaces and accents derived from one piece of artwork.
 * Every text color is checked against the surface it is drawn on.
 */
@Immutable
data class ArtworkPalette(
    val isDark: Boolean,
    val backgroundDeep: Color,
    val background: Color,
    val surface: Color,
    val accent: Color,
    val accentSecondary: Color,
    val onBackground: Color,
    val onBackgroundMuted: Color,
    val onAccent: Color,
) {
    /** Blends every role toward [other]; [fraction] 0 is this palette, 1 is [other]. */
    fun lerpTo(other: ArtworkPalette, fraction: Float): ArtworkPalette {
        val f = fraction.coerceIn(0f, 1f)
        return when (f) {
            0f -> this
            1f -> other
            else -> ArtworkPalette(
                isDark = if (f < 0.5f) isDark else other.isDark,
                backgroundDeep = lerp(backgroundDeep, other.backgroundDeep, f),
                background = lerp(background, other.background, f),
                surface = lerp(surface, other.surface, f),
                accent = lerp(accent, other.accent, f),
                accentSecondary = lerp(accentSecondary, other.accentSecondary, f),
                onBackground = lerp(onBackground, other.onBackground, f),
                onBackgroundMuted = lerp(onBackgroundMuted, other.onBackgroundMuted, f),
                onAccent = lerp(onAccent, other.onAccent, f),
            )
        }
    }
}

private const val NEUTRAL_SATURATION = 0.08f
private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_MUTED_CONTRAST = 3.2f
private const val MIN_HUE_GAP = 30f

/**
 * Builds a cohesive palette from artwork swatches. Pure function: no Android classes, so it can be
 * unit tested on the JVM. Near-black and near-white swatches are ignored as seeds; if nothing
 * colorful is left (grayscale artwork) the palette stays neutral and takes its accent from [fallbackAccent].
 */
fun buildArtworkPalette(
    swatches: List<WeightedColor>,
    dark: Boolean,
    fallbackAccent: Color = DefaultThemeColor,
): ArtworkPalette {
    val total = swatches.sumOf { it.population.coerceAtLeast(0) }.coerceAtLeast(1).toFloat()
    val candidates = swatches
        .filter { it.population > 0 }
        .map { sw ->
            val hsl = Hsl.from(Color(sw.argb))
            Candidate(hsl, score = (sw.population / total) * (0.25f + hsl.s) * lightnessWeight(hsl.l))
        }
        .sortedByDescending { it.score }

    val seed = candidates.firstOrNull { it.hsl.s >= NEUTRAL_SATURATION && lightnessWeight(it.hsl.l) > 0f }
    val neutral = seed == null
    val fallbackHsl = Hsl.from(fallbackAccent)
    val hue = seed?.hsl?.h ?: fallbackHsl.h
    val sat = seed?.hsl?.s ?: 0f

    val secondHue = candidates
        .firstOrNull { it.hsl.s >= NEUTRAL_SATURATION && hueDistance(it.hsl.h, hue) >= MIN_HUE_GAP }
        ?.hsl?.h
        ?: (hue + 28f)

    val tintSat = if (neutral) 0f else sat
    val surfaceTint = (tintSat * 0.5f).coerceIn(if (neutral) 0f else 0.12f, 0.45f)
    val accentSat = if (neutral) fallbackHsl.s.coerceIn(0.45f, 0.8f) else sat.coerceIn(0.5f, 0.9f)

    val deep: Color
    val background: Color
    val surface: Color
    val accent: Color
    val accentSecondary: Color
    if (dark) {
        deep = hsl(hue, surfaceTint, 0.05f)
        background = hsl(hue, surfaceTint, 0.09f)
        surface = hsl(hue, surfaceTint, 0.15f)
        accent = hsl(hue, accentSat, 0.68f)
        accentSecondary = hsl(secondHue, accentSat * 0.85f, 0.7f)
    } else {
        deep = hsl(hue, surfaceTint, 0.88f)
        background = hsl(hue, surfaceTint, 0.94f)
        surface = hsl(hue, surfaceTint, 0.98f)
        accent = hsl(hue, accentSat, 0.36f)
        accentSecondary = hsl(secondHue, accentSat * 0.85f, 0.4f)
    }

    val textBase = if (dark) hsl(hue, tintSat * 0.15f, 0.95f) else hsl(hue, tintSat * 0.3f, 0.1f)
    val mutedBase = if (dark) hsl(hue, tintSat * 0.2f, 0.72f) else hsl(hue, tintSat * 0.3f, 0.34f)
    val onBackground = ensureContrast(textBase, deepestOf(background, surface, dark), MIN_TEXT_CONTRAST)
    val onMuted = ensureContrast(mutedBase, deepestOf(background, surface, dark), MIN_MUTED_CONTRAST)
    val onAccent = ensureContrast(if (dark) hsl(hue, 0.3f, 0.08f) else Color.White, accent, MIN_TEXT_CONTRAST)

    return ArtworkPalette(
        isDark = dark,
        backgroundDeep = deep,
        background = background,
        surface = surface,
        accent = ensureContrast(accent, background, 3f),
        accentSecondary = ensureContrast(accentSecondary, background, 3f),
        onBackground = onBackground,
        onBackgroundMuted = onMuted,
        onAccent = onAccent,
    )
}

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Moves [fg] lightness away from [bg] in small steps until the ratio is met, or the range runs out. */
fun ensureContrast(fg: Color, bg: Color, minRatio: Float): Color {
    if (contrastRatio(fg, bg) >= minRatio) return fg
    val towardWhite = bg.luminance() < 0.5f
    val start = Hsl.from(fg)
    var l = start.l
    repeat(60) {
        l = if (towardWhite) min(1f, l + 0.02f) else maxOf(0f, l - 0.02f)
        val candidate = hsl(start.h, start.s, l)
        if (contrastRatio(candidate, bg) >= minRatio) return candidate
    }
    return if (towardWhite) Color.White else Color.Black
}

private fun deepestOf(background: Color, surface: Color, dark: Boolean): Color =
    if (dark) {
        if (surface.luminance() > background.luminance()) surface else background
    } else {
        if (surface.luminance() < background.luminance()) surface else background
    }

private class Candidate(val hsl: Hsl, val score: Float)

private fun lightnessWeight(l: Float): Float = when {
    l < 0.08f || l > 0.95f -> 0f
    l < 0.2f -> (l - 0.08f) / 0.12f
    l > 0.85f -> (0.95f - l) / 0.10f
    else -> 1f
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

internal class Hsl(val h: Float, val s: Float, val l: Float) {
    companion object {
        fun from(color: Color): Hsl {
            val r = color.red
            val g = color.green
            val b = color.blue
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val l = (max + min) / 2f
            val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
            var h = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0f) h += 360f
            return Hsl(h, s.coerceIn(0f, 1f), l)
        }
    }
}

internal fun hsl(h: Float, s: Float, l: Float): Color {
    val sat = s.coerceIn(0f, 1f)
    val light = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * light - 1f)) * sat
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = light - c / 2f
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
}
