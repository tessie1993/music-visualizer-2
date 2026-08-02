package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `FluidSim.pressureTex`: that it is readable at all, and that reading it
 * changed nothing.
 *
 * One [dev.musicviz.render.fluid.FluidSim] drives FLUID, WATER's flow layer,
 * CURLFLOW and the Hyperspace melt, so the bar for adding a member to it is
 * that no shipping style can tell the difference. A getter over a private grid
 * clears that bar by construction - it reads state that already exists, it
 * allocates nothing on the draw path, and no operator in `step` consults it -
 * and these tests hold each of those three clauses rather than trusting them.
 *
 * The other half is that the exposure is worth anything: the plan's premise is
 * that the pressure field is "computed every frame and thrown away", so the
 * grid has to actually SURVIVE the frame that computes it, on the side the
 * getter reads. Source-level, like [SharedShaderPreludeTest]: a unit test has
 * no GL context, and every one of these questions is about the code.
 */
class FluidPressureExposureTest {
    private val source: String by lazy { ParamSurface.source("render/fluid/FluidSim.kt") }

    /** [source] with its comments removed, so a doc block cannot satisfy a scan. */
    private val code: String by lazy { source.replace(Regex("""//[^\n]*"""), "").replace(Regex("""(?s)/\*.*?\*/"""), "") }

    /** The body of `step`, from its signature to the closing brace. */
    private val stepBody: String by lazy {
        val start = code.indexOf("fun step(dtRaw: Float) {")
        assertTrue("FluidSim.step not found", start >= 0)
        val end = code.indexOf("\n    }", start)
        assertTrue("FluidSim.step is never closed", end > start)
        code.substring(start, end)
    }

    @Test
    fun thePressureFieldIsExposedAsAReadOnlyViewOfTheGridThatAlreadyExists() {
        // Spelled exactly as dyeTex and velocityTex are, including the `?: 0`
        // fallback - a caller that binds texture 0 samples black, where a
        // caller handed a stale name from a released grid would sample another
        // style's memory or fail the draw outright.
        assertTrue(
            "pressureTex is not the same getter form as dyeTex and velocityTex",
            code.contains("val pressureTex: Int get() = pressure?.read?.tex ?: 0"),
        )
        assertTrue("the pressure grid stopped being private", code.contains("private var pressure: FluidBuffers.DoubleFbo? = null"))
        // A `val ... get()` and not a `var`, so nothing outside can point the
        // simulation at a texture it does not own.
        assertTrue("pressureTex became writable", !code.contains("var pressureTex"))
    }

    @Test
    fun nothingInsideTheSimulationReadsIt() {
        // The neutrality argument, made checkable. If no operator in the frame
        // consults the property, then adding it cannot reorder a pass, cannot
        // change what any pass samples, and cannot move a pixel of FLUID,
        // WATER, CURLFLOW or the Hyperspace melt.
        assertTrue("FluidSim.step reads its own pressureTex", !stepBody.contains("pressureTex"))
        assertEquals(
            "pressureTex is mentioned somewhere other than its own declaration",
            1,
            Regex("pressureTex").findAll(code).count(),
        )
        // And no allocation, which is the draw-path rule HotPathReuseTest
        // exists for: the getter is two null-safe field reads and an Int.
        val getter = code.substringAfter("val pressureTex: Int get() =").substringBefore('\n')
        for (allocator in listOf("(", "listOf", "arrayOf", "IntArray", "Pair", "Triple")) {
            assertTrue("the pressureTex getter allocates ($allocator): $getter", !getter.contains(allocator))
        }
    }

    @Test
    fun theSolveSurvivesTheFrameThatComputesItOnTheSideTheGetterReads() {
        // The plan's premise for this one line is that fourteen-to-twenty
        // Jacobi sweeps a frame are already being run and then discarded. Two
        // things have to hold for the getter to hand back that solve rather
        // than a half-relaxed intermediate.
        //
        // First, the loop swaps AFTER each blit, so the converged field ends
        // up in `read` - which is exactly the side the very next pass,
        // fluid_gradient_frag, is handed. That bind is the evidence: if
        // `read` were the wrong side, the projection itself would be wrong and
        // every fluid style would visibly diverge.
        val projection = stepBody.substringAfter("repeat(pressureIterations) {")
        assertTrue(
            "the Jacobi loop no longer leaves its result in `read`",
            projection.startsWith(
                "\n            bindTex(\"uPressure\", press.read.tex, 0, R.raw.fluid_pressure_frag)\n" +
                    "            blit(press.write)\n            press.swap()\n        }",
            ),
        )
        assertTrue(
            "the gradient pass no longer reads the side pressureTex returns",
            projection.contains("bindTex(\"uPressure\", press.read.tex, 0, R.raw.fluid_gradient_frag)"),
        )
        // Second, the grid outlives the frame: `pressure` is only ever
        // assigned during allocation and teardown, never released at the end
        // of a step, which is what lets a consumer read it between frames at
        // all. (It is also why the next frame can use it as a warm start.)
        val assignments = Regex("""\n\s*pressure = """).findAll(code).count()
        assertEquals("the pressure grid is assigned somewhere new - check it still outlives a frame", 3, assignments)
        assertTrue("the pressure grid is now allocated inside step", !stepBody.contains("pressure ="))
    }

    @Test
    fun noShippingStyleHasStartedReadingIt() {
        // The exposure is for a style that does not exist yet (hyper_foam),
        // and until it does, "nothing reads it" is the strongest possible
        // statement of neutrality. When the first consumer lands this gets
        // narrowed to naming it, not deleted.
        val readers =
            File(ParamSurface.moduleRoot, "app/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "FluidSim.kt" }
                .filter { it.readText().contains("pressureTex") }
                .map { it.name }
                .toList()
        assertTrue("pressureTex has acquired consumers: $readers", readers.isEmpty())
    }
}
