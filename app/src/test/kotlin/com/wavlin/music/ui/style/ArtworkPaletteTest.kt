package com.wavlin.music.ui.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {

    private val red = WeightedColor(Color(0xFFD03040).toArgb(), 5000)
    private val teal = WeightedColor(Color(0xFF20A0A8).toArgb(), 1500)
    private val nearBlack = WeightedColor(Color(0xFF050505).toArgb(), 9000)
    private val gray = WeightedColor(Color(0xFF808080).toArgb(), 4000)

    @Test
    fun `text meets contrast on dark and light palettes`() {
        for (dark in listOf(true, false)) {
            val p = buildArtworkPalette(listOf(red, teal, nearBlack), dark)
            assertTrue(contrastRatio(p.onBackground, p.background) >= 4.5f)
            assertTrue(contrastRatio(p.onBackground, p.surface) >= 4.5f)
            assertTrue(contrastRatio(p.onAccent, p.accent) >= 4.5f)
            assertTrue(contrastRatio(p.accent, p.background) >= 3f)
        }
    }

    @Test
    fun `dominant near-black swatch does not become the seed`() {
        val p = buildArtworkPalette(listOf(nearBlack, red), dark = true)
        val hue = Hsl.from(p.accent).h
        assertTrue("hue $hue should be red", hue < 20f || hue > 340f)
    }

    @Test
    fun `grayscale artwork stays neutral`() {
        val p = buildArtworkPalette(listOf(gray, nearBlack), dark = true)
        assertTrue(Hsl.from(p.background).s < 0.02f)
    }

    @Test
    fun `empty input does not crash and still meets contrast`() {
        val p = buildArtworkPalette(emptyList(), dark = false)
        assertTrue(contrastRatio(p.onBackground, p.background) >= 4.5f)
    }

    @Test
    fun `secondary accent differs in hue when artwork has two colors`() {
        val p = buildArtworkPalette(listOf(red, teal), dark = true)
        val d = Math.abs(Hsl.from(p.accent).h - Hsl.from(p.accentSecondary).h)
        assertTrue(d > 30f)
    }

    @Test
    fun `lerp endpoints return the same palettes`() {
        val a = buildArtworkPalette(listOf(red), dark = true)
        val b = buildArtworkPalette(listOf(teal), dark = true)
        assertEquals(a, a.lerpTo(b, 0f))
        assertEquals(b, a.lerpTo(b, 1f))
    }
}
