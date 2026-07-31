package dev.musicviz

import dev.musicviz.render.scene.SceneParams
import dev.musicviz.ui.Preset
import dev.musicviz.ui.PresetStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Pins the preset JSON schema: the exact key set PresetStore.toJson writes.
 * Preset keys are the app's only stable parameter IDs today — saved presets,
 * the persisted live viz state and the preset-folder mirror all use them —
 * so a rename or drop silently corrupts users' files. The upcoming ParamKey
 * work must keep serializing these same names (or add explicit migration).
 *
 * Today every SceneParams constructor parameter serializes under its own
 * name, plus the four preset fields and the optional customShader. If a
 * field must ever serialize under a DIFFERENT name, replace the derivation
 * below with an explicit field->key map — do not just delete the assert.
 */
class PresetJsonSchemaTest {
    @Test
    fun toJsonWritesExactlyTheKnownKeys() {
        val preset =
            Preset(
                name = "schema_test",
                sceneId = "julia",
                attack = 0.5f,
                decay = 0.2f,
                customShader = "// s",
                params = SceneParams(),
            )
        val written = JSONObject(PresetStore.toJson(preset))
        val keys = written.keys().asSequence().toSet()

        val paramNames =
            SceneParams::class
                .primaryConstructor!!
                .parameters
                .map { it.name!! }
                .toSet()
        val expected = paramNames + setOf("name", "sceneId", "attack", "decay", "customShader")

        assertEquals(
            "Preset JSON schema drifted. Missing=${expected - keys} Unexpected=${keys - expected}",
            expected,
            keys,
        )
    }

    @Test
    fun customShaderKeyIsOmittedWhenNull() {
        val preset = Preset(name = "n", sceneId = "s", attack = 0.1f, decay = 0.1f, customShader = null)
        val written = JSONObject(PresetStore.toJson(preset))
        assertEquals(false, written.has("customShader"))
    }
}
