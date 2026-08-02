package dev.musicviz

import dev.musicviz.render.scene.HyperspaceMath
import dev.musicviz.render.scene.MarchBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Every uniform `hyperspace_frag.glsl` declares is one `HyperspaceScene`
 * uploads, and one the preview harness uploads too.
 *
 * An unset GL uniform reads zero. For most of this shader's controls that is
 * merely a feature switched off, but the march is integrated in terms of its
 * own step length, so a zero there is not a smaller picture - it is no picture:
 * `uMaxStep` at 0 clamps every step to the hit epsilon, the ray covers a few
 * hundredths of a unit across the whole step budget, and the frame comes back
 * black. That is exactly what shipped uncommitted here - the shader grew
 * `uMaxStep`, `HyperspaceLook.maxMarchStep` was written to supply it, and the
 * one line that hands the value over was missed.
 *
 * The preview harness could not catch it. `tools/shaderpreview` renders the
 * REAL shader but drives it from a JS mirror of the Kotlin scene, so a uniform
 * present in the shader and in the mirror and absent from the app renders
 * perfectly in the harness and black on a phone. The harness's own README says
 * as much under "What it cannot tell you". So the mirror is asserted against
 * the shader here as well: three lists that have to agree, checked where all
 * three can be read.
 *
 * Parsing source rather than reflecting follows `ParticleStyleTest` and
 * `RecoveredShaderStylesTest` - a `glUniform*` call is not something that
 * survives into a runtime object, and there is no GL in a unit test to ask.
 *
 * The same file also pins what those uniforms are allowed to SAY. Three of
 * them are loop budgets whose ceiling is a `#define`, and one is a bounding
 * radius that has to enclose a set defined by a constant in the GLSL. Neither
 * fails loudly: a budget over its `#define` is silently truncated by the
 * loop's own bound, so the top of the Detail slider quietly stops buying
 * anything, and a bounding radius that is too small clips the body it was
 * meant to contain and lets the camera inside it. Both are readable from the
 * two files, so both are checked here rather than left to a screenshot.
 */
class HyperspaceUniformParityTest {
    private val shader: String by lazy { rawShader("hyperspace_frag.glsl") }
    private val sceneSource: String by lazy { source("render/scene/HyperspaceScene.kt") }
    private val harnessDriver: String by lazy { toolFile("shaderpreview/lib/scenes.mjs") }

    /**
     * Samplers are bound by `glUniform1i` against a texture unit and are
     * uploaded like anything else; nothing here is exempt. Listed so the
     * intent is visible if one is ever added.
     */
    private val samplers = setOf("uFlowTex", "uDyeTex")

    @Test
    fun the_scene_uploads_every_uniform_the_shader_declares() {
        val declared = declaredUniforms(shader)
        assertTrue("no uniforms found in hyperspace_frag.glsl", declared.isNotEmpty())
        assertTrue("sampler list drifted from the shader", samplers.all { it in declared })
        val uploaded = uploadedUniforms(sceneSource)
        assertTrue("no uniform uploads found in HyperspaceScene.kt", uploaded.isNotEmpty())
        assertEquals(
            "hyperspace_frag.glsl declares uniforms HyperspaceScene never uploads. An unset " +
                "uniform reads 0, and for a march bound (uMaxStep) or a scale (uMeltScale) that " +
                "is a black frame, not a disabled effect",
            emptyList<String>(),
            (declared - uploaded).sorted(),
        )
    }

    /**
     * The other direction. An upload with no declaration is dead weight rather
     * than a broken frame - `glGetUniformLocation` answers -1 and the driver
     * discards the call - but it is always either a typo in the name the
     * shader does read, or the leftover of a uniform that was removed, and
     * both are worth failing on while the name is still fresh.
     */
    @Test
    fun the_scene_uploads_nothing_the_shader_does_not_declare() {
        assertEquals(
            "HyperspaceScene uploads uniforms hyperspace_frag.glsl does not declare " +
                "(the location is -1 and the value is dropped - check the spelling)",
            emptyList<String>(),
            (uploadedUniforms(sceneSource) - declaredUniforms(shader)).sorted(),
        )
    }

    /**
     * And the harness mirror, which is the only one of the three that can be
     * wrong without anyone seeing it: it declares what it supplies in a `Set`
     * it then checks the linked program against, so a name missing from that
     * set is a uniform the preview renders at zero while the app renders
     * correctly - the same divergence as the app bug, pointing the other way.
     */
    @Test
    fun the_preview_harness_mirror_supplies_the_same_set() {
        val supplies = harnessSupplies()
        assertTrue("no `supplies` set found in tools/shaderpreview/lib/scenes.mjs", supplies.isNotEmpty())
        assertEquals(
            "the preview harness's `supplies` set has drifted from hyperspace_frag.glsl. " +
                "It renders the real shader from a mirror of the Kotlin, so a name only the " +
                "mirror is missing makes the preview lie about the app (and vice versa)",
            declaredUniforms(shader).sorted(),
            supplies.sorted(),
        )
    }

    /**
     * The loop ceilings. `uSteps`, `uIters`, `uBulbIters` and `uSeedIters` are
     * uniforms so that Detail can move without recompiling, but each one only
     * counts up to a `#define` the loop is bounded by, and GL will happily
     * accept a larger value and ignore the excess. `MarchBudget` names all four
     * ceilings so it can interpolate up to them; if it names a bigger one than
     * the shader has, the top of the slider costs the user nothing and buys
     * them nothing, and there is no error anywhere to notice.
     */
    @Test
    fun the_march_budget_ceilings_are_the_shaders_own_defines() {
        val defines = shaderDefines()
        assertEquals(
            "the shader's #defines and MarchBudget's ceilings have drifted. A budget above " +
                "the #define is silently clamped by the loop bound, so the Detail slider goes " +
                "dead at the top with nothing to show for it",
            mapOf(
                "MAX_STEPS" to MarchBudget.MAX_STEPS,
                "MAX_ITERS" to MarchBudget.MAX_ITERS,
                "MAX_BULB_ITERS" to MarchBudget.MAX_BULB_ITERS,
                "MAX_SEED_ITERS" to MarchBudget.MAX_SEED_ITERS,
            ),
            defines.filterKeys { it.startsWith("MAX_") && it != "MAX_BLOOMS" },
        )
        assertEquals(
            "the shader sizes its body arrays by MAX_BLOOMS and the scene uploads " +
                "HyperspaceMath.MAX_BLOOMS of each; the smaller of the two wins, silently",
            HyperspaceMath.MAX_BLOOMS,
            defines["MAX_BLOOMS"],
        )
    }

    /**
     * The bounding sphere has to contain the body.
     *
     * For five of the six species the enclosing radius is the fold domain's
     * own, which is a property of the estimator and cannot be read off a
     * constant. SEED is the exception and the strict one: it is an escape-time
     * set, and the filled Julia set of `z^2 + c` lies inside the sphere of
     * radius `(1 + sqrt(1 + 4|c|)) / 2` - outside it every orbit escapes
     * monotonically, whatever the iteration budget. So the radius the CPU hands
     * the GPU is checkable against the `c` the GPU actually iterates, and it is
     * worth checking: `HyperspaceLook.cameraDistance` keeps the eye outside
     * this sphere, and a raymarch begun inside a distance estimator draws
     * stripes rather than an interior.
     */
    @Test
    fun the_seed_bounding_radius_encloses_the_set_the_shader_draws() {
        val c =
            Regex("""const\s+vec4\s+c\s*=\s*vec4\(([^)]*)\)""")
                .find(shader.substringAfter("float deSeed("))
                ?.groupValues
                ?.get(1)
                ?.split(",")
                ?.map { it.trim().toFloat() }
        assertEquals("could not read deSeed's constant out of hyperspace_frag.glsl", 4, c?.size)
        val magnitude = sqrt(c!!.sumOf { (it * it).toDouble() }).toFloat()
        val escapeRadius = (1f + sqrt(1f + 4f * magnitude)) / 2f
        assertTrue(
            "deSeed's |c| = $magnitude puts the filled Julia set inside radius $escapeRadius, " +
                "which does not fit in localRadius(SEED) = " +
                "${HyperspaceMath.localRadius(HyperspaceMath.Species.SEED)}",
            escapeRadius <= HyperspaceMath.localRadius(HyperspaceMath.Species.SEED),
        )
        // And not absurdly oversized either - the sphere is what lets a ray
        // skip a body, and it is also what pushes the camera back.
        assertTrue(
            "localRadius(SEED) is more than a tenth larger than it needs to be",
            HyperspaceMath.localRadius(HyperspaceMath.Species.SEED) <= escapeRadius * 1.1f,
        )
    }

    // ------------------------------------------------------------------ parse

    /** Every `#define NAME <integer>` in the shader. */
    private fun shaderDefines(): Map<String, Int> =
        Regex("""^\s*#define\s+(\w+)\s+(\d+)\s*$""", RegexOption.MULTILINE)
            .findAll(shader)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

    private fun declaredUniforms(src: String): Set<String> =
        Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)\s*(?:\[[^\]]*\])?\s*;""")
            .findAll(stripComments(src))
            .map { it.groupValues[1] }
            .toSet()

    /** Every name passed to this scene's `loc(...)` uniform-location cache. */
    private fun uploadedUniforms(src: String): Set<String> =
        Regex("""loc\("(\w+)"\)""")
            .findAll(stripComments(src))
            .map { it.groupValues[1] }
            .toSet()

    /** The harness's `const supplies = new Set([...])`. */
    private fun harnessSupplies(): Set<String> {
        val block =
            Regex("""supplies\s*=\s*new\s+Set\(\[(.*?)\]\)""", RegexOption.DOT_MATCHES_ALL)
                .find(stripComments(harnessDriver))
                ?.groupValues
                ?.get(1)
                ?: return emptySet()
        return Regex("""'(\w+)'""").findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    // ------------------------------------------------------------------- read

    private fun rawShader(name: String): String = repoFile("app/src/main/res/raw/$name")

    private fun source(relative: String): String = repoFile("app/src/main/java/dev/musicviz/$relative")

    private fun toolFile(relative: String): String = repoFile("tools/$relative")

    /**
     * Resolves a path under the module directory, whichever directory the
     * tests run from. Unlike the `app/`-rooted helpers in the sibling tests
     * this one has to reach `tools/` as well, so it anchors on the module.
     */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
