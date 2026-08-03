package dev.musicviz

import dev.musicviz.ui.TextColorPref
import dev.musicviz.ui.TextContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless coverage for the pure half of the "Custom text colour" option: the
 * WCAG contrast math the legibility gate is built on, the HSV/hex conversions
 * the picker drives, and - the part that cannot be checked by looking at the
 * app - the migration off the old "White font" boolean.
 *
 * Written in the style of `ColorDeriveTest`: known-answer checks against
 * numbers a published contrast checker would agree with, plus the identities
 * (round-trips, endpoints) the UI silently relies on.
 */
class TextContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // --- contrast math -----------------------------------------------------

    @Test
    fun blackOnWhiteIsTheMaximumRatio() {
        assertEquals(21f, TextContrast.ratio(black, white), 0.01f)
        assertEquals(21f, TextContrast.ratio(white, black), 0.01f)
    }

    @Test
    fun aColourAgainstItselfIsOne() {
        for (c in intArrayOf(white, black, 0xFF7C9CFF.toInt(), 0xFFFF3DDA.toInt())) {
            assertEquals(1f, TextContrast.ratio(c, c), 0.0001f)
        }
    }

    @Test
    fun ratioIsSymmetric() {
        val a = 0xFF2A63FF.toInt()
        val b = 0xFF1A2340.toInt()
        assertEquals(TextContrast.ratio(a, b), TextContrast.ratio(b, a), 0.0001f)
    }

    @Test
    fun knownRatiosMatchAPublishedContrastChecker() {
        // #767676 on white is the canonical "exactly AA" grey (4.54:1), and
        // #949494 is the one just under it (3.98:1). If the linearisation
        // above were wrong, these are the two that would move.
        assertEquals(4.54f, TextContrast.ratio(0xFF767676.toInt(), white), 0.02f)
        assertEquals(3.98f, TextContrast.ratio(0xFF949494.toInt(), white), 0.02f)
    }

    @Test
    fun alphaIsIgnoredSoAStoredColourCannotSmuggleTransparency() {
        assertEquals(
            TextContrast.ratio(white, black),
            TextContrast.ratio(0x00FFFFFF, black),
            0.0001f,
        )
    }

    @Test
    fun worstRatioTakesTheLeastFlatteringBackdrop() {
        val backdrops = intArrayOf(black, 0xFF404040.toInt(), 0xFFCCCCCC.toInt())
        val worst = TextContrast.worstRatio(white, backdrops)
        assertEquals(TextContrast.ratio(white, 0xFFCCCCCC.toInt()), worst, 0.0001f)
    }

    @Test
    fun emptyBackdropsCannotFailTheGate() {
        // Not a real scheme, but the guard keeps a future caller that filters
        // the backdrop list down to nothing from silently refusing everything.
        assertEquals(TextContrast.MAX_RATIO, TextContrast.worstRatio(white, IntArray(0)), 0.0001f)
        assertTrue(TextContrast.isLegible(white, IntArray(0)))
    }

    @Test
    fun blackOnBlackIsNotLegible() {
        assertFalse(TextContrast.isLegible(black, intArrayOf(black)))
        assertFalse(TextContrast.isLegible(0xFF101010.toInt(), intArrayOf(0xFF050A1E.toInt())))
    }

    // --- nudging to legible ------------------------------------------------

    @Test
    fun nearestLegibleLeavesAnAlreadyLegibleColourAlone() {
        val backdrops = intArrayOf(black, 0xFF1A2340.toInt())
        assertEquals(white, TextContrast.nearestLegible(white, backdrops))
    }

    @Test
    fun nearestLegibleLiftsAColourUntilItClearsTheFloor() {
        val backdrops = intArrayOf(0xFF050A1E.toInt(), 0xFF1A2340.toInt())
        val muddy = 0xFF404040.toInt()
        assertFalse(TextContrast.isLegible(muddy, backdrops))
        val fixed = TextContrast.nearestLegible(muddy, backdrops)
        assertNotNull(fixed)
        assertTrue(TextContrast.isLegible(fixed!!, backdrops))
        // It moved toward white, not to white: "nearest" means the user keeps
        // as much of the shade they chose as legibility allows.
        assertTrue("nudged past the endpoint", TextContrast.relativeLuminance(fixed) > TextContrast.relativeLuminance(muddy))
    }

    @Test
    fun nearestLegibleGivesUpWhenNoColourCouldWork() {
        // A backdrop pair that brackets every possible text colour: nothing
        // reads on both pure black and pure white at 3:1.
        assertNull(TextContrast.nearestLegible(0xFF808080.toInt(), intArrayOf(black, white)))
    }

    // --- HSV and hex -------------------------------------------------------

    @Test
    fun hsvRoundTripsThroughRgb() {
        val samples =
            intArrayOf(
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFC107.toInt(),
                0xFFB3E5FC.toInt(),
                0xFF4A148C.toInt(),
                white,
                black,
            )
        for (c in samples) {
            val hsv = TextContrast.argbToHsv(c)
            assertEquals("round trip of ${TextContrast.toHex(c)}", c, TextContrast.hsvToArgb(hsv[0], hsv[1], hsv[2]))
        }
    }

    @Test
    fun greysHaveZeroSaturationAndAnyHueReproducesThem() {
        val grey = 0xFF808080.toInt()
        val hsv = TextContrast.argbToHsv(grey)
        assertEquals(0f, hsv[1], 0.0001f)
        assertEquals(grey, TextContrast.hsvToArgb(0.42f, 0f, hsv[2]))
    }

    @Test
    fun hsvClampsOutOfRangeInputsInsteadOfWrappingToNonsense() {
        assertEquals(white, TextContrast.hsvToArgb(0f, -1f, 4f))
        assertEquals(black, TextContrast.hsvToArgb(0.5f, 0.5f, -2f))
    }

    @Test
    fun hexRoundTrips() {
        for (c in intArrayOf(white, black, 0xFF2A63FF.toInt(), 0xFF0A0B0C.toInt())) {
            assertEquals(c, TextContrast.parseHex(TextContrast.toHex(c)))
        }
    }

    @Test
    fun hexAcceptsTheFormsPeopleActuallyType() {
        assertEquals(0xFF1A2B3C.toInt(), TextContrast.parseHex("#1A2B3C"))
        assertEquals(0xFF1A2B3C.toInt(), TextContrast.parseHex("1a2b3c"))
        assertEquals(0xFF1A2B3C.toInt(), TextContrast.parseHex("  #1a2B3c "))
        assertEquals(0xFFAABBCC.toInt(), TextContrast.parseHex("#abc"))
    }

    @Test
    fun hexRejectsHalfTypedAndInvalidInput() {
        // Null is the normal state mid-typing, so it has to be a rejection and
        // not a guess - the field keeps the last good colour on null.
        for (bad in listOf("", "#", "1", "12", "12345", "1234567", "gggggg", "#12 34 56")) {
            assertNull("accepted \"$bad\"", TextContrast.parseHex(bad))
        }
    }

    @Test
    fun toHexIsSixUpperCaseDigitsAndDropsAlpha() {
        assertEquals("2A63FF", TextContrast.toHex(0xFF2A63FF.toInt()))
        assertEquals("2A63FF", TextContrast.toHex(0x112A63FF))
        assertEquals("000000", TextContrast.toHex(black))
    }

    // --- persistence and migration -----------------------------------------

    @Test
    fun anExistingWhiteFontUserStillGetsWhite() {
        // The promise. No new key on disk, legacy boolean on -> white.
        assertEquals(TextColorPref.WHITE, TextColorPref.decode(stored = null, legacyWhiteFont = true))
    }

    @Test
    fun anExistingUserWithoutWhiteFontGetsTheThemeDefault() {
        assertNull(TextColorPref.decode(stored = null, legacyWhiteFont = false))
    }

    @Test
    fun aFreshInstallGetsTheThemeDefault() {
        // No key, no legacy flag - byte-identical to the app before this
        // option existed, which is the other half of the promise.
        assertNull(TextColorPref.decode(stored = null, legacyWhiteFont = false))
        assertNull(TextColorPref.decode(stored = TextColorPref.NONE, legacyWhiteFont = false))
    }

    @Test
    fun theNewKeyWinsOnceItExistsSoTheLegacyFlagCannotResurrectWhite() {
        // Someone who had white font on and then turned the option off: the
        // legacy boolean is stale until the next save, and must not be read.
        assertNull(TextColorPref.decode(stored = TextColorPref.NONE, legacyWhiteFont = true))
        assertEquals(
            0xFFFFC107.toInt(),
            TextColorPref.decode(stored = 0xFFFFC107.toInt(), legacyWhiteFont = true),
        )
    }

    @Test
    fun persistenceRoundTripsEveryColourIncludingTheSentinelLookalikes() {
        val samples = listOf(null, TextColorPref.WHITE, 0xFF000000.toInt(), 0xFF2A63FF.toInt(), 0xFFFFC107.toInt())
        for (c in samples) {
            val stored = TextColorPref.encode(c)
            assertEquals("round trip of $c", c, TextColorPref.decode(stored, legacyWhiteFont = false))
        }
    }

    @Test
    fun opaqueBlackSurvivesTheNullSentinel() {
        // The one collision worth naming: NONE is 0, and opaque black is
        // 0xFF000000, so black must not decode as "no colour chosen".
        assertEquals(TextColorPref.NONE, 0)
        assertEquals(black, TextColorPref.decode(TextColorPref.encode(black), legacyWhiteFont = false))
    }

    @Test
    fun storedColoursAreForcedOpaqueOnBothSides() {
        assertEquals(TextColorPref.WHITE, TextColorPref.encode(0x00FFFFFF))
        assertEquals(TextColorPref.WHITE, TextColorPref.decode(0x00FFFFFF, legacyWhiteFont = false))
    }

    @Test
    fun theLegacyFlagIsWrittenBackOnlyForWhiteSoADowngradeIsStillHonest() {
        assertTrue(TextColorPref.legacyWhiteFont(TextColorPref.WHITE))
        assertFalse(TextColorPref.legacyWhiteFont(null))
        assertFalse(TextColorPref.legacyWhiteFont(0xFFFFC107.toInt()))
    }
}
