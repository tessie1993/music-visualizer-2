package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Uniform parity for the WATER display pass and the BEAM scene, the
 * [HyperspaceUniformParityTest] mechanism applied to its two siblings.
 *
 * The failure both directions guard is the same one that shipped there: an
 * unset GL uniform reads zero, silently. For BEAM a zero `uSigma` collapses
 * every quad to its padding and a zero `uGain` flatlines the trace; for the
 * water display a zero `uBrightness` is a black pool and a zero `uInvRes`
 * breaks the normal reconstruction. None of them raise anything anywhere -
 * the frame is just wrong. The other direction (an upload the shader never
 * declares) is a location of -1 and a dropped call: dead weight that is
 * always either a typo or a leftover.
 *
 * Source-parsed for the reasons the Hyperspace test lays out: `glUniform*`
 * calls do not survive into any reflectable runtime object, and a unit test
 * has no GL to link a program in.
 */
class SceneUniformParityTest {
    /**
     * One scene's program: the shader stages it links and the pattern its
     * uniform-location cache is filled through. Both scenes follow the
     * `loc("name")` idiom; WaterScene's display cache is `dLoc` and scoped
     * to the display program alone, which is exactly the pair under test
     * (the ripple sim's own passes have their own caches in RippleSim).
     */
    private data class SceneProgram(
        val label: String,
        val sceneSource: String,
        val locFunction: String,
        val shaderFiles: List<String>,
        /** Uploads with no matching declaration that are fine, with reasons. */
        val uploadExemptions: Map<String, String> = emptyMap(),
    )

    private val programs =
        listOf(
            SceneProgram(
                label = "BEAM",
                sceneSource = "render/scene/BeamScene.kt",
                locFunction = "loc",
                shaderFiles = listOf("beam_vert.glsl", "beam_frag.glsl"),
            ),
            SceneProgram(
                label = "WATER display",
                sceneSource = "render/fluid/WaterScene.kt",
                locFunction = "dLoc",
                shaderFiles = listOf("fluid_base_vert.glsl", "water_display_frag.glsl"),
            ),
        )

    @Test
    fun the_scene_uploads_every_uniform_its_program_declares() {
        for (p in programs) {
            val declared = p.shaderFiles.flatMap { declaredUniforms(rawShader(it)) }.toSet()
            assertTrue("${p.label}: no uniforms found in ${p.shaderFiles}", declared.isNotEmpty())
            val uploaded = uploadedUniforms(source(p.sceneSource), p.locFunction)
            assertTrue("${p.label}: no ${p.locFunction}(\"...\") uploads found in ${p.sceneSource}", uploaded.isNotEmpty())
            assertEquals(
                "${p.label}: the program declares uniforms the scene never uploads. An unset " +
                    "uniform reads 0 - for a beam width, a gain or a texel size that is a wrong " +
                    "frame with no error anywhere",
                emptyList<String>(),
                (declared - uploaded).sorted(),
            )
        }
    }

    @Test
    fun the_scene_uploads_nothing_its_program_does_not_declare() {
        for (p in programs) {
            val declared = p.shaderFiles.flatMap { declaredUniforms(rawShader(it)) }.toSet()
            val uploaded = uploadedUniforms(source(p.sceneSource), p.locFunction)
            val strays = (uploaded - declared - p.uploadExemptions.keys).sorted()
            assertEquals(
                "${p.label}: uploads with no declaration in ${p.shaderFiles} (location -1, " +
                    "value dropped - a typo against a name the shader does read, or a leftover)",
                emptyList<String>(),
                strays,
            )
            assertEquals(
                "${p.label}: stale upload exemptions - remove",
                emptyList<String>(),
                (p.uploadExemptions.keys - uploaded).sorted(),
            )
        }
    }

    // ------------------------------------------------------------------ parse

    private fun declaredUniforms(src: String): Set<String> =
        Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)\s*(?:\[[^\]]*\])?\s*;""")
            .findAll(stripComments(src))
            .map { it.groupValues[1] }
            .toSet()

    private fun uploadedUniforms(
        src: String,
        locFunction: String,
    ): Set<String> =
        Regex("""\b${Regex.escape(locFunction)}\("(\w+)"\)""")
            .findAll(stripComments(src))
            .map { it.groupValues[1] }
            .toSet()

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    // ------------------------------------------------------------------- read

    private fun rawShader(name: String): String = repoFile("src/main/res/raw/$name")

    private fun source(relative: String): String = repoFile("src/main/java/dev/musicviz/$relative")

    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
