package dev.geode

import dev.geode.analysis.ArtPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for palettes lifted from album artwork.
 *
 * The interesting failures are all about what a naive implementation gets
 * wrong: averaging a two-colour sleeve into grey, letting a big flat
 * background outvote the artwork's actual accent, and answering "grey" for a
 * greyscale cover instead of admitting there is nothing to take.
 */
class ArtPaletteTest {
    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A solid block of one colour, [n] pixels. */
    private fun block(
        n: Int,
        r: Int,
        g: Int,
        b: Int,
    ) = IntArray(n) { argb(r, g, b) }

    private val red = { n: Int -> block(n, 220, 30, 30) }
    private val cyan = { n: Int -> block(n, 30, 210, 220) }
    private val nearWhite = { n: Int -> block(n, 246, 246, 244) }
    private val nearBlack = { n: Int -> block(n, 6, 6, 7) }

    @Test
    fun aSingleColourSleeveGivesThatColour() {
        val out = ArtPalette.extract(red(400))!!
        // Red sits at the top of the wheel, so accept either side of the wrap.
        val d = minOf(out.baseHue, 1f - out.baseHue)
        assertTrue("expected a red hue, got ${out.baseHue}", d < 0.05f)
        assertTrue("a flat colour should read as a narrow span", out.span < 0.25f)
    }

    @Test
    fun aTwoColourSleeveDoesNotAverageIntoTheColourBetweenThem() {
        // The classic mistake: red + cyan averaged is grey, which is the one
        // colour the artwork certainly is not.
        val pixels = red(300) + cyan(300)
        val out = ArtPalette.extract(pixels)!!
        val toRed = minOf(out.baseHue, 1f - out.baseHue)
        val toCyan = kotlin.math.abs(out.baseHue - 0.5f)
        assertTrue(
            "hue ${out.baseHue} is neither of the two colours in the artwork",
            toRed < 0.08f || toCyan < 0.08f,
        )
        assertTrue("two distinct colours should report a wide span", out.span > 0.3f)
    }

    @Test
    fun aFlatBackgroundDoesNotOutvoteTheAccent() {
        // A sleeve is mostly background. Near-white and near-black carry no
        // hue opinion, so a small vivid accent has to win.
        val pixels = nearWhite(3_000) + nearBlack(3_000) + cyan(200)
        val out = ArtPalette.extract(pixels)!!
        assertEquals("the accent colour should decide the hue", 0.5f, out.baseHue, 0.06f)
        assertTrue("confidence should reflect how little of it had colour", out.confidence < 0.1f)
    }

    @Test
    fun aVividAccentOutweighsAWashedOutFieldOfAnotherHue() {
        // Saturation-weighted, which is how a person reads a sleeve: the
        // saturated stripe is "the colour", not the pale wash behind it.
        val pale = block(2_000, 180, 200, 190)
        val vivid = block(300, 240, 10, 200)
        val out = ArtPalette.extract(pale + vivid)!!
        assertEquals("the vivid accent should win", 0.86f, out.baseHue, 0.08f)
    }

    @Test
    fun greyscaleArtworkReportsNothingRatherThanGrey() {
        // "This sleeve has no colour" is a real answer, and the caller's right
        // response is to leave the user's palette alone.
        assertNull(ArtPalette.extract(IntArray(500) { argb(it % 256, it % 256, it % 256) }))
        assertNull(ArtPalette.extract(nearBlack(500)))
        assertNull(ArtPalette.extract(nearWhite(500)))
        assertNull(ArtPalette.extract(IntArray(0)))
    }

    @Test
    fun everyResultIsInsideThePaletteDomain() {
        // baseHue and span feed SceneParams' override fields, where a negative
        // reads back as "no override" and silently falls through.
        val samples =
            listOf(
                red(100) + cyan(100),
                block(500, 10, 90, 200),
                nearWhite(400) + block(50, 250, 200, 10),
                IntArray(600) { argb((it * 7) % 256, (it * 13) % 256, (it * 29) % 256) },
            )
        for (pixels in samples) {
            val out = ArtPalette.extract(pixels) ?: continue
            assertTrue("hue ${out.baseHue} out of [0,1)", out.baseHue >= 0f && out.baseHue < 1f)
            assertTrue("span ${out.span} out of (0,1]", out.span > 0f && out.span <= 1f)
            assertTrue("confidence ${out.confidence} out of [0,1]", out.confidence in 0f..1f)
        }
    }

    @Test
    fun theSpanIsNeverZeroSoGradientsDoNotCollapse() {
        // A span of zero would flatten every gradient in the app to one tint,
        // which no built-in palette does.
        val out = ArtPalette.extract(red(1_000))!!
        assertTrue("span collapsed to ${out.span}", out.span > 0.01f)
    }

    @Test
    fun theSameArtworkAlwaysGivesTheSamePalette() {
        // A track's colour identity has to be stable across plays.
        val pixels = red(200) + cyan(150) + nearWhite(900)
        val a = ArtPalette.extract(pixels)
        val b = ArtPalette.extract(pixels.copyOf())
        assertNotNull(a)
        assertEquals(a, b)
    }
}
