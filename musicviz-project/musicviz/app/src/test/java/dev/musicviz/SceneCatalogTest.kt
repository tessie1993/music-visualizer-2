package dev.musicviz

import dev.musicviz.render.scene.SceneCatalog
import dev.musicviz.render.scene.SceneIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the regression that motivated [SceneCatalog]: the random-mode style
 * pool was written out by hand as "particle + shader" and so could never pick
 * Fluid, Curl Flow or Water, while the Styles tab and the renderer both
 * offered them. Three copies of one list, and one of them had drifted.
 *
 * Headless: the catalog takes the particle/shader id lists as arguments (they
 * still live on VisualizerRenderer with their shader resource ids), so nothing
 * here needs a GL context.
 */
class SceneCatalogTest {
    private val particle = listOf("nebula", "bursts")
    private val shader = listOf("julia", "tunnel")

    @Test
    fun random_pool_is_the_selectable_styles_minus_milkdrop() {
        // The invariant that was broken: anything the user can pick by hand,
        // random mode can pick too — except MilkDrop, which needs a .milk file
        // and has its own toggle.
        assertEquals(
            SceneCatalog.selectableStyles(particle, shader, milkdropAvailable = true) - SceneIds.MILKDROP,
            SceneCatalog.randomStyles(particle, shader),
        )
    }

    @Test
    fun the_fluid_family_is_both_selectable_and_randomisable() {
        val selectable = SceneCatalog.selectableStyles(particle, shader, milkdropAvailable = true)
        val random = SceneCatalog.randomStyles(particle, shader)
        for (id in listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER)) {
            assertTrue("$id must be selectable", id in selectable)
            assertTrue("$id must be reachable from random mode", id in random)
        }
    }

    @Test
    fun milkdrop_appears_only_when_available_and_never_at_random() {
        assertTrue(SceneIds.MILKDROP in SceneCatalog.selectableStyles(particle, shader, milkdropAvailable = true))
        assertFalse(SceneIds.MILKDROP in SceneCatalog.selectableStyles(particle, shader, milkdropAvailable = false))
        assertFalse(SceneIds.MILKDROP in SceneCatalog.randomStyles(particle, shader))
    }

    @Test
    fun styles_tab_order_is_preserved() {
        // Particles, then shaders, then MilkDrop, then the fluid family - the
        // order the Styles tab renders its sections in.
        assertEquals(
            particle + shader + listOf(SceneIds.MILKDROP) + SceneCatalog.FLUID_FAMILY,
            SceneCatalog.selectableStyles(particle, shader, milkdropAvailable = true),
        )
    }

    @Test
    fun the_fluid_family_is_exactly_the_three_simulation_styles() {
        assertEquals(listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER), SceneCatalog.FLUID_FAMILY)
    }
}
