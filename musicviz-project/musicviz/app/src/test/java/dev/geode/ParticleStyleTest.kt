package dev.geode

import dev.geode.render.scene.EmergenceSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The CPU/GPU contract of the particle styles, which nothing else can catch:
 * a mismatch between `ParticleSceneBase`'s vertex layout and
 * `particle_vert.glsl`'s attribute declarations, or a uniform name that no
 * shader declares, produces NO GL error at all. The attributes simply read
 * garbage and `glGetUniformLocation` quietly returns -1, so the style renders
 * wrong (or not at all) on a device with a green build behind it.
 *
 * These are source-level checks - unit tests have no GL context - in the same
 * spirit as [ParticleGatingTest] reading its gating back out of the UI source.
 */
class ParticleStyleTest {
    private companion object {
        /** The shader libraries the particle look is shared through. */
        val LIBRARIES = listOf("lib_particle_common", "lib_particle_shade")

        /** Stages that must NOT take the fwidth-based shading library. */
        val VERTEX_STAGES = listOf("emergence_vert.glsl", "fluid_particle_vert.glsl")

        val FRAGMENT_STAGES = listOf("emergence_frag.glsl", "fluid_particle_frag.glsl")
    }

    /** `layout(location = N) in <type> <name>;` in declaration order. */
    private data class Attribute(
        val location: Int,
        val floats: Int,
        val name: String,
    )

    // Comments are stripped everywhere: all of these checks are about what the
    // code DOES, and both files talk about the point-sprite path they replaced.
    private val vertexSource: String by lazy { stripComments(rawShader("emergence_vert.glsl")) }
    private val fragmentSource: String by lazy { stripComments(rawShader("emergence_frag.glsl")) }
    private val echoVertexSource: String by lazy { stripComments(rawShader("emergence_echo_vert.glsl")) }
    private val echoFragmentSource: String by lazy { stripComments(rawShader("emergence_echo_frag.glsl")) }
    private val baseSource: String by lazy { stripComments(source("render/scene/EmergenceScene.kt")) }
    private val simSource: String by lazy { stripComments(source("render/scene/EmergenceSim.kt")) }

    @Test
    fun instanceAttributesMatchTheKotlinVertexLayout() {
        val attrs = attributes(vertexSource)
        assertTrue("emergence_vert.glsl declares no attributes", attrs.isNotEmpty())
        // Location 0 is the shared unit quad (divisor 0), not per-particle
        // state; everything above it is one particle's record.
        assertEquals("location 0 must stay the billboard corner", "aCorner", attrs.first().name)
        assertEquals(0, attrs.first().location)
        val instanceAttrs = attrs.drop(1)
        assertEquals(
            "instance attribute locations must be contiguous from 1",
            (1..instanceAttrs.size).toList(),
            instanceAttrs.map { it.location },
        )
        assertEquals(
            "FLOATS_PER_PARTICLE must equal the floats the shader reads per instance",
            EmergenceSim.FLOATS_PER_PARTICLE,
            instanceAttrs.sumOf { it.floats },
        )
        // The Kotlin side describes the same records as `location to
        // components` pairs; keep the two spellings of the layout in step.
        assertEquals(
            "the `layout` list in EmergenceScene.init disagrees with emergence_vert.glsl",
            instanceAttrs.map { it.location to it.floats },
            kotlinInstanceLayout(),
        )
    }

    @Test
    fun velocityOffsetPointsAtTheVelocityAttribute() {
        val instanceAttrs = attributes(vertexSource).drop(1)
        val offset =
            instanceAttrs
                .takeWhile { it.name != "aVel" }
                .sumOf { it.floats }
        assertTrue("emergence_vert.glsl has no aVel attribute", instanceAttrs.any { it.name == "aVel" })
        assertEquals(
            "VELOCITY_OFFSET must index aVel inside one particle's record",
            offset,
            EmergenceSim.VELOCITY_OFFSET,
        )
        assertEquals("velocity is a vec2", 2, instanceAttrs.first { it.name == "aVel" }.floats)
    }

    @Test
    fun everyUploadedUniformIsDeclaredBySomeStage() {
        val uploaded = Regex("""loc\("(\w+)"\)""").findAll(baseSource).map { it.groupValues[1] }.toSortedSet()
        assertTrue("no uniform uploads found in EmergenceScene.kt", uploaded.isNotEmpty())
        val declared =
            declaredUniforms(vertexSource) + declaredUniforms(fragmentSource) +
                declaredUniforms(echoVertexSource) + declaredUniforms(echoFragmentSource)
        assertEquals(
            "uniforms uploaded by EmergenceScene that no emergence shader declares " +
                "(glGetUniformLocation returns -1 and the upload is silently dropped)",
            emptyList<String>(),
            uploaded.filterNot { it in declared },
        )
    }

    @Test
    fun theSimulationPublishesAVelocity() {
        // The billboards lean along it, so a sim that never writes the slot
        // renders permanently axis-aligned - visible only on a device.
        assertTrue(
            "EmergenceSim never fills the velocity slot of its records",
            simSource.contains("VELOCITY_OFFSET"),
        )
    }

    @Test
    fun bothFamiliesShadeThroughTheOneSharedChunk() {
        // The CPU styles and the fluid layer are two different pipelines with
        // one look. If either grows its own copy of the shading, they drift -
        // and the drift shows up as "the same Particle shape looks different
        // on Fluid", which nothing else in the suite would catch.
        val common = stripComments(rawShader("lib_particle_common.glsl"))
        val shade = stripComments(rawShader("lib_particle_shade.glsl"))
        listOf("lib_particle_common.glsl" to common, "lib_particle_shade.glsl" to shade).forEach { (name, src) ->
            assertTrue("$name must not carry its own #version", !src.contains("#version"))
        }
        // The common library is included by VERTEX stages too, where fwidth()
        // does not exist - a derivative call in it fails to compile on every
        // device, which is exactly the split the two files encode.
        assertTrue("lib_particle_common.glsl must declare its own precision", common.contains("precision highp float;"))
        assertTrue("lib_particle_common.glsl uses a fragment-only derivative", !common.contains("fwidth("))
        assertTrue("the shading belongs in lib_particle_shade.glsl", shade.contains("fwidth("))
        listOf("ptShapeField", "ptRadiusFade", "ptBillboard", "ptAces").forEach {
            assertTrue("lib_particle_common.glsl is missing $it", common.contains("$it("))
        }
        assertTrue("lib_particle_shade.glsl is missing ptShade", shade.contains("ptShade("))
        val consumers =
            mapOf(
                "emergence_frag.glsl" to fragmentSource,
                "emergence_vert.glsl" to vertexSource,
                "fluid_particle_frag.glsl" to stripComments(rawShader("fluid_particle_frag.glsl")),
                "fluid_particle_vert.glsl" to stripComments(rawShader("fluid_particle_vert.glsl")),
            )
        consumers.forEach { (name, src) ->
            // Calls the shared code...
            assertTrue("$name does not use the shared look", Regex("""\bpt[A-Z]\w*\(""").containsMatchIn(src))
            // ...and does not redefine any of it.
            assertTrue(
                "$name defines its own copy of a shared function",
                !Regex("""\bfloat\s+pt[A-Z]\w*\s*\(|\bvec[234]\s+pt[A-Z]\w*\s*\(""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun everyParticleStageDeclaresTheLibrariesItUses() {
        // The shared look reaches both families through `//#include`, and a
        // library is only substituted if `GlUtil.INCLUDES` registers it. Both
        // halves are pinned here because the failure modes are invisible: an
        // unregistered name throws at compile time on a device, and a stage
        // that calls the shared look without naming its library compiles to
        // "undefined function" nowhere a unit test would otherwise look.
        //
        // This wiring has been lost once already - a merge took one side's
        // GlUtil wholesale and left the other side's call sites pointing at a
        // splice helper that no longer existed, which cost the app a build.
        val glUtil = source("render/scene/GlUtil.kt")
        LIBRARIES.forEach {
            assertTrue("GlUtil.INCLUDES does not register $it", glUtil.contains("\"$it\" to R.raw.$it"))
            assertTrue("$it.glsl is missing", rawShader("$it.glsl").isNotEmpty())
        }
        VERTEX_STAGES.forEach { name ->
            assertEquals(
                "$name must include the stage-neutral library and only that one",
                listOf("lib_particle_common"),
                includesOf(name),
            )
        }
        FRAGMENT_STAGES.forEach { name ->
            assertEquals(
                "$name must include the shared geometry and the shared shading, in that order",
                listOf("lib_particle_common", "lib_particle_shade"),
                includesOf(name),
            )
        }
    }

    @Test
    fun everyShaderLoaderResolvesIncludes() {
        // Include resolution is a property of loading a shader, not of which
        // class happens to load it: a loader that reads the raw resource
        // instead ships the directive to the driver as a comment, and the
        // shader fails with an undefined function on a device only.
        val offenders =
            shaderLoadingSources().filter { (_, src) ->
                Regex("""fun loadRaw\([^)]*\)[^=]*=\s*\n?\s*\w+\s*\n?\s*\.resources""").containsMatchIn(src)
            }
        assertEquals(
            "these read raw shader text instead of going through GlUtil.loadShader",
            emptyList<String>(),
            offenders.map { it.first }.sorted(),
        )
    }

    @Test
    fun thePointSpritePathIsGone() {
        // The old style was a GL_POINTS sprite shaded from gl_PointCoord. It
        // was replaced, not kept alongside: leaving either spelling behind
        // would mean two particle looks fighting over the same uniforms.
        listOf("emergence_vert.glsl" to vertexSource, "emergence_frag.glsl" to fragmentSource).forEach { (name, src) ->
            listOf("gl_PointSize", "gl_PointCoord").forEach {
                assertTrue("$name still uses the old point-sprite builtin $it", !src.contains(it))
            }
        }
        assertTrue(
            "EmergenceScene still issues a GL_POINTS draw",
            !baseSource.contains("GL_POINTS"),
        )
    }

    private fun attributes(shader: String): List<Attribute> =
        Regex("""layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*in\s+(\w+)\s+(\w+)\s*;""")
            .findAll(shader)
            .map { m ->
                val type = m.groupValues[2]
                val floats =
                    when (type) {
                        "float" -> 1
                        "vec2" -> 2
                        "vec3" -> 3
                        "vec4" -> 4
                        else -> fail("unsupported attribute type $type").let { 0 }
                    }
                Attribute(m.groupValues[1].toInt(), floats, m.groupValues[3])
            }.sortedBy { it.location }
            .toList()

    private fun declaredUniforms(shader: String): Set<String> =
        Regex("""uniform\s+\w+\s+(\w+)\s*;""").findAll(shader).map { it.groupValues[1] }.toSet()

    /** The `location to components` pairs EmergenceScene.init binds. */
    private fun kotlinInstanceLayout(): List<Pair<Int, Int>> {
        val list =
            Regex("""val layout = listOf\(([^)]*)\)""").find(baseSource)?.groupValues?.get(1)
                ?: fail("no `val layout = listOf(...)` in EmergenceScene.init").let { "" }
        return Regex("""(\d+)\s+to\s+(\d+)""")
            .findAll(list)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
    }

    /** Drops `//` and block comments; GLSL and Kotlin agree on both forms. */
    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    private fun rawShader(name: String): String = repoFile("src/main/res/raw/$name")

    private fun source(relative: String): String = repoFile("src/main/java/dev/geode/$relative")

    /**
     * The `//#include` directives of a raw shader, in order. Deliberately the
     * same anchored pattern `GlUtil.INCLUDE_PATTERN` uses: a directive the
     * resolver would not act on must not count as one here either.
     */
    private fun includesOf(name: String): List<String> =
        Regex("""^[ \t]*//#include[ \t]+(\w+)[ \t]*$""", RegexOption.MULTILINE)
            .findAll(rawShader(name))
            .map { it.groupValues[1] }
            .toList()

    /** Every main source that reads a shader, as `file name to text`. */
    private fun shaderLoadingSources(): List<Pair<String, String>> =
        repoDir("src/main/java/dev/geode")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .filter { (_, src) -> src.contains("fun loadRaw(") }
            .toList()

    /** Resolves a directory under `app/`, whichever directory tests run from. */
    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }

    /** Resolves a path under `app/` or `engine/scenes/`, whichever directory the tests run from. */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "engine/scenes/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
