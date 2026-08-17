package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The follower every visual-driving signal passes through.
 *
 * The property that matters is the one the legacy `BandSmoother` did not have:
 * its coefficients were per-tick constants (`0.6`/`0.12`), so the same music
 * felt different at a 60 Hz hop than at a 120 Hz one, and any change to the
 * analysis cadence silently retuned every scene. Here the coefficients are
 * derived from a time constant and the elapsed time, so behaviour is a
 * property of wall-clock seconds and nothing else.
 */
class EnvelopeTest {
    @Test
    fun `rises toward a step and approaches it`() {
        val env = Envelope(attackSeconds = 0.05f, releaseSeconds = 0.5f)
        repeat(100) { env.step(1f, dtSeconds = 1f / 60f) }
        assertEquals(1f, env.value, 1e-3f)
    }

    @Test
    fun `attack is faster than release`() {
        val rising = Envelope(attackSeconds = 0.05f, releaseSeconds = 0.5f)
        repeat(6) { rising.step(1f, 1f / 60f) }

        val falling = Envelope(attackSeconds = 0.05f, releaseSeconds = 0.5f)
        falling.primeTo(1f)
        repeat(6) { falling.step(0f, 1f / 60f) }

        // 100 ms in: the riser is most of the way up, the faller has barely moved.
        assertTrue("attack reached ${rising.value}", rising.value > 0.7f)
        assertTrue("release reached ${falling.value}", falling.value > 0.8f)
    }

    /**
     * The regression that motivates the class. Two hop rates, the same half
     * second of a held signal: the values must agree, because a time constant
     * is measured in seconds.
     */
    @Test
    fun `the same wall-clock span gives the same value at any hop rate`() {
        fun runAt(hopHz: Int): Float {
            val env = Envelope(attackSeconds = 0.12f, releaseSeconds = 0.4f)
            repeat(hopHz / 2) { env.step(1f, 1f / hopHz) }
            return env.value
        }
        val slow = runAt(30)
        val fast = runAt(240)
        assertTrue("30 Hz gave $slow, 240 Hz gave $fast", abs(slow - fast) < 5e-3f)
    }

    @Test
    fun `a zero time constant follows the target immediately`() {
        val env = Envelope(attackSeconds = 0f, releaseSeconds = 0f)
        env.step(0.75f, 1f / 60f)
        assertEquals(0.75f, env.value, 0f)
    }

    @Test
    fun `a stalled frame does not overshoot`() {
        val env = Envelope(attackSeconds = 0.05f, releaseSeconds = 0.5f)
        // A 2 s hitch: exp(-dt/tau) underflows to 0, so the step must clamp to
        // the target rather than running past it.
        env.step(1f, dtSeconds = 2f)
        assertEquals(1f, env.value, 1e-6f)
    }

    @Test
    fun `reset drops the level but keeps the shape`() {
        val env = Envelope(attackSeconds = 0.05f, releaseSeconds = 0.5f)
        repeat(30) { env.step(1f, 1f / 60f) }
        env.reset()
        assertEquals(0f, env.value, 0f)
        env.step(1f, 1f / 60f)
        assertTrue(env.value > 0f)
    }
}
