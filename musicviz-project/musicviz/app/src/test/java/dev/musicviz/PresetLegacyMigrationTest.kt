package dev.musicviz

import dev.musicviz.render.scene.SceneParams
import dev.musicviz.ui.PresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.reflect.full.memberProperties

/**
 * Characterizes how presets saved by OLDER app versions load today, before
 * the persistence refactor touches this path. A legacy preset is one whose
 * JSON carries only the original four fields; every SceneParams value must
 * then come from PresetStore.fromJson's documented defaults.
 *
 * Those defaults are the CONSTRUCTOR defaults for every field except two
 * deliberate migration deltas (see the comments in PresetStore.fromJson):
 *  - fluidCatchPoints: 0 (constructor: 2) — pre-v0.13.0 presets were tuned
 *    without catch wells and must not gain suction.
 *  - fluidParticleLife: 12f (constructor: 6f) — matches the old ~12.5 s
 *    mean rebirth those presets were designed around.
 *
 * If this test fails after adding a SceneParams field, either the new
 * field's fromJson default doesn't match its constructor default (add it to
 * [LEGACY_DELTAS] only if that difference is an intentional migration), or
 * fromJson doesn't read the field at all (PresetRoundtripTest catches the
 * write side).
 */
class PresetLegacyMigrationTest {
    @Test
    fun legacyPresetJsonLoadsDocumentedDefaults() {
        val parsed =
            PresetStore.fromJson(
                """{"name":"old","sceneId":"nebula","attack":0.6,"decay":0.12}""",
            )
        assertEquals("old", parsed.name)
        assertEquals("nebula", parsed.sceneId)
        assertEquals(null, parsed.customShader)

        val constructorDefaults = SceneParams()
        val failures = mutableListOf<String>()
        for (prop in SceneParams::class.memberProperties) {
            val loaded = prop.get(parsed.params)
            val expected = LEGACY_DELTAS[prop.name] ?: prop.get(constructorDefaults)
            val ok =
                when (expected) {
                    is Float -> loaded is Float && abs(expected - loaded) < 1e-4f
                    else -> expected == loaded
                }
            if (!ok) failures += "${prop.name}: expected=$expected loaded=$loaded"
        }
        assertEquals("Legacy-preset defaults drifted: $failures", 0, failures.size)
    }

    @Test
    fun legacyDeltasStayExactlyTheDocumentedTwo() {
        // Guards the guard: if someone adds a third silent divergence between
        // the constructor and fromJson, the first test fails and the fix must
        // consciously extend this list — never accidentally.
        assertEquals(setOf("fluidCatchPoints", "fluidParticleLife"), LEGACY_DELTAS.keys)
        assertTrue(LEGACY_DELTAS["fluidCatchPoints"] is Int)
        assertTrue(LEGACY_DELTAS["fluidParticleLife"] is Float)
    }

    private companion object {
        /** Field name -> value a key-less (pre-v0.13.0) preset must load with. */
        val LEGACY_DELTAS: Map<String, Any> =
            mapOf(
                "fluidCatchPoints" to 0,
                "fluidParticleLife" to 12f,
            )
    }
}
