package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.audio.AudioBus
import dev.musicviz.wallpaper.IdleFeatures
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gate for the live wallpaper's two audio sources.
 *
 * A wallpaper is on screen for hours with the music app closed, so "no audio"
 * is its NORMAL state. That makes two things load-bearing: the idle motion has
 * to actually move (a frozen or black wallpaper reads as broken), and it must
 * never fake a beat - a wallpaper that pulses like a track nobody can hear is
 * unsettling, and a synthetic beat would drive the flash and shake paths the
 * photosensitivity limits exist for, with no music to justify them.
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
}
