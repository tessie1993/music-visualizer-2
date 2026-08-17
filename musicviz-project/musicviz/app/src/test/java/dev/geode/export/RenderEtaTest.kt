package dev.geode.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long is left on a render.
 *
 * Both progress surfaces were a bare percent bar on an operation that runs for
 * minutes. The properties that matter are that it says nothing rather than
 * guessing early, and that it survives the pipeline's non-uniform shape — audio
 * transcode for the first fifth, frame rendering for the rest, at a very
 * different rate.
 */
class RenderEtaTest {
    private val second = 1_000L

    @Test
    fun `nothing is reported before there is anything to go on`() {
        val eta = RenderEta()
        assertNull(eta.sample(0f, 0))
        assertNull(eta.sample(0.01f, 2 * second))
    }

    @Test
    fun `a steady render is estimated once the window fills`() {
        val eta = RenderEta(windowSeconds = 10f)
        // 1% per second: a hundred-second render.
        var last: Long? = null
        for (t in 0..20) {
            last = eta.sample(t * 0.01f, t * second)
        }
        assertNotNull("no estimate after twenty seconds of steady progress", last)
        // 20% done at 1%/s leaves about eighty seconds.
        assertTrue("estimated $last s", last!! in 70..90)
    }

    /**
     * The failure the windowed rate exists to avoid. Audio transcode is the
     * opening fifth and runs fast; frames are the rest and run slowly. An
     * estimate that averages across the whole run predicts a finish several
     * times too early and then slides backwards, which reads as a stuck render.
     */
    @Test
    fun `a slow phase after a fast one is not estimated from the fast one`() {
        val eta = RenderEta(windowSeconds = 10f)
        // Phase one: 20% in ten seconds.
        for (t in 0..10) eta.sample(t * 0.02f, t * second)
        // Phase two: a tenth the rate.
        var last: Long? = null
        for (t in 11..60) last = eta.sample(0.2f + (t - 10) * 0.002f, t * second)

        assertNotNull(last)
        // At 0.2%/s with ~70% left, the honest answer is several hundred
        // seconds. Averaging the whole run would say well under two hundred.
        assertTrue("estimated $last s, which is the fast phase's rate", last!! > 250)
    }

    @Test
    fun `a finished render reports nothing remaining`() {
        val eta = RenderEta(windowSeconds = 5f)
        for (t in 0..10) eta.sample(t * 0.1f, t * second)
        assertEquals(0L, eta.sample(1f, 11 * second))
    }

    @Test
    fun `a stalled render does not claim to be nearly done`() {
        val eta = RenderEta(windowSeconds = 5f)
        for (t in 0..10) eta.sample(0.3f, t * second)
        // No progress at all: an estimate would be a division by zero dressed
        // up as a number, so there is none.
        assertNull(eta.sample(0.3f, 30 * second))
    }

    @Test
    fun `a restarted render is not estimated from the previous one`() {
        val eta = RenderEta(windowSeconds = 5f)
        for (t in 0..20) eta.sample(t * 0.04f, t * second)
        // Progress goes backwards: a new run on a reused instance.
        assertNull(eta.sample(0.0f, 21 * second))
    }

    @Test
    fun `reset forgets the run`() {
        val eta = RenderEta(windowSeconds = 5f)
        for (t in 0..20) eta.sample(t * 0.04f, t * second)
        eta.reset()
        assertNull(eta.sample(0.5f, 100 * second))
    }

    /** Rounded on purpose: second precision claims accuracy an estimate lacks. */
    @Test
    fun `the description is rounded and readable`() {
        assertEquals("almost done", RenderEta.describe(5))
        assertEquals("almost done", RenderEta.describe(29))
        assertEquals("about a minute left", RenderEta.describe(60))
        assertEquals("about 2 min left", RenderEta.describe(120))
        assertEquals("about 5 min left", RenderEta.describe(300))
        assertEquals("about 1 h left", RenderEta.describe(3600))
        assertEquals("about 2 h left", RenderEta.describe(7200))
    }
}
