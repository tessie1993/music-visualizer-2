package dev.geode

import dev.geode.render.VisualizerRenderer
import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one contract the JVM suite cannot test by running it: release builds
 * are minified, and `VisualizerRenderer.LERPED_FLOATS` excludes the
 * [VisualizerRenderer.NOT_FADED] fields BY NAME. With those fields renamed
 * by R8 the exclusion matches nothing, the UNSET_OVERRIDE sentinels get
 * lerped through zero, and every palette-override fade flickers between set
 * and unset - in the installed build only, while this whole suite (which
 * runs unminified) stays green. So the keep rule itself is pinned here:
 * deleting it from proguard-rules.pro fails the build where the bug would
 * otherwise ship silently.
 */
class ReleaseMinifyContractTest {
    @Test
    fun `proguard keeps the SceneParams field names the fade excludes by name`() {
        val rules = File(appDir(), "proguard-rules.pro").readText()
        assertTrue(
            "proguard-rules.pro no longer keeps SceneParams field names; " +
                "NOT_FADED's name-based exclusion breaks in release builds",
            Regex(
                """-keepclassmembernames\s+class\s+dev\.geode\.render\.scene\.SceneParams\s*\{\s*<fields>;\s*\}""",
            ).containsMatchIn(rules),
        )
    }

    @Test
    fun `every NOT_FADED name is a real SceneParams field`() {
        // A stale name here would silently re-enrol that field in the fade;
        // ParamFadeCoverageTest guards the other direction (a field neither
        // faded nor named).
        val fields = SceneParams::class.java.declaredFields.map { it.name }.toSet()
        for (name in VisualizerRenderer.NOT_FADED.keys) {
            assertTrue("NOT_FADED names '$name', which is not a SceneParams field", name in fields)
        }
    }

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, prefix + "src/main")
                if (candidate.isDirectory) return candidate.parentFile.parentFile
            }
            dir = dir.parentFile
        }
        error("app module not found from ${File("").absolutePath}")
    }
}
