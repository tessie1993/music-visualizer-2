package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The particle-look shader contract of the fluid styles' lifecycle layer,
 * which nothing else can catch: a uniform name no shader declares, or a
 * library include that stops resolving, produces NO GL error at all - the
 * sprites simply render wrong (or not at all) on a device with a green build
 * behind it.
 *
 * These are source-level checks - unit tests have no GL context - in the same
 * spirit as [ParticleGatingTest] reading its gating back out of the UI source.
 */
class ParticleStyleTest {
    private companion object {
        /** The shader libraries the particle look is shared through. */
        val LIBRARIES = listOf("lib_particle_common", "lib_particle_shade")

        /** Stages that must NOT take the fwidth-based shading library. */
        val VERTEX_STAGES = listOf("fluid_particle_vert.glsl")

        val FRAGMENT_STAGES = listOf("fluid_particle_frag.glsl")
    }

    // Comments are stripped everywhere: all of these checks are about what the
    // code DOES, not what it says.
    @Test
    fun theFluidLayerShadesThroughTheSharedChunk() {
        // One look, one implementation. If the layer grows its own copy of the
        // shading, "the same Particle shape looks different on Fluid" - which
        // nothing else in the suite would catch.
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
            (VERTEX_STAGES + FRAGMENT_STAGES).associateWith { stripComments(rawShader(it)) }
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
        // The shared look arrives through `//#include`, and a library is only
        // substituted if `GlUtil.INCLUDES` registers it. Both halves are
        // pinned here because the failure modes are invisible: an
        // unregistered name throws at compile time on a device, and a stage
        // that calls the shared look without naming its library compiles to
        // "undefined function" nowhere a unit test would otherwise look.
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

    private fun includesOf(shaderName: String): List<String> =
        Regex("""//#include\s+(\w+)""")
            .findAll(rawShader(shaderName))
            .map { it.groupValues[1] }
            .toList()

    private fun stripComments(src: String): String =
        src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** Kotlin sources that load shader text, paired with their content. */
    private fun shaderLoadingSources(): List<Pair<String, String>> {
        val root = mainSourceRoot()
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .filter { (_, src) -> src.contains("R.raw.") }
            .toList()
    }

    private fun rawShader(name: String): String {
        for (rel in listOf(
            "src/main/res/raw",
            "app/src/main/res/raw",
            "../engine/scenes/src/main/res/raw",
            "engine/scenes/src/main/res/raw",
        )) {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "$rel/$name")
                if (candidate.isFile) return candidate.readText()
                dir = dir.parentFile
            }
        }
        fail("shader $name not found from ${File("").absolutePath}")
        error("unreachable")
    }

    private fun source(relative: String): String {
        val file = File(mainSourceRoot(), relative)
        assertTrue("missing source $relative", file.isFile)
        return file.readText()
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "${prefix}src/main/java/dev/geode")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        fail("main source root not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
