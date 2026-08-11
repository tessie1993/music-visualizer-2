package dev.musicviz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A shader that never compiled must never become "the" shader.
 *
 * `activeCustomShaders` is not a draft buffer. It is the source that gets
 * re-pushed into every fresh GL context after the EGL context is lost, baked
 * into export scenes, and saved into presets and shared preset links. It used
 * to be written by `submitShader` on the *submitting* thread, before the GL
 * thread had any chance to compile the text.
 *
 * That produced a defect with a very confusing shape: type a broken shader and
 * nothing appears to happen, because `ShaderScene` correctly keeps the last
 * working program. Background the app; the context dies and takes that working
 * program with it. On resume the only record of what this style should look
 * like is the broken source, so the style comes back **permanently black** -
 * and the same broken text is now what presets save and what exports render.
 *
 * So the map is written from the GL thread, on the far side of a successful
 * link, and never from the submitting thread. `ShaderScene` already knows how
 * to keep the previous program on failure; the renderer now records at the same
 * moment rather than optimistically ahead of it.
 *
 * Source-level: compiling GLSL needs a GL context, but *when* the record is
 * written is a fact about the code, in the style of [RendererWiringTest].
 */
class CustomShaderLastGoodSourceTest {
    private val renderer: String by lazy { repoFile("src/main/java/dev/musicviz/render/VisualizerRenderer.kt") }
    private val shaderScene: String by lazy { repoFile("src/main/java/dev/musicviz/render/scene/ShaderScene.kt") }

    /** `fun submitShader(...) { ... }` - the off-thread entry point. */
    private val submitShaderBody: String by lazy { functionBody(renderer, "fun submitShader(") }

    /** `private fun compilePendingIfAny() { ... }` - the GL-thread compile. */
    private val compileBody: String by lazy { functionBody(shaderScene, "private fun compilePendingIfAny()") }

    @Test
    fun submitShaderOnlyQueuesAndDoesNotRecordTheSource() {
        assertTrue("submitShader must still queue the source", submitShaderBody.contains("pendingCustomShaders"))
        assertFalse(
            "submitShader must not write activeCustomShaders: it runs off the GL thread, " +
                "before the source is known to compile, and that map survives context loss",
            submitShaderBody.contains("activeCustomShaders"),
        )
    }

    @Test
    fun theRecordIsWrittenFromACompileSuccessCallback() {
        // The one remaining writer is the callback the ShaderScene invokes
        // after a successful link.
        val writes = Regex("""activeCustomShaders\[[^\]]+\]\s*=""").findAll(renderer).count()
        assertTrue("expected exactly one writer of activeCustomShaders, found $writes", writes == 1)
        assertTrue(
            "the single write must be inside the ShaderScene's compiled-source callback",
            Regex("""onUserSourceCompiled\s*=\s*\{[^}]*activeCustomShaders\[""").containsMatchIn(renderer),
        )
    }

    @Test
    fun theCallbackFiresOnlyAfterTheLinkSucceeded() {
        // Ordering inside compilePendingIfAny: the bail-out on a failed link
        // must come before the success notification, or the callback fires for
        // source that did not compile and we are back where we started.
        val bail = compileBody.indexOf("if (newProgram == 0) return")
        val notify = compileBody.indexOf("onUserSourceCompiled(")
        assertTrue("compilePendingIfAny no longer bails out on a failed link", bail >= 0)
        assertTrue("compilePendingIfAny never notifies a successful compile", notify >= 0)
        assertTrue("the success callback fires before the failed-link bail-out", bail < notify)
    }

    @Test
    fun onlyUserSubmittedSourceIsRecorded() {
        // Every ShaderScene compiles its BUILT-IN source on first frame and
        // again after each context loss. Recording those would make every
        // shader style look user-edited, defeat "null if unedited", and bake
        // the built-in text into presets and exports.
        assertTrue(
            "setFragmentSource must mark the queued source as user-submitted",
            Regex("""fun setFragmentSource[\s\S]{0,200}?pendingIsUserSource\s*=\s*true""")
                .containsMatchIn(shaderScene),
        )
        assertTrue(
            "the success callback must be gated on the source having come from setFragmentSource",
            Regex("""if\s*\(\s*fromUser\s*\)\s*onUserSourceCompiled\(""").containsMatchIn(compileBody),
        )
        assertFalse(
            "init() re-queues the current source after context loss; it must not mark it user-submitted, " +
                "or a recompile would re-record source that is already recorded",
            functionBody(shaderScene, "override fun init()").contains("pendingIsUserSource = true"),
        )
    }

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
