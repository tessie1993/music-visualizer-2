package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The module graph from `MASTER_PLAN.md` §4.1, asserted rather than described.
 *
 * Two of the boundaries enforce themselves: `audio-core` and `visual-core` are
 * `java-library` modules, so `android.*` is not on their compile classpath and
 * an import of it does not build. That is the whole argument for extracting
 * modules at all — `ENGINE_V2_PLAN.md` §1 traces it to two files in
 * `analysis/` that drifted into importing `android.*` under a package
 * convention with no way to stop them.
 *
 * The rest do not enforce themselves. Nothing in Gradle stops `:engine:scenes`
 * from reaching into `PlayerViewModel`, or `:app` from depending on
 * `:engine:gl` directly and bypassing the runtime's narrow API. Those are the
 * edges checked here.
 */
class EngineModuleBoundaryTest {
    private val root = ParamSurface.moduleRoot

    private val modules =
        listOf(
            "audio-core",
            "visual-core",
            "gl",
            "scenes",
            "audio-android",
            "runtime",
        )

    private fun buildFileOf(module: String) = File(root, "engine/$module/build.gradle.kts")

    private fun sourcesOf(module: String): List<File> =
        File(root, "engine/$module/src")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** `project(":engine:x")` dependencies declared by a module. */
    private fun declaredEdges(module: String): Set<String> =
        Regex("""project\("(:engine:[a-z-]+)"\)""")
            .findAll(buildFileOf(module).readText())
            .map { it.groupValues[1].removePrefix(":engine:") }
            .toSet()

    @Test
    fun `every module in the plan exists and is on the settings graph`() {
        val settings = File(root, "settings.gradle.kts").readText()
        modules.forEach { module ->
            assertTrue("engine/$module has no build file", buildFileOf(module).isFile)
            assertTrue("`:engine:$module` is not included", settings.contains("\":engine:$module\""))
        }
    }

    @Test
    fun `the dependency graph is the one the plan draws`() {
        val allowed =
            mapOf(
                "audio-core" to emptySet(),
                "visual-core" to setOf("audio-core"),
                "gl" to setOf("visual-core"),
                "scenes" to setOf("gl", "visual-core", "audio-core"),
                "audio-android" to setOf("audio-core"),
                "runtime" to setOf("audio-core", "visual-core", "gl", "scenes", "audio-android"),
            )
        modules.forEach { module ->
            val extra = declaredEdges(module) - allowed.getValue(module)
            assertEquals("`:engine:$module` declares an edge §4.1 does not allow", emptySet<String>(), extra)
        }
    }

    @Test
    fun `the pure modules are pure by construction, not by convention`() {
        // A java-library module cannot resolve android.*, so this asserts the
        // plugin choice rather than the imports - get the plugin wrong and the
        // forbidden import silently becomes possible again.
        listOf("audio-core", "visual-core").forEach { module ->
            assertTrue(
                "engine/$module must be a jvm-library, or Android types become importable",
                buildFileOf(module).readText().contains("""id("geode.jvm-library")"""),
            )
        }
    }

    @Test
    fun `no engine module reaches back into the app`() {
        val offenders =
            modules.flatMap { module ->
                sourcesOf(module)
                    .filter {
                            file ->
                        Regex("""^import dev\.geode\.(ui|data|playback)\.""", RegexOption.MULTILINE).containsMatchIn(file.readText())
                    }
                    .map { "$module/${it.name}" }
            }
        assertEquals(
            "§4.1: scene code may not reach into PlayerViewModel, Compose state or preferences",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the app depends on the runtime and on no engine module beneath it`() {
        val appBuild = File(root, "app/build.gradle.kts").readText()
        val engineEdges =
            Regex("""project\("(:engine:[a-z-]+)"\)""")
                .findAll(appBuild)
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(
            "§4.1: :app talks to :engine:runtime and to stable contracts, never to gl or scenes directly",
            setOf(":engine:runtime"),
            engineEdges,
        )
    }

    @Test
    fun `new shaders belong to the scenes module, not to app resources`() {
        // §V2-1-02: "Put new GLSL under :engine:scenes/src/main/assets/..., not
        // res/raw." The 65 existing shaders stay where the source-text gates
        // can still find them; this pins the count so a new one has to make a
        // deliberate choice rather than drift into the old home.
        val legacy = File(root, "app/src/main/res/raw").listFiles { f -> f.extension == "glsl" }.orEmpty()
        assertEquals(
            "a new shader under app/src/main/res/raw belongs in :engine:scenes assets instead",
            65,
            legacy.size,
        )
    }
}
