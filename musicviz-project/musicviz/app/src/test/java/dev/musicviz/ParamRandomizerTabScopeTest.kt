package dev.musicviz

import dev.musicviz.data.PresetStore
import dev.musicviz.render.scene.CustomizeTab
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Guards the two properties "Randomize <tab>" promises.
 *
 * SCOPE. The button lives inside a Customize tab, so a press must roll that
 * tab and nothing else. It used to roll every parameter in the app, which
 * meant opening Color and pressing it threw away the motion, shape and FX the
 * user had just dialled in - with no undo. [a_roll_is_exactly_that_tab] pins
 * the semantics ("scoped to T" == "everything but T locked"), and
 * [tabs_do_not_share_parameters] proves the tabs partition the parameters, so
 * no key can be rolled from two places.
 *
 * PLACEMENT. A key's tab has to be the tab whose panel actually renders that
 * control, or "only this tab" is a lie in a different way -
 * [every_key_is_rolled_by_the_tab_that_renders_it] reads the labels straight
 * out of each `CustomizeTabs.kt` composable ([ParamSurface]), the same way
 * `ParamRandomizerFluidTest` reads them for the lock keys.
 *
 * RANDOMNESS. [a_roll_is_actually_random] and [a_seeded_roll_is_reproducible]
 * pin that presses differ from each other while a seeded roll stays a pure
 * function of its seed (which is what makes the rest of these tests possible).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParamRandomizerTabScopeTest {
    private val base = SceneParams.DEFAULT

    @Test
    fun every_tab_rolls_something() {
        for (tab in CustomizeTab.entries) {
            assertTrue("${tab.title} rolls no parameter at all", ParamRandomizer.keysFor(tab).isNotEmpty())
            val rolled = ParamRandomizer.randomize(base, emptySet(), Random(3), tab)
            assertNotEquals("a roll on ${tab.title} changed nothing", base, rolled)
        }
    }

    @Test
    fun no_press_is_ever_a_no_op() {
        // Most FX and shape params are off by default and roll through
        // `sometimes`, so a whole tab can draw "leave it alone" at once - on
        // FX that was about one press in eleven doing nothing at all, which
        // reads as a dead button now that a roll is scoped to one tab.
        for (tab in CustomizeTab.entries) {
            repeat(200) {
                assertNotEquals(
                    "a press on ${tab.title} changed nothing",
                    base,
                    ParamRandomizer.randomize(base, emptySet(), tab = tab),
                )
            }
        }
    }

    @Test
    fun a_fully_locked_tab_is_left_alone() {
        // The one case where doing nothing is right - and it must not spin.
        for (tab in CustomizeTab.entries) {
            val locked = ParamRandomizer.keysFor(tab).toSet()
            assertEquals(base, ParamRandomizer.randomize(base, locked, Random(2), tab))
        }
    }

    @Test
    fun the_published_keys_are_exactly_the_tabs_keys() {
        assertEquals(
            ParamRandomizer.KEYS.sorted(),
            CustomizeTab.entries.flatMap { ParamRandomizer.keysFor(it) }.sorted(),
        )
    }

    @Test
    fun a_roll_is_exactly_that_tab() {
        // "Scoped to T" and "everything outside T locked" are the same roll:
        // both skip the same keys, in the same order, so with one seed they
        // must produce the identical SceneParams. Locks are already proven to
        // hold (ParamRandomizerFluidTest), so this carries the scoping proof:
        // nothing outside the tab can have moved.
        val everything = ParamRandomizer.KEYS.toSet()
        for (tab in CustomizeTab.entries) {
            val scoped = ParamRandomizer.randomize(base, emptySet(), Random(17), tab)
            val lockedOut = ParamRandomizer.randomize(base, everything - ParamRandomizer.keysFor(tab).toSet(), Random(17))
            assertEquals("a roll on ${tab.title} reached outside the tab", lockedOut, scoped)
        }
    }

    @Test
    fun tabs_do_not_share_parameters() {
        // Field-level, not key-level: two keys in different tabs writing the
        // same SceneParams field would make one tab's roll change another
        // tab's sliders even though the key lists look disjoint.
        val touched = CustomizeTab.entries.associateWith { tab -> fieldsTouchedBy(tab) }
        for (tab in CustomizeTab.entries) {
            for (other in CustomizeTab.entries) {
                if (other <= tab) continue
                val shared = touched.getValue(tab) intersect touched.getValue(other)
                assertEquals("${tab.title} and ${other.title} roll the same parameters", emptySet<String>(), shared)
            }
        }
    }

    @Test
    fun every_key_is_rolled_by_the_tab_that_renders_it() {
        val misplaced = mutableListOf<String>()
        for (tab in CustomizeTab.entries) {
            val labels = ParamSurface.lockableLabels(ParamSurface.tabBodies.getValue(tab))
            ParamRandomizer.keysFor(tab).filterNot { it in labels }.forEach {
                misplaced += "\"$it\" rolls with ${tab.title} but is not rendered there"
            }
        }
        assertEquals(emptyList<String>(), misplaced)
    }

    @Test
    fun a_roll_is_actually_random() {
        // Two presses of the same button must not give the same look, and the
        // draw must be a spread rather than a handful of values.
        val rolls = List(200) { ParamRandomizer.randomize(base, emptySet(), tab = CustomizeTab.MOTION) }
        assertTrue("consecutive rolls repeat", rolls.zipWithNext().none { (a, b) -> a == b })
        assertTrue("Speed barely varies across 200 rolls", rolls.map { it.speed }.distinct().size > 100)
        assertTrue("Zoom barely varies across 200 rolls", rolls.map { it.zoom }.distinct().size > 100)
    }

    @Test
    fun a_seeded_roll_is_reproducible() {
        assertEquals(
            ParamRandomizer.randomize(base, emptySet(), Random(5), CustomizeTab.COLOR),
            ParamRandomizer.randomize(base, emptySet(), Random(5), CustomizeTab.COLOR),
        )
    }

    /** Every SceneParams field [tab] can move, named as the preset JSON names it. */
    private fun fieldsTouchedBy(tab: CustomizeTab): Set<String> {
        val defaults = PresetStore.paramsToJson(base)
        val rng = Random(101)
        return buildSet {
            repeat(400) {
                val rolled = PresetStore.paramsToJson(ParamRandomizer.randomize(base, emptySet(), rng, tab))
                for (key in rolled.keys()) {
                    if (rolled.opt(key) != defaults.opt(key)) add(key)
                }
            }
        }
    }
}
