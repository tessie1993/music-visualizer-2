package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The MilkDrop integration's load-bearing shape, pinned at the source level —
 * the same approach [SceneFailureTest] takes, because none of this can run
 * without a device GL context and a native engine, and every defect below
 * shipped as a silent black MilkDrop rather than as anything a suite saw.
 *
 * ## The design under guard
 *
 * The engine is STOCK projectM v4.1.7: `projectm_opengl_render_frame` ends
 * its frame on the DEFAULT framebuffer, the only target where upstream's
 * `glDrawBuffers(GL_BACK)` is legal, and `MilkdropScene` copies the frame off
 * framebuffer 0 into its own texture for the post pass.
 *
 * The previous integration instead patched a render-to-FBO API onto the
 * engine, and the patch went stale twice — once linking the bridge against a
 * declared-but-undefined symbol, once leaving `GL_BACK` set on a framebuffer
 * object — each time shipping a MilkDrop that was permanently black while
 * every other style worked. The rebuild's premise is that there is NO patch
 * to go stale, so what this file holds is exactly that:
 *
 * 1. **No engine patches exist.** `tools/` carries no `.patch`, and the
 *    native-libs workflow has no apply step: the committed `.so` is buildable
 *    from the upstream tag alone.
 * 2. **The bridge speaks only the stock C API.** A render-to-FBO entry point
 *    reappearing in `milkdrop_jni.c` means someone is re-growing the patched
 *    engine without the patch, which links fine and dies at JNI link time on
 *    a device.
 * 3. **The scene copies the frame off framebuffer 0** after the native
 *    render — the copy IS the integration; without it the engine paints the
 *    back buffer and the composite immediately paints over it (black
 *    MilkDrop, no error anywhere).
 * 4. **The scene drains latched GL errors after the native render**, so a
 *    recoverable error a preset raised inside the engine cannot masquerade
 *    as some later call's failure.
 */
class MilkdropIntegrationTest {
    private val toolsDir: File by lazy { repoRoot().resolve("tools") }

    private val bridge: String by lazy { toolsDir.resolve("milkdrop_jni.c").readText() }

    private val scene: String by lazy {
        repoRoot()
            .resolve("app/src/main/java/dev/geode/render/scene/MilkdropScene.kt")
            .readText()
    }

    @Test
    fun `no engine patch exists anywhere in tools`() {
        val patches =
            toolsDir
                .listFiles { f: File -> f.name.endsWith(".patch") }
                .orEmpty()
                .map { it.name }
                .sorted()
        assertEquals(
            "the stock-engine design has no patch to go stale — a .patch in tools/ " +
                "means the render-to-FBO approach is being regrown; it shipped a " +
                "black MilkDrop twice",
            emptyList<String>(),
            patches,
        )
    }

    @Test
    fun `the native build applies nothing to the upstream clone`() {
        val workflowFile =
            generateSequence(repoRoot()) { it.parentFile }
                .map { File(it, ".github/workflows/native-libs.yml") }
                .firstOrNull { it.isFile }
        val workflow = checkNotNull(workflowFile) { "native-libs.yml not found above ${repoRoot()}" }.readText()
        assertFalse(
            "native-libs.yml must build the projectM tag stock — an apply step " +
                "means the committed .so is not what the tag builds",
            workflow.contains("git apply"),
        )
    }

    @Test
    fun `the bridge speaks only the stock render API`() {
        assertTrue(
            "the bridge must render through stock projectm_opengl_render_frame",
            bridge.contains("projectm_opengl_render_frame((projectm_handle)"),
        )
        assertFalse(
            "projectm_opengl_render_frame_fbo is not in any projectM release — " +
                "calling it links fine and throws UnsatisfiedLinkError on a device",
            bridge.contains("projectm_opengl_render_frame_fbo"),
        )
    }

    @Test
    fun `the scene lifts the frame off framebuffer 0 after the native render`() {
        val draw = scene.substringAfter("override fun draw(")
        val render = draw.indexOf("nativeRender(")
        val bindRead = draw.indexOf("GL_READ_FRAMEBUFFER, 0)")
        val copy = draw.indexOf("glCopyTexSubImage2D")
        assertTrue("MilkdropScene.draw() must call the native render", render >= 0)
        assertTrue("the copy must read from the DEFAULT framebuffer", bindRead >= 0)
        assertTrue("MilkdropScene.draw() must copy the engine's frame into its texture", copy >= 0)
        assertTrue(
            "the read binding and copy must come AFTER the native render — before it " +
                "there is no frame on framebuffer 0 to lift",
            render < bindRead && bindRead < copy,
        )
    }

    @Test
    fun `the scene captures the renderer's target before anything can rebind`() {
        val draw = scene.substringAfter("override fun draw(")
        val capture = draw.indexOf("GL_DRAW_FRAMEBUFFER_BINDING")
        val render = draw.indexOf("nativeRender(")
        val restore = draw.lastIndexOf("GL_DRAW_FRAMEBUFFER, prevFbo[0]")
        assertTrue("draw() must capture the bound draw framebuffer", capture >= 0)
        assertTrue(
            "the capture must precede the native render — the engine ends its frame " +
                "bound to framebuffer 0",
            capture < render,
        )
        assertTrue(
            "the renderer's framebuffer must be restored after the engine's",
            restore > render,
        )
    }

    @Test
    fun `the copy restores the read framebuffer it borrowed`() {
        val draw = scene.substringAfter("override fun draw(")
        val copy = draw.indexOf("glCopyTexSubImage2D")
        val capture = draw.indexOf("GL_READ_FRAMEBUFFER_BINDING")
        val restore = draw.indexOf("GL_READ_FRAMEBUFFER, prevReadFbo[0]")
        assertTrue("the READ binding must be captured before the copy borrows it", capture in 0 until copy)
        assertTrue(
            "the READ binding must be restored after the copy: the persistence pass and " +
                "the field sims read through GL_READ_FRAMEBUFFER later in the same frame, " +
                "and leaving it on 0 points them at the window",
            restore > copy,
        )
    }

    @Test
    fun `the scene drains latched GL errors after the native render`() {
        val draw = scene.substringAfter("override fun draw(")
        val render = draw.indexOf("nativeRender(")
        val drain = draw.indexOf("glGetError")
        assertTrue("MilkdropScene.draw() must drain glGetError after the native call", drain >= 0)
        assertTrue(
            "the drain must come AFTER the native render, where a preset-raised " +
                "error would otherwise stay latched into the post pass",
            drain > render,
        )
    }

    @Test
    fun `curl flow has the error channel the rest of the fluid family has`() {
        val curl =
            repoRoot()
                .resolve("app/src/main/java/dev/geode/render/fluid/CurlFlowScene.kt")
                .readText()
        assertTrue(
            "CurlFlowScene must declare onShaderError: it has three unavailability " +
                "paths and used to take all of them silently",
            curl.contains("var onShaderError"),
        )
        val renderer =
            repoRoot()
                .resolve("app/src/main/java/dev/geode/render/VisualizerRenderer.kt")
                .readText()
        val curlFactory = renderer.substringAfter("SceneIds.CURLFLOW ->").substringBefore("SceneIds.")
        assertTrue(
            "createScene must wire CurlFlowScene.onShaderError like its siblings'",
            curlFactory.contains("onShaderError"),
        )
    }

    /** Resolves the musicviz project root, whichever directory tests run from. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            // The app module's parent is the Gradle root that owns tools/.
            if (File(dir, "tools/milkdrop_jni.c").isFile) return dir
            if (File(dir, "app/../tools/milkdrop_jni.c").isFile) return File(dir, "app/..").canonicalFile
            dir = dir.parentFile
        }
        fail("musicviz project root not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
