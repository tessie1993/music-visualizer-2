package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Offscreen colour targets are allocated in one place.
 *
 * There used to be four hand-written copies of the same thirty lines - the live
 * renderer's two scene targets and its trail buffer, the export compositor's
 * scene target and its trail buffer - and they had drifted: two checked
 * `glCheckFramebufferStatus` and two did not, one cleared the texture and three
 * left its contents to the driver. None of that is visible in review, and all of
 * it is the kind of thing that only shows up on one vendor's driver.
 *
 * [RenderTarget] is now the single owner for that shape, so the check and the
 * clear happen everywhere by construction.
 *
 * The simulation buffers are deliberately NOT in scope. `FluidBuffers` owns a
 * different thing - ping-pong pairs and MRT at probed half-float formats, with
 * an empirical renderability cascade - and folding two unrelated allocators into
 * one would be the shallow-module coupling the repo's own rules warn about.
 * `FlowField` owns a single special-purpose target and is listed as a known
 * exception rather than silently ignored. (The MilkDrop scene no longer
 * allocates a framebuffer at all: the stock engine renders on framebuffer 0
 * and the scene copies the frame into a plain texture.)
 */
class RenderTargetOwnershipTest {
    private companion object {
        /**
         * Files allowed to call `glGenFramebuffers` directly, and why. Adding a
         * line here is a design decision; the default answer for a new offscreen
         * colour target is [RenderTarget].
         */
        val ALLOWED: Map<String, String> =
            mapOf(
                "render/RenderTarget.kt" to "the shared owner itself",
                "render/fluid/FluidBuffers.kt" to
                    "ping-pong and MRT at probed half-float formats - a different allocator, not this one",
                "render/fluid/FlowField.kt" to "its own velocity-field ping-pong at field resolution",
                "render/scene/EmergenceScene.kt" to
                    "the acid feedback ping-pong: its history must survive the composite's own clears",
            )

        /**
         * The call sites that bind what they allocate, so must check it.
         *
         * `onSurfaceChanged` is deliberately absent: it pre-allocates so the
         * first frame after a resize is free of a multi-megabyte allocation, it
         * binds nothing itself, and `onDrawFrame` re-ensures every frame and
         * handles the failure. That is the one place where ignoring the result
         * is correct, and `preallocationRange` is how this test says so out
         * loud rather than by omission.
         */
        val ENSURE_CALLERS: Map<String, List<String>> =
            mapOf(
                "render/VisualizerRenderer.kt" to listOf("fboA.ensure(", "fboB.ensure(", "trail.ensure("),
                "export/FxCompositor.kt" to listOf("sceneTarget.ensure(", "trail.ensure("),
            )
    }

    @Test
    fun `only the shared owner and its declared exceptions allocate framebuffers`() {
        val offenders =
            mainSources()
                .filter { (_, text) -> text.contains("glGenFramebuffers") }
                .map { (path, _) -> path }
                .filterNot { it in ALLOWED.keys }
                .sorted()
        assertEquals(
            "these files allocate a framebuffer directly - use RenderTarget, or declare the exception " +
                "in ALLOWED with a reason",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the composite pipeline's targets all go through the shared owner`() {
        // The four sites this consolidated, named so that reintroducing a
        // hand-rolled one in either file fails here.
        for (path in listOf("render/VisualizerRenderer.kt", "export/FxCompositor.kt")) {
            val text = source(path)
            assertTrue("$path no longer uses RenderTarget", text.contains("RenderTarget("))
            assertTrue(
                "$path allocates a colour texture by hand again - RenderTarget owns that shape",
                !text.contains("glFramebufferTexture2D"),
            )
        }
    }

    @Test
    fun `the shared owner checks completeness and clears`() {
        // The two things the drifted copies disagreed about. Both must be in the
        // one implementation, or consolidating them achieved nothing.
        val text = source("render/RenderTarget.kt")
        assertTrue("RenderTarget must check framebuffer completeness", text.contains("glCheckFramebufferStatus"))
        assertTrue("RenderTarget must clear a freshly allocated target", text.contains("GL_COLOR_BUFFER_BIT"))
    }

    @Test
    fun `every ensure result is acted on`() {
        // ensure() returning false zeroes the handles, so a caller that ignores
        // it binds framebuffer 0 - the screen, or the encoder surface - and
        // samples texture 0. That is strictly worse than the unchecked
        // allocation this replaced, where an incomplete framebuffer at least
        // made the draws no-ops. So every call site that goes on to bind the
        // target must consume the Boolean.
        val offenders =
            ENSURE_CALLERS.flatMap { (path, calls) ->
                val text = source(path)
                val exempt = preallocationRange(text)
                calls.flatMap { unconsumed(text, it, exempt) }.map { "$path: $it" }
            }
        assertEquals(
            "these call sites ignore ensure()'s result - an unusable target would be bound as " +
                "framebuffer 0, which is the screen or the encoder surface",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `context loss drops trail handles without deleting them`() {
        // release() on names from a dead context is only safe while nothing new
        // has been allocated; by the time the trail buffer is reached the names
        // may belong to someone else's object. onSurfaceCreated must forget().
        val body = functionBody(source("render/VisualizerRenderer.kt"), "override fun onSurfaceCreated")
        assertTrue("onSurfaceCreated must forget() the trail target, not release() it", body.contains("trail.forget()"))
    }

    /** A result is consumed by a guard, a check, an assignment or a return. */
    private val consumed = Regex("""(if\s*\(|check\(|require\(|val\s+\w+\s*=|var\s+\w+\s*=|return\s)""")

    /** Character range of the pre-allocation function, or empty if there is none. */
    private fun preallocationRange(text: String): IntRange {
        val at = text.indexOf("override fun onSurfaceChanged")
        if (at < 0) return IntRange.EMPTY
        val body = functionBody(text, "override fun onSurfaceChanged")
        val start = text.indexOf(body, at)
        return start until (start + body.length)
    }

    /** Every occurrence of [call] outside [exempt] whose result is discarded. */
    private fun unconsumed(
        text: String,
        call: String,
        exempt: IntRange,
    ): List<String> {
        val found = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf(call, from)
            if (at < 0) return found
            from = at + call.length
            val line = text.substring(text.lastIndexOf('\n', at) + 1, text.indexOf('\n', at)).trim()
            if (at !in exempt && !consumed.containsMatchIn(line)) found += line
        }
    }

    /** Every main-source Kotlin file, keyed by its path below `dev/geode/`. */
    private fun mainSources(): List<Pair<String, String>> {
        val root = repoDir("src/main/java/dev/geode")
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    private fun source(relative: String): String =
        File(repoDir("src/main/java/dev/geode"), relative).also {
            if (!it.isFile) fail("$relative not found")
        }.readText()

    /** The body of [signature]'s function, by brace matching from its `{`. */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val at = source.indexOf(signature)
        if (at < 0) fail("$signature not found")
        var depth = 0
        var i = source.indexOf('{', at)
        val start = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        fail("unbalanced braces after $signature")
        error("unreachable")
    }

    /** Resolves a directory under `app/`, whichever directory the tests run from. */
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
}
