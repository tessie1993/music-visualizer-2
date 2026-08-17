package dev.geode

import dev.geode.analysis.KeyPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for key-driven colour.
 *
 * The point of the feature is not "keys have colours" - any mapping does that
 * - it is that MUSICALLY related keys get RELATED colours, so a set that
 * modulates by a fifth drifts through the spectrum instead of jumping across
 * it. That property is what these tests pin, rather than individual hue
 * numbers, which are free to be retuned.
 */
class KeyPaletteTest {
    private fun hue(key: String) = KeyPalette.hueFor(key) ?: error("no hue for $key")

    @Test
    fun everyKeyTheDetectorCanEmitGetsAHue() {
        val roots = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        for (root in roots) {
            for (mode in listOf("major", "minor")) {
                val h = KeyPalette.hueFor("$root $mode")
                assertTrue("no hue for $root $mode", h != null && h >= 0f && h < 1f)
            }
        }
    }

    @Test
    fun keysAFifthApartAreNeighboursOnTheWheel() {
        // The whole design: C -> G -> D is one step at a time in music, so it
        // has to be one step at a time in colour.
        val fifths = listOf("C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#", "F")
        val step = 1f / fifths.size
        for (i in fifths.indices) {
            val a = fifths[i]
            val b = fifths[(i + 1) % fifths.size]
            assertTrue("$a and $b should be circle-of-fifths neighbours", KeyPalette.areNeighbours(a, b))
            assertEquals(
                "$a -> $b is not one step of hue",
                step,
                KeyPalette.hueDistance(hue("$a major"), hue("$b major")),
                1e-4f,
            )
        }
    }

    @Test
    fun aSemitoneApartIsFarApartInColour() {
        // The failure a chromatic mapping would produce: C and C# share almost
        // no notes, so they must not read as the same colour family.
        val step = 1f / 12f
        for (pair in listOf("C" to "C#", "F" to "F#", "A" to "A#")) {
            val d = KeyPalette.hueDistance(hue("${pair.first} major"), hue("${pair.second} major"))
            assertTrue("${pair.first}/${pair.second} are only $d apart", d > step * 2)
        }
    }

    @Test
    fun aMinorKeySitsBesideItsRelativeMajorWithoutSharingItsColour() {
        // A minor and C major are the same seven notes - interchangeable in a
        // mix - so they must be the same colour family. Placing a minor key at
        // its own root instead would put A minor three steps from C major, as
        // far away as keys that genuinely clash.
        for ((minor, major) in listOf("A minor" to "C major", "E minor" to "G major", "D minor" to "F major")) {
            val d = KeyPalette.hueDistance(hue(minor), hue(major))
            assertTrue("$minor and $major are indistinguishable", d > 0f)
            assertTrue("$minor is too far from $major ($d)", d < 1f / 12f / 2f)
        }
    }

    @Test
    fun aMinorKeyIsNotPlacedAtItsOwnRoot() {
        // The bug this pins: reading "A minor" as "the A position". It is the
        // C position, because those are the same notes.
        assertTrue(
            "A minor was placed where A major is",
            KeyPalette.hueDistance(hue("A minor"), hue("A major")) > 1f / 12f,
        )
    }

    @Test
    fun flatSpellingsResolveToTheSameHueAsTheirSharpTwin() {
        assertEquals(hue("A# major"), hue("Bb major"), 1e-6f)
        assertEquals(hue("C# minor"), hue("Db minor"), 1e-6f)
        assertEquals(hue("F# major"), hue("Gb major"), 1e-6f)
    }

    @Test
    fun anUnknownKeyIsNothingRatherThanADefaultColour() {
        // "Not analysed" and "in C" are different facts; colouring the first
        // as the second would invent information the analysis never produced.
        assertNull(KeyPalette.hueFor(""))
        assertNull(KeyPalette.hueFor("   "))
        assertNull(KeyPalette.hueFor("H minor"))
        assertNull(KeyPalette.hueFor("unknown"))
    }

    @Test
    fun theTwelveKeysAreAllDistinguishable() {
        val hues = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B").map { hue("$it major") }
        for (i in hues.indices) {
            for (j in i + 1 until hues.size) {
                assertNotEquals("two keys share a hue", hues[i], hues[j])
            }
        }
    }
}
