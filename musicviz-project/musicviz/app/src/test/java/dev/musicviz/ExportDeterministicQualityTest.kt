package dev.musicviz

import dev.musicviz.render.fluid.PerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A deterministic render must not consult a frame-time sensor.
 *
 * `PerformanceMonitor` exists to lower the fluid tier when a *device* cannot
 * keep up, and it reads the only signal it has: the frame interval. The export
 * path does not have frame intervals - it drives every scene with a constant
 * `dt = 1f / fps`, because the whole point is that the clip is re-rendered off
 * the export clock rather than in real time.
 *
 * Feed one to the other and the arithmetic is brutal: a 30 fps export reports a
 * rock-steady 30 fps against the monitor's 50 fps target, so the deficit never
 * resets, and at 2.5 s severity is `(50-30)/50 = 0.4` - past the 0.35 "severe"
 * bar, so **two** tiers are dropped at once, the monitor resets, and it happens
 * again every 2.5 s until the tier saturates at the floor. Every 30 fps export
 * of a fluid scene therefore rendered at minimum quality, on every device,
 * deterministically, while the screen it was exported from looked fine.
 *
 * [everyThirtyFpsFrameLooksLikeADeficitToTheSensor] pins the sensor arithmetic
 * so the reason stays visible; [theExportLoopDisablesAdaptiveQuality] pins the
 * fix. The sensor is not wrong - asking it about an export was.
 */
class ExportDeterministicQualityTest {
    @Test
    fun everyThirtyFpsFrameLooksLikeADeficitToTheSensor() {
        // Exactly what VideoExporter feeds a scene at 30 fps: a constant dt.
        val monitor = PerformanceMonitor()
        val dt = 1f / 30f
        var severity = 0
        var frames = 0
        while (severity == 0 && frames < 600) {
            severity = monitor.onFrame(dt)
            frames++
        }
        assertEquals(
            "a constant 30 fps feed must reach SEVERE - this is why the export path may not feed it",
            2,
            severity,
        )
        // 2.5 s of sustained deficit at 1/30 s per frame, plus the window
        // warm-up before averageFps reports at all.
        assertTrue("fired after only $frames frames", frames >= 75)
    }

    @Test
    fun aSixtyFpsFeedIsHealthyAndNeverDowngrades() {
        // The counterweight: 60 > 50, so a 60 fps export escaped the bug
        // entirely. That asymmetry is why it went unnoticed for so long.
        val monitor = PerformanceMonitor()
        repeat(600) { assertEquals(0, monitor.onFrame(1f / 60f)) }
    }

    @Test
    fun theExportLoopDisablesAdaptiveQuality() {
        // Both FluidScene and WaterScene gate their monitor on
        // p.fluidAutoQuality, and VideoExporter builds the frame's params in
        // one place - so neutralising it there covers every current and future
        // frame-time-fed consumer.
        val src = repoFile("src/main/java/dev/musicviz/export/VideoExporter.kt")
        assertTrue(
            "VideoExporter must neutralise fluidAutoQuality: a deterministic render " +
                "cannot let a frame-time sensor choose its resolution",
            Regex("""fluidAutoQuality\s*=\s*false""").containsMatchIn(src),
        )
    }

    @Test
    fun theSensorIsStillConsultedOnTheLivePath() {
        // The fix must not turn adaptive quality off for everyone - a device
        // that genuinely cannot run FLUID still needs to step down.
        for (scene in listOf("FluidScene", "WaterScene")) {
            val src = repoFile("src/main/java/dev/musicviz/render/fluid/$scene.kt")
            assertTrue(
                "$scene no longer consults the frame-time sensor at all",
                src.contains("monitor.onFrame("),
            )
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
