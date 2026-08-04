package dev.musicviz

import dev.musicviz.render.BlendMode
import dev.musicviz.render.scene.CustomizeTab
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.ui.LayersBus
import dev.musicviz.ui.LayersUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Layers feature's UI wiring (B2b). The render side has been complete for
 * a while - `VisualizerRenderer.layerSceneId`/`layerMix`/`layerBlend` and the
 * composite branch that blends them - but nothing wrote those fields, so the
 * whole feature was invisible. The writers are the FX tab's Layers section and
 * the shell-level engine bindings, joined by [LayersBus] because the layer
 * fields are deliberately renderer state rather than `SceneParams` (they say
 * which scenes are on screen, not how one scene looks) and so have no
 * ViewModel flow to ride.
 *
 * The label-set and expression tests read the wiring back out of the sources,
 * the technique [FluidTabGatingTest] / `ParamSurface` use: a binding that
 * stops writing one of the three fields, or a slider whose range drifts from
 * what `VisualSafety.layerMix` accepts, fails the build instead of shipping as
 * a dead control.
 */
class LayersCustomizeTest {
    @After
    fun resetBus() {
        // LayersBus is process-wide state; leave it as other tests expect it.
        LayersBus.state.value = LayersUiState()
        LayersBus.availableScenes.value = emptyList()
        LayersBus.activeSceneId.value = null
    }

    @Test
    fun bus_defaults_mirror_the_renderers_own() {
        // The bindings push the bus unconditionally once composed, so a bus
        // default that differed from the renderer's would silently change the
        // engine on app start, before the user has touched anything.
        val renderer = ParamSurface.source("render/VisualizerRenderer.kt")
        assertTrue("layerSceneId no longer defaults to null", "var layerSceneId: String? = null" in renderer)
        val mix =
            Regex("var layerMix: Float = ([0-9.]+)f").find(renderer)
                ?: error("layerMix default not found in VisualizerRenderer.kt")
        val blend =
            Regex("var layerBlend: BlendMode = BlendMode\\.(\\w+)").find(renderer)
                ?: error("layerBlend default not found in VisualizerRenderer.kt")
        val defaults = LayersUiState()
        assertFalse("the layer must ship OFF", defaults.enabled)
        assertNull(defaults.sceneId)
        assertEquals(mix.groupValues[1].toFloat(), defaults.mix, 0f)
        assertEquals(BlendMode.valueOf(blend.groupValues[1]), defaults.blend)
    }

    @Test
    fun bindings_push_all_three_layer_fields_and_null_when_off() {
        val plumbing = ParamSurface.source("ui/EnginePlumbing.kt")
        // null rather than the id at mix 0: a set layerSceneId renders a whole
        // second scene per frame whatever the mix says.
        assertTrue(
            "the off switch must clear layerSceneId, not just the mix",
            "renderer.layerSceneId = if (layers.enabled) layers.sceneId else null" in plumbing,
        )
        assertTrue("layerMix is no longer bound", "renderer.layerMix = layers.mix" in plumbing)
        assertTrue("layerBlend is no longer bound", "renderer.layerBlend = layers.blend" in plumbing)
    }

    @Test
    fun bindings_feed_the_picker_its_scene_list_and_the_active_id() {
        // The Customize tabs hold no renderer reference, so the bindings are
        // the only place these two can come from; losing either write leaves
        // the picker empty or unable to exclude the active style.
        val plumbing = ParamSurface.source("ui/EnginePlumbing.kt")
        assertTrue(
            "availableScenes is no longer published from the renderer",
            "LayersBus.availableScenes.value = visualizerView.visualizerRenderer.availableSceneIds()" in plumbing,
        )
        assertTrue(
            "the active scene id is no longer mirrored to the bus",
            "LayersBus.activeSceneId.value = viz.sceneId" in plumbing,
        )
    }

    @Test
    fun the_layers_section_renders_on_the_fx_tab() {
        assertTrue(
            "FxTab no longer mounts LayersSection",
            "LayersSection()" in ParamSurface.tabBodies.getValue(CustomizeTab.FX),
        )
    }

    @Test
    fun the_picker_excludes_the_active_scene() {
        // The renderer ignores a layer naming the active scene (a style
        // blended with itself is just that style at a different exposure), so
        // offering it would be a picker entry that does nothing.
        assertTrue("the layer picker no longer filters the active scene", "it != activeScene" in layersSection())
    }

    @Test
    fun the_blend_selector_offers_every_mode() {
        // Built from the enum, so a ninth BlendMode appended for the shader
        // (BlendMode.kt's append-only contract) reaches the UI for free.
        assertTrue(
            "the blend chips are no longer built from BlendMode.entries",
            "BlendMode.entries.map { it.name.lowercase() }" in layersSection(),
        )
    }

    @Test
    fun the_mix_slider_spans_exactly_what_the_renderer_accepts() {
        // VisualSafety.layerMix coerces into 0..1 before its ADD/DIFFERENCE
        // clamping, so 0..1 keeps the whole slider live and nothing beyond it
        // would be.
        assertTrue(
            "VisualSafety.layerMix no longer coerces to 0..1 - update the slider range to match",
            "coerceIn(0f, 1f)" in ParamSurface.source("render/VisualSafety.kt"),
        )
        assertTrue("the mix slider's range drifted from 0f..1f", "valueRange = 0f..1f" in layersSection())
    }

    @Test
    fun layer_controls_are_not_randomizer_keys() {
        // The randomizer rolls SceneParams only; a roll must never switch on a
        // second full scene per frame. Guarded because lock keys ARE label
        // strings, so a colliding label would silently join the roll surface.
        for (label in listOf("Layers enabled", "Layer style", "Layer mix")) {
            assertFalse("\"$label\" collides with a randomizer key", label in ParamRandomizer.KEYS)
        }
    }

    @Test
    fun the_bus_carries_writes_to_readers() {
        val picked = LayersUiState(enabled = true, sceneId = "plasma", mix = 0.25f, blend = BlendMode.ADD)
        LayersBus.state.value = picked
        assertEquals(picked, LayersBus.state.value)
        LayersBus.availableScenes.value = listOf("plasma", "nebula")
        LayersBus.activeSceneId.value = "nebula"
        assertEquals(listOf("plasma", "nebula"), LayersBus.availableScenes.value)
        assertEquals("nebula", LayersBus.activeSceneId.value)
    }

    /** The `LayersSection` composable's body, sliced out of `CustomizeTabs.kt`. */
    private fun layersSection(): String {
        val src = ParamSurface.source("ui/CustomizeTabs.kt")
        val bounds =
            Regex("(?m)^(?:internal |private |)fun (\\w+)\\(")
                .findAll(src)
                .map { it.groupValues[1] to it.range.first }
                .toList()
        val index = bounds.indexOfFirst { it.first == "LayersSection" }
        assertTrue("no LayersSection in CustomizeTabs.kt", index >= 0)
        return src.substring(bounds[index].second, bounds.getOrNull(index + 1)?.second ?: src.length)
    }
}
