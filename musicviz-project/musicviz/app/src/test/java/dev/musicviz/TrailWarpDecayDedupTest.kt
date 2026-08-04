package dev.musicviz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * One formula for the feedback trail's `uDecay`, in one place.
 *
 * `trail_warp_frag` has two writers - the live renderer's `drawTrailWarp` and
 * the export path's `FxCompositor.fadeSceneTargetWarp` - and the export copy
 * used to re-implement `CurlFlowMath.warpDecay` inline. Identical today, but a
 * retune of the live remap (the `+ 0.02f` floor that keeps Curl Flow's
 * streams from strobing) would have left exports decaying at the OLD rate: a
 * rendered clip that no longer matches the screen, the exact drift the export
 * pipeline's uniform-parity comments warn about. Source-level, like
 * [RendererWiringTest]: both call sites need a GL context, but who computes
 * the decay is a fact about the code.
 */
class TrailWarpDecayDedupTest {
    private val sources: Map<String, String> by lazy {
        mapOf(
            "VisualizerRenderer" to repoFile("src/main/java/dev/musicviz/render/VisualizerRenderer.kt"),
            "FxCompositor" to repoFile("src/main/java/dev/musicviz/export/FxCompositor.kt"),
        )
    }

    @Test
    fun everyUDecayWriterCallsTheSharedHelper() {
        // The uniform upload and the helper call, adjacent: uDecay's value
        // comes from CurlFlowMath.warpDecay at both call sites.
        val fed = Regex(""""uDecay"\),\s*CurlFlowMath\.warpDecay\(""")
        for ((name, src) in sources) {
            assertTrue("$name no longer feeds uDecay from CurlFlowMath.warpDecay", fed.containsMatchIn(src))
        }
    }

    @Test
    fun neitherWriterReimplementsTheRemap() {
        // The inline copy's fingerprint: warpDecay's retention remap, spelled
        // out at a call site instead of behind the helper.
        for ((name, src) in sources) {
            assertFalse("$name re-implements CurlFlowMath.warpDecay inline again", src.contains("0.97f + 0.02f"))
        }
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
