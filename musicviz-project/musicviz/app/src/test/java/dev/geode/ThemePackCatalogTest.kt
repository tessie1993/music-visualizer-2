package dev.geode

import dev.geode.ui.theme.StoneComponent
import dev.geode.ui.theme.StoneState
import dev.geode.ui.theme.ThemePackCatalog
import dev.geode.ui.theme.stoneStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaging contract `ThemePack.surface` relies on.
 *
 * That function is a `requireNotNull`: a pack missing a component family
 * crashes the screen that paints it. It is written that way deliberately -
 * "every pack ships all 18 families, and the catalog is covered by a test
 * that proves it, so a miss here is a packaging bug rather than something a
 * caller should paper over". This is that test; without it the importer could
 * emit a short pack and nothing would notice until a device did.
 */
class ThemePackCatalogTest {
    private val packs = ThemePackCatalog.all

    @Test
    fun everyPackShipsAllEighteenComponentFamilies() {
        for (pack in packs) {
            assertEquals(
                "${pack.slug} surface families",
                StoneComponent.entries.toSet(),
                pack.surfaces.keys,
            )
            // ... and every family resolves in all five states, which is what
            // a control actually asks for as it is interacted with.
            for (component in StoneComponent.entries) {
                val art = pack.surface(component)
                for (state in StoneState.entries) {
                    assertNotEquals("${pack.slug} $component $state", 0, art.forState(state))
                }
            }
        }
    }

    @Test
    fun slugsAreUniqueAndResolveBackToTheirPack() {
        assertTrue(packs.isNotEmpty())
        assertEquals(packs.size, packs.map { it.slug }.toSet().size)
        for (pack in packs) {
            assertEquals(pack, ThemePackCatalog.bySlug(pack.slug))
        }
    }

    @Test
    fun anUnknownOrAbsentSlugFallsBackToTheDefaultPack() {
        // ThemeStore migrates the two legacy enum names it can and passes
        // everything else straight through, so the fallback is what a user
        // upgrading from a retired theme actually lands on.
        assertEquals(packs.first(), ThemePackCatalog.bySlug(null))
        assertEquals(packs.first(), ThemePackCatalog.bySlug("lapis"))
        assertEquals(packs.first(), ThemePackCatalog.bySlug(""))
    }

    @Test
    fun stoneStateResolvesInThePacksPrecedenceOrder() {
        // Unusable beats everything, a press beats a standing selection, and
        // focus is the weakest signal.
        assertEquals(StoneState.DISABLED, stoneStateOf(enabled = false, pressed = true, selected = true))
        assertEquals(StoneState.PRESSED, stoneStateOf(pressed = true, selected = true, focused = true))
        assertEquals(StoneState.SELECTED, stoneStateOf(selected = true, focused = true))
        assertEquals(StoneState.FOCUSED, stoneStateOf(focused = true))
        assertEquals(StoneState.DEFAULT, stoneStateOf())
    }

    @Test
    fun everyPackAuthorsTheMotionTheContractRequires() {
        // The pack contract states the press response as a scale-down over
        // pressDurationMs and a settle over releaseDurationMs, and requires a
        // reduced-motion crossfade. A zero here means the importer read a
        // token it did not find and the control would snap.
        for (pack in packs) {
            val m = pack.motion
            assertTrue("${pack.slug} pressScale ${m.pressScale}", m.pressScale in 0.9f..1f)
            assertTrue("${pack.slug} pressDurationMs", m.pressDurationMs > 0)
            assertTrue("${pack.slug} releaseDurationMs", m.releaseDurationMs > 0)
            assertTrue("${pack.slug} focusDurationMs", m.focusDurationMs > 0)
            assertTrue("${pack.slug} selectedDurationMs", m.selectedDurationMs > 0)
            assertTrue("${pack.slug} reduceMotionCrossfadeMs", m.reduceMotionCrossfadeMs > 0)
        }
    }
}
