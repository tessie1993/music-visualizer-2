package dev.geode

import dev.geode.render.scene.PcmRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The raw-PCM feed every style now shares with MilkDrop.
 *
 * The behavioral halves run headlessly (PcmRow and the sim are pure CPU); the
 * renderer's single-drain rule and the sink membership are pinned as source
 * text, because the failure that matters there - a second drain starving the
 * first consumer, a scene quietly dropping off the feed - produces no error
 * anywhere, just a style that stops being audio-reactive.
 */
class PcmFanoutTest {
    // ---- PcmRow: the shared decimation every drawing consumer uses ----

    @Test
    fun `decimation keeps the transient a stride sampler steps over`() {
        val pcm = FloatArray(2048)
        pcm[1001] = 0.9f
        val row = FloatArray(512)
        PcmRow.fill(row, pcm, pcm.size)
        assertTrue("the single-sample transient was decimated away", row.any { abs(it) >= 0.9f })
    }

    @Test
    fun `a sine survives resampling at its own amplitude`() {
        val pcm = FloatArray(4096) { (0.5 * sin(2.0 * PI * it / 256.0)).toFloat() }
        val row = FloatArray(512)
        PcmRow.fill(row, pcm, pcm.size)
        assertEquals(0.5f, row.maxOf { abs(it) }, 0.02f)
    }

    @Test
    fun `non-finite samples read as silence, empty input clears the row`() {
        val row = FloatArray(64) { 9f }
        PcmRow.fill(row, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.25f), 3)
        assertTrue(row.all { it.isFinite() })
        PcmRow.fill(row, FloatArray(0), 0)
        assertTrue("an empty source must clear stale data", row.all { it == 0f })
    }

    @Test
    fun `a short chunk still spans the whole row`() {
        val row = FloatArray(512)
        PcmRow.fill(row, FloatArray(64) { 0.3f }, 64)
        assertTrue("the tail of the row was left empty", abs(row.last()) > 0.2f)
    }

    // ---- Source gates: membership and the single drain ----

    private val moduleRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("module root not found")

    private fun source(relative: String): String = File(moduleRoot, "app/src/main/java/dev/geode/$relative").readText()

    @Test
    fun `every signal-drawing scene stays on the feed`() {
        for (scene in listOf(
            "render/scene/MilkdropScene.kt",
            "render/scene/BeamScene.kt",
            "render/scene/ShaderScene.kt",
            "render/scene/SilkScene.kt",
            "render/scene/LifeScene.kt",
            "render/scene/MycoScene.kt",
            "render/scene/AcidScene.kt",
            "render/scene/HyperspaceScene.kt",
            "render/scene/CymaticsScene.kt",
            "render/fluid/FluidScene.kt",
            "render/fluid/CurlFlowScene.kt",
            "render/fluid/WaterScene.kt",
        )) {
            assertTrue("$scene dropped off the raw-PCM feed", source(scene).contains("PcmSink"))
        }
    }

    @Test
    fun `the renderer drains the pcm cursor exactly once per frame`() {
        val renderer = source("render/VisualizerRenderer.kt")
        val drains = Regex("""framePcm = pcmProvider\(\)""").findAll(renderer).count()
        assertEquals(
            "the provider is a cursor over a ring; a second drain site returns silence " +
                "and starves the first consumer's next frame",
            1,
            drains,
        )
        assertTrue(
            "the per-frame drain flag must reset at the top of onDrawFrame",
            Regex("""onDrawFrame\([^)]*\) \{\s*\n\s*framePcmDrained = false""").containsMatchIn(renderer),
        )
        assertTrue(
            "sinks must be fed before update() so the frame they draw is the frame they heard",
            renderer.contains("(scene as? PcmSink)?.let { deliverPcm(it) }\n        scene.update("),
        )
    }

    @Test
    fun `every sink copies instead of aliasing the shared buffer`() {
        for (scene in listOf(
            "render/scene/MilkdropScene.kt",
            "render/scene/BeamScene.kt",
            "render/scene/ShaderScene.kt",
        )) {
            val body = source(scene).substringAfter("fun acceptPcm(").substringBefore("\n    }")
            assertTrue(
                "$scene keeps the caller's buffer instead of copying - it is reused next frame",
                body.contains("System.arraycopy"),
            )
        }
    }
}
