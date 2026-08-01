package dev.musicviz

import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.ParticleSceneBase
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
    /** `layout(location = N) in <type> <name>;` in declaration order. */
    private data class Attribute(
        val location: Int,
        val floats: Int,
        val name: String,
    )

    // Comments are stripped everywhere: all of these checks are about what the
    // code DOES, and both files talk about the point-sprite path they replaced.
    private val vertexSource: String by lazy { stripComments(rawShader("particle_vert.glsl")) }
    private val fragmentSource: String by lazy { stripComments(rawShader("particle_frag.glsl")) }
    private val baseSource: String by lazy { stripComments(source("render/scene/ParticleSceneBase.kt")) }

    @Test
    fun instanceAttributesMatchTheKotlinVertexLayout() {
        val attrs = attributes(vertexSource)
        assertTrue("particle_vert.glsl declares no attributes", attrs.isNotEmpty())
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
            ParticleSceneBase.FLOATS_PER_PARTICLE,
            instanceAttrs.sumOf { it.floats },
        )
        // The Kotlin side describes the same records as `location to
        // components` pairs; keep the two spellings of the layout in step.
        assertEquals(
            "the `layout` list in ParticleSceneBase.init disagrees with particle_vert.glsl",
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
        assertTrue("particle_vert.glsl has no aVel attribute", instanceAttrs.any { it.name == "aVel" })
        assertEquals(
            "VELOCITY_OFFSET must index aVel inside one particle's record",
            offset,
            ParticleSceneBase.VELOCITY_OFFSET,
        )
        assertEquals("velocity is a vec2", 2, instanceAttrs.first { it.name == "aVel" }.floats)
    }

    @Test
    fun everyUploadedUniformIsDeclaredBySomeStage() {
        val uploaded = Regex("""loc\("(\w+)"\)""").findAll(baseSource).map { it.groupValues[1] }.toSortedSet()
        assertTrue("no uniform uploads found in ParticleSceneBase.kt", uploaded.isNotEmpty())
        val declared = declaredUniforms(vertexSource) + declaredUniforms(fragmentSource)
        assertEquals(
            "uniforms uploaded by ParticleSceneBase that no particle shader declares " +
                "(glGetUniformLocation returns -1 and the upload is silently dropped)",
            emptyList<String>(),
            uploaded.filterNot { it in declared },
        )
    }

    @Test
    fun everyParticleScenePublishesAVelocity() {
        // The billboards lean along it, so a scene that never writes the slot
        // renders permanently axis-aligned - visible only on a device.
        val scenes =
            listOf(
                "BurstScene",
                "FountainScene",
                "NebulaScene",
                "OrbitScene",
                "SwarmScene",
                "GalaxyScene",
                "AttractorScene",
                "StormScene",
                "InkflowScene",
            )
        val missing =
            scenes.filterNot { name ->
                stripComments(source("render/scene/$name.kt")).contains("VELOCITY_OFFSET")
            }
        assertEquals("particle scenes that never fill their velocity slot", emptyList<String>(), missing)
    }

    @Test
    fun bothFamiliesShadeThroughTheOneSharedChunk() {
        // The CPU styles and the fluid layer are two different pipelines with
        // one look. If either grows its own copy of the shading, they drift -
        // and the drift shows up as "the same Particle shape looks different
        // on Fluid", which nothing else in the suite would catch.
        val common = stripComments(rawShader("particle_common.glsl"))
        val shade = stripComments(rawShader("particle_shade.glsl"))
        listOf("particle_common.glsl" to common, "particle_shade.glsl" to shade).forEach { (name, src) ->
            assertTrue("$name must not carry its own #version", !src.contains("#version"))
        }
        // The common chunk is spliced into VERTEX stages too, where fwidth()
        // does not exist - a derivative call in it fails to compile on every
        // device, which is exactly the split the two files encode.
        assertTrue("particle_common.glsl must declare its own precision", common.contains("precision highp float;"))
        assertTrue("particle_common.glsl uses a fragment-only derivative", !common.contains("fwidth("))
        assertTrue("the shading belongs in particle_shade.glsl", shade.contains("fwidth("))
        listOf("ptShapeField", "ptRadiusFade", "ptBillboard", "ptAces").forEach {
            assertTrue("particle_common.glsl is missing $it", common.contains("$it("))
        }
        assertTrue("particle_shade.glsl is missing ptShade", shade.contains("ptShade("))
        val consumers =
            mapOf(
                "particle_frag.glsl" to fragmentSource,
                "particle_vert.glsl" to vertexSource,
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
    fun withChunkSplicesAfterTheVersionLine() {
        // `#version` must be the first thing in a GLSL ES shader, so a chunk
        // prepended instead of spliced would fail to compile on every device.
        val spliced = GlUtil.withChunk("#version 300 es\nvoid main() {}\n", "float helper();")
        assertTrue("version line moved", spliced.startsWith("#version 300 es\n"))
        assertTrue("chunk not spliced in", spliced.contains("float helper();"))
        assertTrue("chunk landed after the body", spliced.indexOf("helper") < spliced.indexOf("void main"))
        // Sources with no version line (the in-app GLSL editor accepts
        // anything) come back untouched rather than corrupted by a prepend.
        assertEquals("void main() {}", GlUtil.withChunk("void main() {}", "float helper();"))
    }

    @Test
    fun thePointSpritePathIsGone() {
        // The old style was a GL_POINTS sprite shaded from gl_PointCoord. It
        // was replaced, not kept alongside: leaving either spelling behind
        // would mean two particle looks fighting over the same uniforms.
        listOf("particle_vert.glsl" to vertexSource, "particle_frag.glsl" to fragmentSource).forEach { (name, src) ->
            listOf("gl_PointSize", "gl_PointCoord").forEach {
                assertTrue("$name still uses the old point-sprite builtin $it", !src.contains(it))
            }
        }
        assertTrue(
            "ParticleSceneBase still issues a GL_POINTS draw",
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

    /** The `location to components` pairs ParticleSceneBase.init binds. */
    private fun kotlinInstanceLayout(): List<Pair<Int, Int>> {
        val list =
            Regex("""val layout = listOf\(([^)]*)\)""").find(baseSource)?.groupValues?.get(1)
                ?: fail("no `val layout = listOf(...)` in ParticleSceneBase.init").let { "" }
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

    private fun source(relative: String): String = repoFile("src/main/java/dev/musicviz/$relative")

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
