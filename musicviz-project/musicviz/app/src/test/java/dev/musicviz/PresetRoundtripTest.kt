package dev.musicviz

import dev.musicviz.data.Preset
import dev.musicviz.data.PresetStore
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Guards the preset pipeline against silently dropping parameters: builds a
 * SceneParams where EVERY field differs from its default (via reflection, so
 * fields added later are covered automatically), roundtrips it through
 * PresetStore's JSON, and asserts field-by-field equality. This is the P0
 * "preset roundtrip" gate from todo.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetRoundtripTest {
    @Test
    fun everySceneParamsFieldSurvivesJsonRoundtrip() {
        val ctor = SceneParams::class.primaryConstructor!!
        val defaults = SceneParams()
        val byName = SceneParams::class.memberProperties.associateBy { it.name }

        // Build args where every parameter is deliberately non-default.
        val args =
            ctor.parameters.associateWith { param ->
                val current = byName.getValue(param.name!!).get(defaults)
                when (current) {
                    is Float -> current + 0.137f
                    is Int -> current + 3
                    is Boolean -> !current
                    is String -> current + "_x"
                    else -> error("Unhandled SceneParams field type for '${param.name}': $current")
                }
            }
        val mutated = ctor.callBy(args)

        val preset =
            Preset(
                name = "roundtrip_test",
                sceneId = "julia",
                attack = 0.77f,
                decay = 0.31f,
                customShader = "// custom\nvoid main() {}",
                params = mutated,
                // The .milk source rides in the preset like the GLSL does:
                // on the MilkDrop style it IS the visual, and dropping it in
                // the JSON is what made a saved preset reload as the engine's
                // idle "M" logo.
                milkPreset = "MILKDROP_PRESET_VERSION=201\n[preset00]\n",
            )

        val parsed = PresetStore.fromJson(PresetStore.toJson(preset))

        assertEquals(preset.name, parsed.name)
        assertEquals(preset.sceneId, parsed.sceneId)
        assertEquals(preset.attack, parsed.attack, 1e-4f)
        assertEquals(preset.decay, parsed.decay, 1e-4f)
        assertEquals(preset.customShader, parsed.customShader)
        assertEquals(preset.milkPreset, parsed.milkPreset)

        val failures = mutableListOf<String>()
        for (prop in SceneParams::class.memberProperties) {
            val want = prop.get(mutated)
            val got = prop.get(parsed.params)
            val ok =
                when (want) {
                    is Float -> got is Float && kotlin.math.abs(want - got) < 1e-4f
                    else -> want == got
                }
            if (!ok) failures += "${prop.name}: saved=$want loaded=$got"
        }
        assertEquals("Fields dropped by preset JSON: $failures", 0, failures.size)
    }
}
