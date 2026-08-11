package dev.musicviz

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
 * `FlowField` and `ProjectMScene` each own a single special-purpose target and
 * are listed as known exceptions rather than silently ignored.
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
                "render/scene/ProjectMScene.kt" to "the FBO handed to libprojectM's native renderer",
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
    fun `context loss drops trail handles without deleting them`() {
        // release() on names from a dead context is only safe while nothing new
        // has been allocated; by the time the trail buffer is reached the names
        // may belong to someone else's object. onSurfaceCreated must forget().
        val body = functionBody(source("render/VisualizerRenderer.kt"), "override fun onSurfaceCreated")
        assertTrue("onSurfaceCreated must forget() the trail target, not release() it", body.contains("trail.forget()"))
    }

    /** Every main-source Kotlin file, keyed by its path below `dev/musicviz/`. */
    private fun mainSources(): List<Pair<String, String>> {
        val root = repoDir("src/main/java/dev/musicviz")
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    private fun source(relative: String): String =
        File(repoDir("src/main/java/dev/musicviz"), relative).also {
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
