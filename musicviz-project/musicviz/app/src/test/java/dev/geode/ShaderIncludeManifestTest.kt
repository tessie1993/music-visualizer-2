package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `GlUtil.resolveIncludes` throws on an unknown include, which is the right
 * behaviour and the wrong moment: it throws on the GL thread, on a device,
 * the first time somebody selects that scene. A typo in a rarely-picked
 * shader ships, and the report is a blank visual rather than a build failure.
 *
 * Everything below is decidable from the files alone, so it belongs in a
 * build rather than in a bug report.
 *
 * Scope note: this reads `app/src/main/res/raw` and `GlUtil.kt` because that
 * is where the shaders and the include registry both live today. When
 * `LEGACY_DISPOSITION.md`'s GLSL row moves them to `:engine:scenes`, this
 * moves with them — a check that stays behind while its subject leaves is the
 * failure mode `BASELINE.md` §3 catalogues.
 */
class ShaderIncludeManifestTest {
    private val glUtil = File(ParamSurface.moduleRoot, "app/src/main/java/dev/geode/render/scene/GlUtil.kt")

    private fun shaders(): List<File> = ShaderSources.all()

    private fun includesIn(file: File): List<String> = ShaderSources.includesIn(file)

    /** Library names `GlUtil.INCLUDES` maps, read from its source. */
    private fun registered(): Set<String> =
        Regex(""""(\w+)" to R\.raw\.\w+""")
            .findAll(glUtil.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `this test checks the pattern the resolver actually uses`() {
        // If the resolver's pattern changes, everything below is measuring
        // something else. Fail here rather than quietly pass over the gap.
        assertTrue(
            "GlUtil.INCLUDE_PATTERN no longer matches the pattern this test parses with",
            glUtil.readText().contains("""Regex("^[ \\t]*//#include[ \\t]+(\\w+)[ \\t]*$", RegexOption.MULTILINE)"""),
        )
    }

    @Test
    fun `every include a shader asks for is registered`() {
        val known = registered()
        val unknown =
            shaders().flatMap { file ->
                includesIn(file).filterNot { it in known }.map { "${file.name}: $it" }
            }
        assertEquals(
            "an unregistered include throws on the GL thread the first time that scene is chosen",
            emptyList<String>(),
            unknown,
        )
    }

    @Test
    fun `no library nests an include the resolver would not expand`() {
        // resolveIncludes is deliberately one level: a library's own directive
        // is inserted verbatim and never rescanned. GLSL treats the leftover
        // `//#include` as a comment, so the shader compiles and the function it
        // needed is simply absent - which surfaces as a missing symbol on one
        // device family and nowhere else.
        val nested =
            shaders()
                .filter { it.name.startsWith("lib_") }
                .flatMap { file -> includesIn(file).map { "${file.name}: $it" } }
        assertEquals("the include resolver does not recurse", emptyList<String>(), nested)
    }

    @Test
    fun `every registered library is pulled in by something`() {
        val used = shaders().flatMap { includesIn(it) }.toSet()
        assertEquals(
            "a registered library nothing includes is dead weight in the APK",
            emptySet<String>(),
            registered() - used,
        )
    }

    @Test
    fun `no shader uses a preprocessor include GLSL ES does not have`() {
        // `//#include` is this app's convention precisely because `#include`
        // is not in GLSL ES 3.0. An uncommented one fails at driver compile.
        val real =
            shaders()
                .filter { Regex("""^[ \t]*#include\b""", RegexOption.MULTILINE).containsMatchIn(it.readText()) }
                .map { it.name }
        assertEquals(emptyList<String>(), real)
    }

    @Test
    fun `braces balance in every shader`() {
        // The only syntax check available without a GLSL compiler, and it
        // earns its place on one failure mode: a bad merge resolution that
        // truncates or doubles a block. Comments are stripped first because
        // several shaders discuss braces in prose; GLSL has no string
        // literals, so nothing else can hide one.
        val unbalanced =
            shaders().mapNotNull { file ->
                val code =
                    file
                        .readText()
                        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
                        .replace(Regex("""//[^\n]*"""), " ")
                val depth = code.count { it == '{' } - code.count { it == '}' }
                if (depth == 0) null else "${file.name}: $depth"
            }
        assertEquals(emptyList<String>(), unbalanced)
    }
}
