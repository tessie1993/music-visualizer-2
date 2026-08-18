package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The libprojectM render-to-FBO integration, pinned at the source level -
 * the same approach [SceneFailureTest] takes, because none of this can run
 * without a device GL context and a native engine, and every defect below
 * shipped as a silent black MilkDrop rather than as anything a suite saw.
 *
 * Three facts are held:
 *
 * 1. **One patch, complete.** `tools/` once held two FBO backport patches;
 *    the older one declared `projectm_opengl_render_frame_fbo` without
 *    patching `ProjectMCWrapper.cpp` to define it, so a build from it linked
 *    the JNI bridge against a symbol that did not exist - `available` said
 *    yes and the first render call took the GL thread down. Exactly one
 *    patch may exist and it must carry the wrapper definition.
 *
 * 2. **The GLES draw buffer follows the target.** Upstream projectM ends its
 *    frame with `glDrawBuffers(GL_BACK)`, which is only legal for the
 *    DEFAULT framebuffer. The backport binds a framebuffer OBJECT there
 *    instead, where a conformant driver rejects GL_BACK
 *    (GL_INVALID_OPERATION latched every frame) and a lenient one redirects
 *    the engine's final copy away from the scene's texture - MilkDrop
 *    permanently black while every other style works. The patch must select
 *    the draw buffer per target.
 *
 * 3. **The Kotlin side defends itself against a pre-fix engine.** The
 *    committed `.so` is rebuilt by CI, not by this repo's compiler, so the
 *    scene cannot assume the patch above is IN the binary it ships next to:
 *    it establishes COLOR_ATTACHMENT0 on its FBO before handing GL to the
 *    engine, and drains latched GL errors after the native call so a stale
 *    GL_INVALID_OPERATION cannot masquerade as some later call's failure.
 */
class NativeFboIntegrationTest {
    private val toolsDir: File by lazy { repoRoot().resolve("tools") }

    private val patch: String by lazy {
        toolsDir.resolve("projectm-v417-render-fbo-backport.patch").readText()
    }

    private val scene: String by lazy {
        repoRoot()
            .resolve("app/src/main/java/dev/geode/render/scene/ProjectMScene.kt")
            .readText()
    }

    @Test
    fun `exactly one fbo backport patch exists`() {
        val patches =
            toolsDir
                .listFiles { f: File -> f.name.endsWith(".patch") && f.name.contains("fbo") }
                .orEmpty()
                .map { it.name }
                .sorted()
        assertEquals(
            "tools/ must hold exactly the one working FBO backport - a second is " +
                "the incomplete-draft trap build-projectm.md describes",
            listOf("projectm-v417-render-fbo-backport.patch"),
            patches,
        )
    }

    @Test
    fun `the patch defines the wrapper it declares`() {
        assertTrue(
            "the patch must touch ProjectMCWrapper.cpp: a declaration without a " +
                "definition builds fine and dies at JNI link time",
            patch.contains("ProjectMCWrapper.cpp"),
        )
        val definitions =
            Regex("^\\+void projectm_opengl_render_frame_fbo", RegexOption.MULTILINE)
                .findAll(patch)
                .count()
        assertEquals("the wrapper definition must be added exactly once", 1, definitions)
        val declarations =
            Regex("^\\+PROJECTM_EXPORT void projectm_opengl_render_frame_fbo", RegexOption.MULTILINE)
                .findAll(patch)
                .count()
        assertEquals("the header declaration must be added exactly once", 1, declarations)
    }

    @Test
    fun `the patched GLES draw buffer follows the target framebuffer`() {
        assertTrue(
            "the USE_GLES block must select GL_COLOR_ATTACHMENT0 for framebuffer " +
                "objects - GL_BACK there is rejected by conformant drivers and " +
                "redirects the final copy on lenient ones (black MilkDrop)",
            patch.contains("targetFramebufferObject == 0 ? GL_BACK : GL_COLOR_ATTACHMENT0"),
        )
    }

    @Test
    fun `the scene establishes its draw buffer before the native render`() {
        val draw = scene.substringAfter("override fun draw(")
        val establish = draw.indexOf("glDrawBuffers")
        val render = draw.indexOf("nativeRenderToFbo")
        assertTrue("ProjectMScene.draw() must set its FBO's draw buffer explicitly", establish >= 0)
        assertTrue("ProjectMScene.draw() must call the native render", render >= 0)
        assertTrue(
            "the draw-buffer state must be established BEFORE the engine renders",
            establish < render,
        )
    }

    @Test
    fun `the scene drains latched GL errors after the native render`() {
        val draw = scene.substringAfter("override fun draw(")
        val render = draw.indexOf("nativeRenderToFbo")
        val drain = draw.indexOf("glGetError")
        assertTrue("ProjectMScene.draw() must drain glGetError after the native call", drain >= 0)
        assertTrue(
            "the drain must come AFTER the native render, where the pre-fix engine " +
                "latches GL_INVALID_OPERATION every frame",
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
            if (File(dir, "tools/pm_jni.c").isFile) return dir
            if (File(dir, "app/../tools/pm_jni.c").isFile) return File(dir, "app/..").canonicalFile
            dir = dir.parentFile
        }
        fail("musicviz project root not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
