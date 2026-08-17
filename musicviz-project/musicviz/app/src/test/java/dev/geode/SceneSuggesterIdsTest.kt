package dev.geode

import dev.geode.analysis.SceneSuggester
import dev.geode.render.scene.SceneIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * SceneSuggester speaks the renderer's scene ids, not its own copies.
 *
 * The suggester used to spell "nebula"/"bursts"/"julia"/"tunnel" as free
 * literals: a renamed id in [SceneIds] would have compiled clean and left the
 * intelligence layer suggesting a scene the renderer no longer knows -
 * `sceneFor` falls back silently, so Auto mode would have quietly pinned every
 * track to the fallback. Source-level, like [RendererWiringTest]: the aliasing
 * is a fact about the code, not about any one suggestion.
 */
class SceneSuggesterIdsTest {
    private val source: String by lazy { repoFile("src/main/java/dev/geode/analysis/SceneSuggester.kt") }

    /** Every id string [SceneIds] declares, read off the object by reflection. */
    private val sceneIdValues: Set<String> by lazy {
        SceneIds::class
            .java
            .declaredFields
            .filter { it.type == String::class.java }
            .map {
                it.isAccessible = true
                it.get(SceneIds) as String
            }.toSet()
    }

    @Test
    fun everySuggestionIsAnIdTheRendererKnows() {
        val suggestions =
            setOf(
                SceneSuggester.SCENE_EMERGENCE,
                SceneSuggester.SCENE_JULIA,
                SceneSuggester.SCENE_TUNNEL,
            )
        for (id in suggestions) {
            assertTrue("SceneSuggester suggests \"$id\", which SceneIds does not declare", id in sceneIdValues)
        }
    }

    @Test
    fun theSuggesterNamesNoSceneByLiteral() {
        // The regression this test exists for: a quoted copy of a SceneIds
        // value reintroduces the drift the aliases removed.
        for (m in Regex("\"([^\"]*)\"").findAll(source)) {
            val literal = m.groupValues[1]
            assertFalse(
                "SceneSuggester duplicates the scene id \"$literal\" as a literal - reference SceneIds instead",
                literal in sceneIdValues,
            )
        }
        assertTrue("SceneSuggester no longer references SceneIds at all", source.contains("SceneIds."))
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
