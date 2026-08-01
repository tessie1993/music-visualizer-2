package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.audio.AudioBus
import dev.musicviz.wallpaper.IdleFeatures
import dev.musicviz.wallpaper.VisualizerWallpaperService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gate for the live wallpaper's two audio sources, and for when it runs them.
 *
 * A wallpaper is on screen for hours with the music app closed, so "no audio"
 * is its NORMAL state. That makes two things load-bearing: the idle motion has
 * to actually move (a frozen or black wallpaper reads as broken), and it must
 * never fake a beat - a wallpaper that pulses like a track nobody can hear is
 * unsettling, and a synthetic beat would drive the flash and shake paths the
 * photosensitivity limits exist for, with no music to justify them.
 *
 * The same "hours on screen" is why the Engine's lifecycle is gated here too.
 * It outlives its surface and spends most of its life invisible, so which of
 * those states feed a 62 Hz thread is both a battery question and, when the
 * surface comes back, the difference between a live wallpaper and one frozen
 * until the user re-selects it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperIdleTest {
    @After
    fun tearDown() = AudioBus.clear()

    private fun loud() =
        AudioFeatures(
            bands = FloatArray(16) { 0.8f },
            waveform = FloatArray(64) { 0.5f },
            rms = 0.9f,
            bass = 0.9f,
            mid = 0.8f,
            treble = 0.7f,
            beat = true,
        )

    @Test
    fun theIdleMotionActuallyMoves() {
        val idle = IdleFeatures()
        val first = idle.tick(0.016f)
        val bassSamples = mutableSetOf<Float>()
        var maxBand = 0f
        repeat(600) {
            val f = idle.tick(0.05f)
            bassSamples += f.bass
            maxBand = maxOf(maxBand, f.bands.max())
        }
        assertTrue("idle bass never changed", bassSamples.size > 100)
        assertTrue("idle bands are silent — the wallpaper would be black", maxBand > 0.05f)
        assertTrue("the very first frame is already alive", first.rms > 0f)
    }

    @Test
    fun aFrameHandedOverIsNeverRewrittenUnderTheRenderer() {
        // The two threads the wallpaper runs: the feeder ticks idle motion at
        // 62 Hz while the GL thread reads the bands and waveform out of
        // whatever frame it grabbed, for as long as that frame takes to draw.
        // Ticking into one shared pair of arrays made every idle frame - the
        // wallpaper's normal state - able to read a spectrum that was half one
        // tick and half the next.
        val idle = IdleFeatures()
        val held = idle.tick(0.016f)
        val bandsWhenHandedOver = held.bands.copyOf()
        val waveformWhenHandedOver = held.waveform.copyOf()
        var latest = held
        repeat(600) { latest = idle.tick(0.05f) }
        assertNotSame("later ticks handed back the array the renderer is holding", held.bands, latest.bands)
        assertNotSame(held.waveform, latest.waveform)
        // Non-vacuous: the idle really did move on underneath, and the held
        // frame still reads exactly as it did when it was handed over.
        assertFalse("the idle spectrum never changed at all", latest.bands.contentEquals(bandsWhenHandedOver))
        assertFalse("the idle waveform never changed at all", latest.waveform.contentEquals(waveformWhenHandedOver))
        assertArrayEquals("a returned frame was mutated by a later tick", bandsWhenHandedOver, held.bands, 0f)
        assertArrayEquals("a returned waveform was mutated by a later tick", waveformWhenHandedOver, held.waveform, 0f)
    }

    @Test
    fun idleFramesAreShapedLikeTheAnalyzersOwn() {
        // ShaderScene resamples the bands and MilkDrop is handed the waveform
        // verbatim, so a differently sized idle frame would look measurably
        // coarser than the same scene playing music. Pinned here because the
        // arrays are now built per tick rather than once in the constructor.
        val f = IdleFeatures().tick(0.016f)
        val reference = AudioFeatures.empty()
        assertEquals(reference.bands.size, f.bands.size)
        assertEquals(reference.waveform.size, f.waveform.size)
    }

    @Test
    fun theIdleMotionNeverFakesABeat() {
        val idle = IdleFeatures()
        repeat(5_000) { assertFalse("idle invented a beat", idle.tick(0.016f).beat) }
    }

    @Test
    fun theIdleMotionStaysGentle() {
        // Not just "moves": a wallpaper that lurches is worse than a still one.
        val idle = IdleFeatures()
        var previous = idle.tick(0.016f)
        repeat(2_000) {
            val next = idle.tick(0.016f)
            val jump = kotlin.math.abs(next.bass - previous.bass)
            assertTrue("idle bass jumped by $jump in one frame", jump < 0.02f)
            assertTrue("idle level is not calm (${next.rms})", next.rms < 0.4f)
            previous = next
        }
    }

    @Test
    fun theBusHandsOverWhatTheAppIsPlaying() {
        AudioBus.publish(loud())
        val f = AudioBus.features()
        assertNotNull("the wallpaper must see the app's own analysis", f)
        assertTrue(AudioBus.isLive)
        assertTrue(f!!.beat)
    }

    @Test
    fun aClearedBusMeansIdleRatherThanAFrozenLastFrame() {
        // What happens when the app is closed: the wallpaper has to fall back
        // to its own motion, not hold the final frame of the last session.
        AudioBus.publish(loud())
        AudioBus.clear()
        assertNull(AudioBus.features())
        assertFalse(AudioBus.isLive)
    }

    /**
     * Live feeder threads, by the name the Engine gives its own.
     *
     * The feeder is the only thing about the Engine's run state observable
     * from outside it, and it is enough: stopping it is joined, so the count
     * is exact rather than eventually-consistent.
     */
    private fun feeders() = Thread.getAllStackTraces().keys.count { it.name == "musicviz-wallpaper-audio" }

    @Test
    fun theEngineFeedsExactlyWhileThereIsSomethingToFeed() {
        val service = Robolectric.buildService(VisualizerWallpaperService::class.java).create().get()
        val engine = service.onCreateEngine()
        val holder = engine.surfaceHolder
        try {
            engine.onCreate(holder)
            assertEquals("fed before there was a surface to draw on", 0, feeders())
            engine.onSurfaceCreated(holder)
            assertEquals("fed while the wallpaper was still hidden", 0, feeders())
            engine.onVisibilityChanged(true)
            assertEquals("on screen with nothing feeding it", 1, feeders())

            // The callbacks repeat, and two feeders would mean two writers of
            // `features` and idle motion running at twice its tuned rate.
            engine.onVisibilityChanged(true)
            engine.onSurfaceCreated(holder)
            assertEquals("a repeated callback started a second feeder", 1, feeders())

            // The battery half: nothing reads `features` behind another app,
            // which is where the wallpaper spends most of its life.
            engine.onVisibilityChanged(false)
            assertEquals("kept synthesizing 62 times a second for nobody", 0, feeders())
            engine.onVisibilityChanged(true)
            assertEquals("did not come back when the user did", 1, feeders())

            // The recovery half: an Engine outlives its surface. Losing the
            // feeder for good on a destroy left every feature-driven motion
            // frozen on the last idle frame for the rest of the Engine's life.
            engine.onSurfaceDestroyed(holder)
            assertEquals("the feeder outlived the surface it was feeding", 0, feeders())
            engine.onSurfaceCreated(holder)
            assertEquals("never recovered from a surface destroy/recreate", 1, feeders())
        } finally {
            engine.onDestroy()
        }
        assertEquals("the destroyed engine left its feeder running", 0, feeders())
    }
}
