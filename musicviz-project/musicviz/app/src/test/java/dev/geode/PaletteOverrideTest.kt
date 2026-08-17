package dev.geode

import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless gate for the custom-palette hook every scene family reads through
 * paletteBase/paletteRange. Two bugs it guards: (1) a user-made palette must
 * beat the built-in PALETTES lookup, per slot and per component, so the
 * palette/gradient maker actually changes what is drawn; (2) with no override
 * set the lookup must be byte-for-byte what it was before the hook existed -
 * a stale sentinel must never leak a wrong hue into an untouched preset.
 * Also pins the PALETTES table to append-only growth, because presets persist
 * `palette`/`palette2` as indices into it: inserting or reordering an entry
 * silently repaints every preset a user has already saved.
 */
class PaletteOverrideTest {
    private fun builtIn(index: Int) = SceneParams.PALETTES[index]

    @Test
    fun withoutOverridesTheBuiltInTableIsUnchanged() {
        for (i in SceneParams.PALETTES.indices) {
            val p = SceneParams(palette = i, palette2 = (i + 1) % SceneParams.PALETTES.size)
            assertEquals(builtIn(p.palette).second, p.paletteBase, 0f)
            assertEquals(builtIn(p.palette).third, p.paletteRange, 0f)
            assertEquals(builtIn(p.palette2).second, p.palette2Base, 0f)
            assertEquals(builtIn(p.palette2).third, p.palette2Range, 0f)
            assertFalse(p.usesCustomPalette)
            assertFalse(p.usesCustomPalette2)
        }
    }

    @Test
    fun outOfRangePaletteIndicesStillClampIntoTheTable() {
        val low = SceneParams(palette = -7, palette2 = -1)
        assertEquals(builtIn(0).second, low.paletteBase, 0f)
        assertEquals(builtIn(0).third, low.palette2Range, 0f)
        val high = SceneParams(palette = 9999, palette2 = 9999)
        val last = SceneParams.PALETTES.size - 1
        assertEquals(builtIn(last).second, high.paletteBase, 0f)
        assertEquals(builtIn(last).third, high.palette2Range, 0f)
    }

    @Test
    fun activeOverrideWinsOverTheBuiltInEntry() {
        val p =
            SceneParams(
                palette = 2,
                palette2 = 3,
                paletteBaseOverride = 0.42f,
                paletteRangeOverride = 0.77f,
                palette2BaseOverride = 0.11f,
                palette2RangeOverride = 0.05f,
            )
        assertEquals(0.42f, p.paletteBase, 0f)
        assertEquals(0.77f, p.paletteRange, 0f)
        assertEquals(0.11f, p.palette2Base, 0f)
        assertEquals(0.05f, p.palette2Range, 0f)
        assertTrue(p.usesCustomPalette)
        assertTrue(p.usesCustomPalette2)
    }

    @Test
    fun overridesAreIndependentPerSlotAndPerComponent() {
        // Slot 1 base only: slot 1 range and all of slot 2 stay built-in.
        val p = SceneParams(palette = 4, palette2 = 5, paletteBaseOverride = 0.9f)
        assertEquals(0.9f, p.paletteBase, 0f)
        assertEquals(builtIn(4).third, p.paletteRange, 0f)
        assertEquals(builtIn(5).second, p.palette2Base, 0f)
        assertEquals(builtIn(5).third, p.palette2Range, 0f)
        assertTrue(p.usesCustomPalette)
        assertFalse(p.usesCustomPalette2)

        // Slot 2 range only, mirrored.
        val q = SceneParams(palette = 4, palette2 = 5, palette2RangeOverride = 0.02f)
        assertEquals(builtIn(4).second, q.paletteBase, 0f)
        assertEquals(builtIn(5).second, q.palette2Base, 0f)
        assertEquals(0.02f, q.palette2Range, 0f)
        assertFalse(q.usesCustomPalette)
        assertTrue(q.usesCustomPalette2)
    }

    @Test
    fun zeroIsARealHueAndOnlyNegativesCountAsUnset() {
        // 0f = red, a legitimate override that must NOT read as "unset".
        val red = SceneParams(palette = 1, paletteBaseOverride = 0f, paletteRangeOverride = 0f)
        assertEquals(0f, red.paletteBase, 0f)
        assertEquals(0f, red.paletteRange, 0f)
        assertTrue(red.usesCustomPalette)

        // Any negative clears the override, not just the canonical sentinel.
        val cleared =
            SceneParams(
                palette = 1,
                paletteBaseOverride = SceneParams.UNSET_OVERRIDE,
                paletteRangeOverride = -0.863f,
            )
        assertEquals(builtIn(1).second, cleared.paletteBase, 0f)
        assertEquals(builtIn(1).third, cleared.paletteRange, 0f)
        assertFalse(cleared.usesCustomPalette)
    }

    @Test
    fun defaultsCarryNoOverrideAndNoCustomPaletteId() {
        val d = SceneParams.DEFAULT
        assertTrue(d.paletteBaseOverride < 0f)
        assertTrue(d.paletteRangeOverride < 0f)
        assertTrue(d.palette2BaseOverride < 0f)
        assertTrue(d.palette2RangeOverride < 0f)
        assertEquals(SceneParams.NO_CUSTOM_PALETTE, d.customPaletteId)
        assertEquals(SceneParams.NO_CUSTOM_PALETTE, d.customPalette2Id)
        assertFalse(d.usesCustomPalette)
        assertFalse(d.usesCustomPalette2)
    }

    @Test
    fun paletteTableGrowsAppendOnly() {
        // Saved presets store indices into PALETTES: these prefix entries are
        // load-bearing and must keep their exact position, hue and span.
        val expectedPrefix =
            listOf(
                Triple("Spectrum", 0.0f, 1.0f),
                Triple("Neon", 0.5f, 0.45f),
                Triple("Fire", 0.0f, 0.14f),
                Triple("Ocean", 0.5f, 0.2f),
                Triple("Mono", 0.6f, 0.02f),
                Triple("Candy", 0.85f, 0.5f),
                Triple("Forest", 0.33f, 0.18f),
                Triple("Aurora", 0.45f, 0.7f),
                Triple("Sunset", 0.05f, 0.3f),
                Triple("Ice", 0.55f, 0.15f),
                Triple("Vapor", 0.78f, 0.35f),
                Triple("Toxic", 0.25f, 0.25f),
                Triple("Royal", 0.7f, 0.25f),
                Triple("Blush", 0.93f, 0.12f),
                Triple("Copper", 0.07f, 0.1f),
                Triple("Mint", 0.4f, 0.12f),
                Triple("Galaxy", 0.65f, 0.5f),
                Triple("Cherry", 0.97f, 0.08f),
            )
        val table = SceneParams.PALETTES
        assertTrue("PALETTES must not shrink", table.size >= expectedPrefix.size)
        assertEquals(expectedPrefix, table.take(expectedPrefix.size))
        // The new colours the user asked for, appended after the legacy block.
        val added = table.drop(expectedPrefix.size)
        assertEquals(listOf("Cyan", "Magenta", "Yellow"), added.take(3).map { it.first })
        assertEquals(0.5f, added[0].second, 1e-3f)
        assertEquals(0.833f, added[1].second, 1e-3f)
        assertEquals(0.167f, added[2].second, 1e-3f)
        // Every entry must be a usable hue/span pair.
        for ((name, base, span) in table) {
            assertTrue("$name base hue out of 0..1", base >= 0f && base <= 1f)
            assertTrue("$name span out of 0..1", span > 0f && span <= 1f)
        }
    }
}
