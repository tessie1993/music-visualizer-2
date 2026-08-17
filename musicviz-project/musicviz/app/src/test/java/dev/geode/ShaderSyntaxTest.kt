package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Compiles every shader with a real GLSL ES 3.0 front-end.
 *
 * [ShaderIncludeManifestTest] checks the include manifest and balances braces,
 * which is all that is decidable without a compiler. It cannot see an
 * undeclared identifier, a type mismatch, a swizzle out of range or a wrong
 * argument count, and those reach a user as a scene that renders black on
 * whichever device first selects it.
 *
 * Includes are expanded first, because `//#include` is this app's own
 * convention and no compiler knows it. That makes this a check on the include
 * system too: a library that failed to expand shows up as the missing function
 * it should have provided.
 *
 * Skipped when `glslangValidator` is absent so a local build is not blocked.
 * CI installs it, and [the CI workflow installs the shader compiler] fails if
 * that step is dropped - otherwise this could stop running everywhere at once
 * and report nothing.
 */
class ShaderSyntaxTest {
    private val compiler: File? by lazy {
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { File(it, "glslangValidator") }
            .firstOrNull { it.canExecute() }
    }

    /** Compiler output when [source] is rejected, or null when it compiles. */
    private fun compileSource(
        source: String,
        stage: String,
    ): String? {
        val temp = File.createTempFile("shader", ".$stage").apply { deleteOnExit() }
        temp.writeText(source)
        val process =
            ProcessBuilder(compiler?.absolutePath, temp.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val rejected = process.waitFor() != 0
        temp.delete()
        return output.trim().takeIf { rejected }
    }

    @Test
    fun `every shader compiles as GLSL ES 300`() {
        assumeTrue("glslangValidator not on PATH; install glslang-tools", compiler != null)
        val failures =
            ShaderSources.standalone().mapNotNull { shader ->
                compileSource(ShaderSources.expand(shader), ShaderSources.stageOf(shader))
                    ?.let { "${shader.name}\n$it" }
            }
        assertEquals("shaders the compiler rejected:\n${failures.joinToString("\n")}", emptyList<String>(), failures)
    }

    @Test
    fun `the compiler harness rejects what it claims to catch`() {
        // Without this the pass above is indistinguishable from one that
        // compiles nothing and reports success. Fixtures rather than a real
        // shader edited in place: a plant that is not restored leaves a broken
        // scene in the tree, which is not a theoretical risk.
        assumeTrue("glslangValidator not on PATH; install glslang-tools", compiler != null)
        val body =
            """
            #version 300 es
            precision mediump float;
            out vec4 fragColor;
            void main() {
                %s
                fragColor = vec4(1.0);
            }
            """.trimIndent()
        val faults =
            mapOf(
                "undeclared identifier" to "float q = notDeclaredAnywhere;",
                "type mismatch" to "vec3 v = 1.0;",
                "swizzle out of range" to "vec2 a = vec2(0.0); float b = a.z;",
                "unknown function" to "float u = neverDefined(1.0);",
            )
        faults.forEach { (label, fault) ->
            assertNotNull("the compiler accepted a shader with a $label", compileSource(body.format(fault), "frag"))
        }
        assertEquals("the fixture itself must be valid", null, compileSource(body.format(""), "frag"))
    }

    @Test
    fun `the standalone set is every shader that is not a library`() {
        // Guards the split the compile pass rests on. A library has no
        // `#version` and no `main`, so compiling one alone would report a
        // failure that is not a defect; and a real shader misfiled as a
        // library would quietly leave the set.
        assertEquals(ShaderSources.all().size, ShaderSources.standalone().size + ShaderSources.libraries().size)
        assertTrue("no standalone shaders found", ShaderSources.standalone().isNotEmpty())
        ShaderSources.standalone().forEach {
            assertTrue("${it.name} declares no #version, so it is a library", it.readText().contains("#version 300 es"))
        }
        ShaderSources.libraries().forEach {
            assertTrue(
                "${it.name} carries a #version, but a library is pasted mid-file where that is invalid",
                !Regex("""^[ \t]*#version""", RegexOption.MULTILINE).containsMatchIn(it.readText()),
            )
        }
    }

    @Test
    fun `the CI workflow installs the shader compiler`() {
        val workflow = File(ParamSurface.moduleRoot, "../../.github/workflows/android.yml")
        assertTrue("android.yml not found at ${workflow.canonicalPath}", workflow.isFile)
        assertTrue(
            "CI no longer installs glslang-tools, so the compile pass silently skips",
            workflow.readText().contains("glslang-tools"),
        )
    }
}
