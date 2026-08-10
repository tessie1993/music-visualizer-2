package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * One rule, for every style there is: a shader the driver rejects degrades
 * that style to "unavailable". It never throws.
 *
 * The rule is not a preference, it is the renderer's lifecycle. Every scene is
 * constructed and `init`ed inside `VisualizerRenderer.onSurfaceCreated`,
 * before the user has picked any of them, so an exception out of one init()
 * leaves onSurfaceCreated, leaves GLThread.run, and takes the process with it.
 * One particle shader that some GPU dislikes is therefore not one dead style -
 * it is a visualizer that crashes on every launch, taking the other
 * thirty-five with it. Ten scenes wrote the catch by hand and two
 * (ParticleSceneBase, ProjectMScene) did not; this file is what makes the
 * omission fail the build instead of a stranger's phone.
 *
 * Source-level, because a unit test has no GL context to reject anything with:
 * the same approach [ParticleStyleTest] takes to the CPU/GPU contract it
 * cannot run either.
 */
class SceneFailureTest {
    private companion object {
        /** The two that used to throw; named so a rewrite cannot lose them. */
        val ONCE_BROKEN = listOf("ParticleSceneBase.kt", "ProjectMScene.kt")

        /** How many code lines a `draw` may spend before it checks itself. */
        const val GUARD_WINDOW = 3
    }

    /** Every Scene implementation under `render/`, as `file name to source`. */
    private val sceneSources: List<Pair<String, String>> by lazy {
        repoDir("src/main/java/dev/musicviz/render")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            // The whole interface, so a class that merely mentions a Scene is
            // not mistaken for one.
            .filter { (_, src) ->
                src.contains("override fun init()") &&
                    src.contains("override fun draw(") &&
                    src.contains("override fun release()")
            }.toList()
            .sortedBy { it.first }
    }

    @Test
    fun theScanFindsTheScenes() {
        // Everything below is "no file breaks the rule", which a scan that
        // found nothing would also satisfy.
        assertTrue("no Scene implementations found under render/", sceneSources.size >= 9)
        assertEquals(
            "the scenes this file exists for are no longer being scanned",
            emptyList<String>(),
            ONCE_BROKEN.filterNot { name -> sceneSources.any { it.first == name } },
        )
    }

    @Test
    fun everySceneThatBuildsAProgramCatchesTheDriverRejectingIt() {
        // `GlUtil.buildProgramReporting` carries the catch for the scenes
        // that build through it (the next test pins that); a raw
        // `GlUtil.buildProgram(` call still needs a catch of its own.
        val offenders =
            sceneSources
                .filter { (_, src) -> src.contains("GlUtil.buildProgram(") }
                .filterNot { (_, src) -> src.contains("catch (e: GlUtil.ShaderCompileException)") }
                .map { it.first }
        assertEquals(
            "these scenes let a driver-rejected shader out of init() and into GLThread.run",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theSharedReportingBuilderCatchesTheRejectionItself() {
        // The scenes that delegate their catch to GlUtil.buildProgramReporting
        // are only safe while the helper actually catches and reports; losing
        // that catch would re-arm the launch crash for all of them at once.
        val glUtil = repoDir("src/main/java/dev/musicviz/render").resolve("scene/GlUtil.kt").readText()
        assertTrue(
            "GlUtil.buildProgramReporting no longer catches ShaderCompileException and reports it",
            Regex("""fun buildProgramReporting[\s\S]*?catch \(e: ShaderCompileException\) \{\s*onError\(e\.message\)""")
                .containsMatchIn(glUtil),
        )
    }

    @Test
    fun aSceneThatFailedToBuildCannotThenDraw() {
        // Catching is only half of it: a scene that swallowed the failure and
        // then drew would bind program 0 and an unfilled VAO, which is a
        // silently black style at best and undefined behaviour at worst. Every
        // scene answers this the same way - an availability flag checked
        // before anything is bound.
        val offenders =
            sceneSources
                .filterNot { (_, src) -> drawOpensWithAGuard(src) }
                .map { it.first }
        assertEquals(
            "these scenes draw without first checking that they have anything to draw with",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun everySceneWithAnErrorChannelSaysWhyItWentDark() {
        // Silent black is the worst failure mode - the user picks a style and
        // gets nothing, with no way to tell a broken GPU from a broken app.
        // Scenes that have somewhere to report to must report.
        val offenders =
            sceneSources
                .filter { (_, src) -> src.contains("onShaderError") || src.contains("onError") }
                .flatMap { (name, src) -> shaderFailureBlocks(src).map { name to it } }
                .filterNot { (_, block) -> block.contains("onShaderError(") || block.contains("onError(") }
                .map { it.first }
                .distinct()
        assertEquals(
            "these scenes have an error channel and go dark without using it",
            emptyList<String>(),
            offenders,
        )
    }

    /** True when `draw` reaches a `... return` guard before it touches GL. */
    private fun drawOpensWithAGuard(source: String): Boolean {
        val lines = source.lines()
        val start = lines.indexOfFirst { it.contains("override fun draw(") }
        if (start < 0) return false
        return lines
            .drop(start + 1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
            .take(GUARD_WINDOW)
            .any { Regex("""^if \(.+\) return$""").containsMatchIn(it) }
    }

    /** The body of every `catch (e: GlUtil.ShaderCompileException) { ... }`. */
    private fun shaderFailureBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = source.indexOf("catch (e: GlUtil.ShaderCompileException) {", from)
            if (start < 0) return blocks
            var depth = 0
            var i = source.indexOf('{', start)
            val open = i
            while (i < source.length) {
                if (source[i] == '{') depth++
                if (source[i] == '}') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            blocks += source.substring(open, minOf(i + 1, source.length))
            from = i + 1
        }
    }

    /** Resolves a directory under `app/`, whichever directory tests run from. */
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
