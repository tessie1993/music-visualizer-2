package dev.musicviz

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The ink layer's lifecycle contract in `RippleSim`.
 *
 * `create()` compiles the ink shaders only when `inkEnabled` is set at that
 * moment; `allocGrid()` (every resize and quality retier) allocates the ink
 * grids later, re-reading the flag. Flipped to true between the two, an
 * unguarded allocGrid builds grids that `step()` has no programs to drive,
 * and the first ink splat kills the GL thread with
 * `programs.getValue -> NoSuchElementException`.
 *
 * Source-level, like [RenderClockWrapTest]: demonstrating the crash needs a
 * GL thread, but the guard that prevents it is a fact about the source. The
 * grid allocation must be tied to the compiled program, not the flag alone,
 * so a post-create flip degrades to the documented inkless behaviour - the
 * FluidSim retier shape, where the shader set is fixed at create and only
 * grids move mid-life.
 */
class RippleInkLifecycleTest {
    @Test
    fun inkGridsAreOnlyAllocatedWhenTheInkShadersWereCompiled() {
        val src = repoFile("src/main/java/dev/musicviz/render/fluid/RippleSim.kt")
        assertTrue(
            "allocGrid gates the ink grids on inkEnabled alone again - a flip to true after " +
                "create() allocates grids step() cannot drive and crashes the GL thread",
            src.contains("inkEnabled && programs.containsKey(R.raw.water_ink_splat_frag)"),
        )
    }

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
