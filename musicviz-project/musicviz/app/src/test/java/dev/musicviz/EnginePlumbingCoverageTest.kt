package dev.musicviz

import dev.musicviz.render.BlendMode
import dev.musicviz.ui.LayersUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Two drift gates around `EnginePlumbing.kt`, held to that file's own header
 * contract.
 *
 * BINDING COVERAGE: `VisualizerEngineBindings` exists because renderer inputs
 * bound anywhere shallower "silently stopped applying whenever the player was
 * collapsed" (the header's own words). The failure mode recurs one field at a
 * time: someone adds a public `var` to [dev.musicviz.render.VisualizerRenderer],
 * writes it from whatever screen they are working in, and the knob dies the
 * moment that screen unmounts. So: every public mutable renderer field must be
 * written inside `EnginePlumbing.kt`, or carry a reason in the exemption map.
 *
 * DEFAULTS PARITY: `LayersUiState`'s doc line says its defaults "mirror the
 * renderer's own". Nothing enforced that - and a bus default drifting from the
 * renderer default means the FIRST paint of the Layers controls shows values
 * the engine is not using, until the user wiggles something. The renderer
 * cannot be instantiated off the GL thread cheaply, so its side is read from
 * source, the same trade [RendererWiringTest] makes.
 *
 * Both gates are source-scans; `EnginePlumbing.kt` and `VisualizerRenderer.kt`
 * are read, never reflected into.
 */
class EnginePlumbingCoverageTest {
    /**
     * Renderer vars deliberately NOT plumbed through the bindings, with the
     * reason. Empty today: every public mutable field is UI-owned state and
     * all twelve are bound. An entry here needs an owner that is provably
     * always-composed (or not UI state at all).
     */
    private val notPlumbed: Map<String, String> = emptyMap()

    private val rendererSource: String by lazy { source("render/VisualizerRenderer.kt") }
    private val plumbingSource: String by lazy { source("ui/EnginePlumbing.kt") }

    @Test
    fun every_public_renderer_var_is_bound_in_engine_plumbing() {
        val publicVars =
            Regex("""^    var (\w+)""", RegexOption.MULTILINE)
                .findAll(stripComments(rendererSource))
                .map { it.groupValues[1] }
                .toSet()
        assertTrue("no public vars found in VisualizerRenderer.kt - parse broke?", publicVars.size >= 10)

        val plumbing = stripComments(plumbingSource)
        val unbound =
            publicVars
                .filter { name -> !Regex("""\.$name\s*=[^=]""").containsMatchIn(plumbing) }
                .filter { it !in notPlumbed }
                .sorted()
        assertEquals(
            "public VisualizerRenderer vars never written in EnginePlumbing.kt. A renderer " +
                "input bound anywhere shallower dies when that screen unmounts (the file " +
                "header's own bug class); bind it in VisualizerEngineBindings or add a " +
                "justified exemption",
            emptyList<String>(),
            unbound,
        )
        assertEquals(
            "stale notPlumbed entries - these are bound now (or gone); remove them",
            emptyList<String>(),
            (notPlumbed.keys - publicVars).sorted() +
                notPlumbed.keys.filter { name ->
                    Regex("""\.$name\s*=[^=]""").containsMatchIn(plumbing)
                },
        )
    }

    @Test
    fun the_bindings_stay_composed_at_the_shell() {
        assertTrue(
            "AppShell no longer composes VisualizerEngineBindings - the bindings only hold " +
                "their contract while they are always composed",
            stripComments(source("ui/AppShell.kt")).contains("VisualizerEngineBindings("),
        )
    }

    @Test
    fun layers_bus_defaults_equal_the_renderer_layer_defaults() {
        val defaults = LayersUiState()
        // The renderer side, read off the field initializers.
        val src = stripComments(rendererSource)
        val mix =
            Regex("""var layerMix:\s*Float\s*=\s*([\d.]+)f""")
                .find(src)
                ?.groupValues
                ?.get(1)
                ?.toFloat()
                ?: fail<Float>("could not read layerMix default from VisualizerRenderer.kt")
        val blend =
            Regex("""var layerBlend:\s*BlendMode\s*=\s*BlendMode\.(\w+)""")
                .find(src)
                ?.groupValues
                ?.get(1)
                ?: fail<String>("could not read layerBlend default from VisualizerRenderer.kt")
        val sceneId =
            Regex("""var layerSceneId:\s*String\?\s*=\s*(\S+)""")
                .find(src)
                ?.groupValues
                ?.get(1)
                ?: fail<String>("could not read layerSceneId default from VisualizerRenderer.kt")

        assertEquals(
            "LayersUiState.mix default drifted from VisualizerRenderer.layerMix - the Layers " +
                "panel's first paint would show a mix the engine is not using",
            mix,
            defaults.mix,
            1e-6f,
        )
        assertEquals(
            "LayersUiState.blend default drifted from VisualizerRenderer.layerBlend",
            BlendMode.valueOf(blend),
            defaults.blend,
        )
        assertEquals(
            "the renderer's layer must default OFF (null layerSceneId), matching the bus",
            "null",
            sceneId,
        )
        assertEquals("the bus's layer must default off", false, defaults.enabled)
        assertEquals("the bus's layer scene must default unset", null, defaults.sceneId)
    }

    // ---------------------------------------------------------------- helpers

    /** Typed wrapper so `fail` can appear on the right of `?:`. */
    private fun <T> fail(message: String): T {
        org.junit.Assert.fail(message)
        error("unreachable")
    }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("src/main/java/dev/musicviz/", "app/src/main/java/dev/musicviz/")) {
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        org.junit.Assert.fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
