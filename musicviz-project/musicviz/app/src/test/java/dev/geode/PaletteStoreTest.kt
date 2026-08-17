package dev.geode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.geode.data.PaletteStore
import dev.geode.render.scene.SceneParams
import dev.geode.ui.paletteChipIndex
import dev.geode.ui.paletteChipSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the user-made palette feature, and specifically three ways it can go
 * wrong:
 *
 *  1. Clearing an override by writing 0f instead of [SceneParams.UNSET_OVERRIDE].
 *     0f is a legitimate base hue (red) and stays ACTIVE (>= 0f), so a 0f
 *     "clear" would pin every scene to red while the UI showed a built-in.
 *  2. The mirror bug: a saved palette whose base hue is 0f must still register
 *     as a custom palette, not silently fall back to the built-in table.
 *  3. Deleting a palette that a scene is using must not leave a dangling
 *     `customPaletteId`; the resolved hues stay, the id goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaletteStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: PaletteStore

    @Before
    fun setUp() {
        store = PaletteStore(ctx)
        store.list().forEach { store.delete(it.id) }
    }

    @Test
    fun savedPaletteRoundtripsThroughDisk() {
        val stored = store.save(PaletteStore.create("Sunrise Fade", 0.08f, 0.42f))
        val back = PaletteStore(ctx).list()
        assertEquals(1, back.size)
        assertEquals("Sunrise Fade", back[0].name)
        assertEquals(stored.id, back[0].id)
        assertEquals(0.08f, back[0].baseHue, 1e-4f)
        assertEquals(0.42f, back[0].hueSpan, 1e-4f)
        assertNotNull(store.get(stored.id))
    }

    @Test
    fun savingTheSameNameReplacesInsteadOfDuplicating() {
        store.save(PaletteStore.create("Dusk", 0.1f, 0.2f))
        store.save(PaletteStore.create("Dusk", 0.6f, 0.3f))
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals(0.6f, all[0].baseHue, 1e-4f)
    }

    @Test
    fun outOfRangeInputIsClampedSoOverridesStayActive() {
        val clamped = PaletteStore.create("Wild", -0.5f, 9f)
        assertEquals(0f, clamped.baseHue, 1e-6f)
        assertEquals(1f, clamped.hueSpan, 1e-6f)
        assertTrue(PaletteStore.applyPalette(SceneParams.DEFAULT, clamped).usesCustomPalette)
    }

    @Test
    fun applyingASavedPaletteOverridesBothSlotsIndependently() {
        val a = PaletteStore.create("A", 0.2f, 0.3f)
        val b = PaletteStore.create("B", 0.7f, 0.9f)
        val p = PaletteStore.applyPalette(PaletteStore.applyPalette(SceneParams.DEFAULT, a), b, second = true)
        assertTrue(p.usesCustomPalette)
        assertTrue(p.usesCustomPalette2)
        assertEquals(0.2f, p.paletteBase, 1e-4f)
        assertEquals(0.3f, p.paletteRange, 1e-4f)
        assertEquals(0.7f, p.palette2Base, 1e-4f)
        assertEquals(0.9f, p.palette2Range, 1e-4f)
        assertEquals(a.id, p.customPaletteId)
        assertEquals(b.id, p.customPalette2Id)
    }

    @Test
    fun clearingWritesTheUnsetSentinelNeverZero() {
        val p = PaletteStore.applyPalette(SceneParams.DEFAULT, PaletteStore.create("Red-ish", 0f, 0.1f))
        val cleared = PaletteStore.clear(p.copy(palette = 2))
        assertFalse(cleared.usesCustomPalette)
        assertTrue("base override must be negative", cleared.paletteBaseOverride < 0f)
        assertTrue("range override must be negative", cleared.paletteRangeOverride < 0f)
        assertEquals(SceneParams.UNSET_OVERRIDE, cleared.paletteBaseOverride, 1e-6f)
        assertEquals(SceneParams.NO_CUSTOM_PALETTE, cleared.customPaletteId)
        // Falls back to the built-in table (index 2 = Fire).
        assertEquals(SceneParams.PALETTES[2].second, cleared.paletteBase, 1e-6f)
        assertEquals(SceneParams.PALETTES[2].third, cleared.paletteRange, 1e-6f)
    }

    @Test
    fun clearingOneSlotLeavesTheOtherAlone() {
        val custom = PaletteStore.create("Both", 0.4f, 0.5f)
        val p = PaletteStore.applyPalette(PaletteStore.applyPalette(SceneParams.DEFAULT, custom), custom, second = true)
        val cleared = PaletteStore.clear(p, second = true)
        assertTrue(cleared.usesCustomPalette)
        assertFalse(cleared.usesCustomPalette2)
    }

    @Test
    fun aZeroBaseHueStillCountsAsACustomPalette() {
        val p = PaletteStore.applyPalette(SceneParams.DEFAULT, PaletteStore.create("Pure red", 0f, 0f))
        assertTrue(p.usesCustomPalette)
        assertEquals(0f, p.paletteBase, 1e-6f)
        assertEquals(0f, p.paletteRange, 1e-6f)
    }

    @Test
    fun deletingAPaletteInUseKeepsTheHuesAndDropsTheDanglingId() {
        val saved = store.save(PaletteStore.create("Doomed", 0.33f, 0.66f))
        val p = PaletteStore.applyPalette(PaletteStore.applyPalette(SceneParams.DEFAULT, saved), saved, second = true)
        store.delete(saved.id)
        val repaired = PaletteStore.forgetDeleted(p, saved.id)
        assertTrue(store.list().isEmpty())
        assertEquals(SceneParams.NO_CUSTOM_PALETTE, repaired.customPaletteId)
        assertEquals(SceneParams.NO_CUSTOM_PALETTE, repaired.customPalette2Id)
        // The look on screen must not jump just because the library was tidied.
        assertEquals(0.33f, repaired.paletteBase, 1e-4f)
        assertEquals(0.66f, repaired.paletteRange, 1e-4f)
        assertTrue(repaired.usesCustomPalette)
        assertTrue(repaired.usesCustomPalette2)
    }

    @Test
    fun forgettingAnUnrelatedIdChangesNothing() {
        val saved = PaletteStore.create("Keeper", 0.5f, 0.5f)
        val p = PaletteStore.applyPalette(SceneParams.DEFAULT, saved)
        assertEquals(p, PaletteStore.forgetDeleted(p, "some-other-id"))
        assertEquals(p, PaletteStore.forgetDeleted(p, SceneParams.NO_CUSTOM_PALETTE))
    }

    @Test
    fun chipIndexTracksBuiltInsSavedPalettesAndDanglingIds() {
        val saved = listOf(PaletteStore.create("One", 0.1f, 0.1f), PaletteStore.create("Two", 0.2f, 0.2f))
        val builtIns = SceneParams.PALETTES.size
        val builtIn = SceneParams.DEFAULT.copy(palette = 3)
        assertEquals(3, paletteChipIndex(builtIn, saved, second = false))
        val custom = PaletteStore.applyPalette(SceneParams.DEFAULT, saved[1])
        assertEquals(builtIns + 1, paletteChipIndex(custom, saved, second = false))
        // Deleted underneath us: nothing is highlighted rather than the wrong chip.
        val dangling = PaletteStore.forgetDeleted(custom, saved[1].id)
        assertEquals(-1, paletteChipIndex(dangling, saved, second = false))
    }

    @Test
    fun tappingABuiltInChipAfterACustomPaletteReallyRestoresTheBuiltIn() {
        val saved = listOf(PaletteStore.create("Loud", 0.75f, 0.9f))
        val custom = PaletteStore.applyPalette(SceneParams.DEFAULT, saved[0])
        // Chip 2 = Fire. Without clearing the override the scene would stay
        // purple while the Fire chip looked selected.
        val backToFire = paletteChipSelected(custom, saved, index = 2, second = false)
        assertFalse(backToFire.usesCustomPalette)
        assertEquals(2, backToFire.palette)
        assertEquals(SceneParams.PALETTES[2].second, backToFire.paletteBase, 1e-6f)
        assertEquals(2, paletteChipIndex(backToFire, saved, second = false))
        // ...and the custom chip round-trips back on.
        val again = paletteChipSelected(backToFire, saved, index = SceneParams.PALETTES.size, second = false)
        assertTrue(again.usesCustomPalette)
        assertEquals(saved[0].id, again.customPaletteId)
        assertEquals(0.75f, again.paletteBase, 1e-4f)
    }

    @Test
    fun chipSelectionOnTheSecondSlotLeavesTheFirstAlone() {
        val saved = listOf(PaletteStore.create("Loud", 0.75f, 0.9f))
        val custom = PaletteStore.applyPalette(SceneParams.DEFAULT, saved[0])
        val bothCustom = paletteChipSelected(custom, saved, index = SceneParams.PALETTES.size, second = true)
        assertTrue(bothCustom.usesCustomPalette)
        assertTrue(bothCustom.usesCustomPalette2)
        val cleared = paletteChipSelected(bothCustom, saved, index = 5, second = true)
        assertTrue(cleared.usesCustomPalette)
        assertFalse(cleared.usesCustomPalette2)
        assertEquals(5, cleared.palette2)
    }

    @Test
    fun sampleHueWrapsAroundTheWheel() {
        assertEquals(0.9f, PaletteStore.sampleHue(0.9f, 0.4f, 0, stops = 5), 1e-4f)
        assertEquals(0.3f, PaletteStore.sampleHue(0.9f, 0.4f, 4, stops = 5), 1e-4f)
        for (i in 0 until PaletteStore.PREVIEW_STOPS) {
            val h = PaletteStore.sampleHue(0.8f, 1f, i)
            assertTrue("hue $h out of range", h >= 0f && h < 1f)
        }
    }
}
