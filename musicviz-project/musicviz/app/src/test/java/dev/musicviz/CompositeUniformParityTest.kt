package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `composite_frag` has two writers, and they must upload the same uniforms.
 *
 * The live renderer's `onDrawFrame` and the export path's
 * `FxCompositor.composite` are two hand-written spellings of one shader pass.
 * `FxCompositor` even carries a comment promising "the two composite call sites
 * stay uniform-for-uniform identical" - and it was already false: the export
 * path uploaded neither `uLayerMix` nor `uBlendMode`, so a layered export would
 * have read GL's zero-initialised defaults and silently rendered the bottom
 * layer alone. Unreachable while export pins `uStyle = CUT`, which is exactly
 * why nobody noticed, and exactly why a comment was never going to hold the
 * line.
 *
 * Source-level for the same reason as [RendererWiringTest] and
 * [TrailWarpDecayDedupTest]: both call sites need a GL context, but *which
 * uniforms each one writes* is a fact about the code.
 *
 * A deliberate asymmetry is allowed, but it has to be declared in
 * [JUSTIFIED_ASYMMETRY] with the reason - never by deleting an assertion.
 */
class CompositeUniformParityTest {
    private companion object {
        /**
         * Uniforms one call site may legitimately skip, and why. Empty on
         * purpose: today both paths upload the full set, and a new entry here
         * is a design decision that should be argued in review rather than a
         * convenient way to make this test pass.
         */
        val JUSTIFIED_ASYMMETRY: Map<String, String> = emptyMap()
    }

    /** `cLoc("uFoo")` - the live renderer's composite-program accessor. */
    private val liveUniforms: Set<String> by lazy {
        namesIn(repoFile("src/main/java/dev/musicviz/render/VisualizerRenderer.kt"), """cLoc\("([A-Za-z0-9_]+)"\)""")
    }

    /** `loc("uFoo")` - FxCompositor's composite-program accessor. */
    private val exportUniforms: Set<String> by lazy {
        namesIn(repoFile("src/main/java/dev/musicviz/export/FxCompositor.kt"), """\bloc\("([A-Za-z0-9_]+)"\)""")
    }

    @Test
    fun `both composite call sites upload the same uniform set`() {
        val liveOnly = liveUniforms - exportUniforms - JUSTIFIED_ASYMMETRY.keys
        val exportOnly = exportUniforms - liveUniforms - JUSTIFIED_ASYMMETRY.keys
        assertEquals(
            "uniforms the LIVE composite uploads and the EXPORT composite does not - " +
                "an exported frame will use GL's zero default for each of these",
            emptySet<String>(),
            liveOnly,
        )
        assertEquals(
            "uniforms the EXPORT composite uploads and the LIVE composite does not",
            emptySet<String>(),
            exportOnly,
        )
    }

    @Test
    fun `the parity check is actually looking at something`() {
        // A regex that silently stops matching would make the test above pass
        // by measuring nothing. Both sides are ~50 uniforms today.
        assertTrue("live uniform scan found only ${liveUniforms.size}", liveUniforms.size > 40)
        assertTrue("export uniform scan found only ${exportUniforms.size}", exportUniforms.size > 40)
        assertTrue("uTexA missing from the live scan", "uTexA" in liveUniforms)
        assertTrue("uTexA missing from the export scan", "uTexA" in exportUniforms)
    }

    @Test
    fun `every declared asymmetry names the call site that skips it`() {
        for ((uniform, reason) in JUSTIFIED_ASYMMETRY) {
            assertTrue(
                "$uniform's exemption reason must say which path skips it and why",
                reason.contains("live", ignoreCase = true) || reason.contains("export", ignoreCase = true),
            )
        }
    }

    private fun namesIn(
        source: String,
        pattern: String,
    ): Set<String> = Regex(pattern).findAll(source).map { it.groupValues[1] }.toSet()

    /** Resolves a path under `app/`, whichever directory the tests run from. */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
