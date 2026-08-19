package dev.geode

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The framebuffer discipline of the four field-sim scenes, read out of the
 * source the way [RendererWiringTest] reads the renderer's: these contracts
 * only exist on the GL thread, so no JVM test can execute them, and every one
 * of them was broken in a way no crash would name — the screen just went
 * black while every suite stayed green.
 *
 * Three rules, each pinned because its violation shipped:
 *
 * 1. **Capture the restore target before anything can rebind it.**
 *    `FluidBuffers.probeFormats()` and `Fbo.create()` both leave framebuffer
 *    0 bound, so a `GL_DRAW_FRAMEBUFFER_BINDING` capture taken after
 *    `ensure*()` records the screen instead of the renderer's scene target on
 *    exactly the frames that allocate — the show pass then misses `fboA` and
 *    the composite presents black (once per style entry and resize, and, with
 *    rule 2 broken, every census).
 *
 * 2. **A readback must not disturb the draw binding.** `LifeScene.census()`
 *    used to bind `GL_FRAMEBUFFER` — both targets — and never restore it, so
 *    the present pass rendered the palette output INTO the simulation state
 *    it was sampling: a black frame and a corrupted organism every four
 *    seconds. Reads go through `GL_READ_FRAMEBUFFER`, and the binding is
 *    restored before the function returns.
 *
 * 3. **Read in the always-legal format, not the implementation's favourite.**
 *    ES 3.0 §4.3.2 guarantees RGBA/FLOAT readback for float color buffers and
 *    RGBA/UNSIGNED_BYTE for normalized ones; `IMPLEMENTATION_COLOR_READ_*` is
 *    an *additional* pair, not a gate. Gating the census on it meant the
 *    reseed safety net never ran on the RGBA8 fallback path (whose preferred
 *    type is never FLOAT), so a starved world stayed black forever on exactly
 *    the devices §6.3's fallback exists for.
 *
 * Plus the fallback completeness rule dd6612b stated but only half applied:
 * every state texture a field-sim scene cannot exist without needs a
 * renderable fallback — `MycoScene`'s agents had none, so ten styles were
 * permanently black wherever float targets don't render.
 */
class FieldSimFboContractTest {
    private companion object {
        val SCENES =
            listOf(
                "SilkScene" to "ensureDye",
                "LifeScene" to "ensureState",
                "AcidScene" to "ensureState",
                "MycoScene" to "ensureBuffers",
            )

        const val CAPTURE = "glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING"
    }

    @Test
    fun `every field sim captures its restore target before allocating`() {
        for ((scene, ensure) in SCENES) {
            // Comments stripped: the capture sites document this very rule by
            // naming the ensure call, and prose must not shadow the code.
            val draw =
                functionBody(sceneSource(scene), "draw", scene)
                    .lineSequence()
                    .joinToString("\n") { it.substringBefore("//") }
            val captureAt = draw.indexOf(CAPTURE)
            val ensureAt = draw.indexOf("$ensure(")
            assertTrue("$scene.draw() never captures GL_DRAW_FRAMEBUFFER_BINDING", captureAt >= 0)
            assertTrue("$scene.draw() never calls $ensure()", ensureAt >= 0)
            assertTrue(
                "$scene.draw() captures the restore framebuffer AFTER $ensure(); " +
                    "allocation leaves framebuffer 0 bound, so the show pass misses the " +
                    "renderer's scene target on every allocating frame",
                captureAt < ensureAt,
            )
        }
    }

    @Test
    fun `the life census reads without touching the draw binding, and restores`() {
        val census = functionBody(sceneSource("LifeScene"), "census", "LifeScene")
        assertTrue(
            "census() must bind GL_READ_FRAMEBUFFER for its readback",
            census.contains("glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER"),
        )
        assertTrue(
            "census() must not bind GL_FRAMEBUFFER (both targets): that redirects the " +
                "present pass into the simulation state",
            !census.contains("glBindFramebuffer(GLES30.GL_FRAMEBUFFER"),
        )
        val binds = Regex("glBindFramebuffer\\(GLES30\\.GL_READ_FRAMEBUFFER").findAll(census).count()
        assertTrue(
            "census() must restore the read binding before returning (one bind, one restore; saw $binds)",
            binds >= 2,
        )
    }

    @Test
    fun `the life census reads the always-legal formats, not the preferred pair`() {
        val census = functionBody(sceneSource("LifeScene"), "census", "LifeScene")
        assertTrue(
            "census() must not gate on IMPLEMENTATION_COLOR_READ_*: it is an additional " +
                "pair, not a requirement, and the gate silenced the reseed net on every " +
                "byte-fallback device",
            !census.contains("GL_IMPLEMENTATION_COLOR_READ"),
        )
        assertTrue(
            "census() must read RGBA/FLOAT on the float path (always legal, ES 3.0 §4.3.2)",
            census.contains("GLES30.GL_FLOAT"),
        )
        assertTrue(
            "census() must read RGBA/UNSIGNED_BYTE on the byte-state path (always legal)",
            census.contains("GLES30.GL_UNSIGNED_BYTE"),
        )
    }

    @Test
    fun `the myco agent state has a byte fallback like every other field-sim state`() {
        val ensure = functionBody(sceneSource("MycoScene"), "ensureBuffers", "MycoScene")
        val agentBranch = ensure.substringBefore("trail == null")
        assertTrue(
            "MycoScene's agent texture needs an RGBA8 fallback when no float format is " +
                "renderable — without one, every Myco style is permanently black on " +
                "exactly the devices §6.3's named-fallback rule exists for",
            agentBranch.contains("GL_RGBA8"),
        )
    }

    /** The named scene's source text. */
    private fun sceneSource(name: String): String = repoFile("src/main/java/dev/geode/render/scene/$name.kt")

    /**
     * The body of `fun [name]` in [source] — the brace-matched form of
     * [RendererWiringTest.functionBody], parameterized on the file.
     */
    private fun functionBody(
        source: String,
        name: String,
        where: String,
    ): String {
        val declaration =
            Regex("""^ {4}(?:private |internal |override |protected )*fun $name\b""", RegexOption.MULTILINE)
                .find(source)
        if (declaration == null) {
            fail("$where has no function named $name")
            error("unreachable")
        }
        var i = declaration.range.last
        var parens = 0
        var opened = false
        while (i < source.length && !(opened && parens == 0)) {
            when (source[i]) {
                '(' -> {
                    parens++
                    opened = true
                }
                ')' -> parens--
                else -> Unit
            }
            i++
        }
        val brace = source.indexOf('{', i)
        if (brace < 0) {
            val blank = source.indexOf("\n\n", i)
            return source.substring(declaration.range.first, if (blank < 0) source.length else blank)
        }
        var depth = 0
        var j = brace
        while (j < source.length) {
            if (source[j] == '{') depth++
            if (source[j] == '}' && --depth == 0) break
            j++
        }
        return source.substring(declaration.range.first, minOf(j + 1, source.length))
    }

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
