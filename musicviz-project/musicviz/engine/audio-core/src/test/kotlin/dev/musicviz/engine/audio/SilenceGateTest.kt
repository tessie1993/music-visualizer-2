package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class SilenceGateTest {
    private val hopRateHz = 86f

    private fun gate(holdSeconds: Float = SilenceGate.HOLD_SECONDS) = SilenceGate(hopRateHz, holdSeconds = holdSeconds)

    @Test
    fun `a session starts silent`() {
        assertSame(FrameActivity.Silent, gate().activity)
    }

    @Test
    fun `nothing below the open threshold opens it`() {
        val gate = gate()
        repeat(500) { assertSame(FrameActivity.Silent, gate.update(SilenceGate.OPEN_AT_RMS * 0.99f)) }
    }

    @Test
    fun `the open threshold opens it on the frame it is crossed`() {
        assertSame(FrameActivity.Sounding, gate().update(SilenceGate.OPEN_AT_RMS))
    }

    @Test
    fun `once open it stays open down to the close threshold`() {
        // The hysteresis, on its own: a level that could never have opened the
        // gate still keeps it open, and does so indefinitely rather than for
        // the length of the hold.
        val gate = gate()
        gate.update(0.1f)
        repeat(10_000) { assertSame(FrameActivity.Sounding, gate.update(SilenceGate.CLOSE_AT_RMS)) }
    }

    @Test
    fun `a signal oscillating around the open threshold does not chatter`() {
        // The failure a bare threshold has and this does not. Every one of
        // these frames is on the silent side of the open threshold half the
        // time, and a threshold would flip the whole feature set with it.
        val gate = gate()
        gate.update(0.1f)
        repeat(2_000) { i ->
            val rms = if (i % 2 == 0) SilenceGate.OPEN_AT_RMS * 1.01f else SilenceGate.OPEN_AT_RMS * 0.5f
            assertSame("frame $i", FrameActivity.Sounding, gate.update(rms))
        }
    }

    @Test
    fun `below the close threshold it holds for exactly the hold and then closes`() {
        // 80 Hz and a quarter second so the hold is 20 frames exactly and the
        // test is not really about how the constructor rounds.
        val gate = SilenceGate(hopRateHz = 80f, holdSeconds = 0.25f)
        gate.update(0.1f)
        repeat(20) { assertSame("frame $it of the hold", FrameActivity.Sounding, gate.update(0f)) }
        assertSame(FrameActivity.Silent, gate.update(0f))
    }

    @Test
    fun `the hold re-arms on every sounding frame`() {
        val gate = gate(0.25f)
        repeat(50) {
            // Just under the hold each time, so only re-arming keeps it open.
            gate.update(0.1f)
            repeat(20) { assertSame(FrameActivity.Sounding, gate.update(0f)) }
        }
    }

    @Test
    fun `a zero hold closes on the first frame below the close threshold`() {
        val gate = gate(holdSeconds = 0f)
        assertSame(FrameActivity.Sounding, gate.update(0.1f))
        assertSame(FrameActivity.Silent, gate.update(0f))
    }

    @Test
    fun `reset returns it to the start of a session`() {
        val gate = gate()
        gate.update(0.5f)
        assertSame(FrameActivity.Sounding, gate.activity)
        gate.reset()
        assertSame(FrameActivity.Silent, gate.activity)
        // And the hold does not survive the reset either.
        assertSame(FrameActivity.Silent, gate.update(0f))
    }

    @Test
    fun `the thresholds are the levels the documentation claims`() {
        // The constants are written as linear RMS and documented in dBFS;
        // this is what stops the two drifting apart.
        assertEquals(-60.0, 20.0 * log10(SilenceGate.OPEN_AT_RMS.toDouble()), 0.01)
        assertEquals(-70.0, 20.0 * log10(SilenceGate.CLOSE_AT_RMS.toDouble()), 0.01)
        // And 16-bit quantisation noise sits far below both: q / sqrt(12).
        val quantisationRms = (1.0 / 32768.0) / kotlin.math.sqrt(12.0)
        assertEquals(-101.1, 20.0 * log10(quantisationRms), 0.1)
    }

    @Test
    fun `deciding a frame allocates nothing`() {
        val gate = gate()
        var i = 0
        val perRun = JvmAllocationMeter.perRun(20_000) { gate.update(if (i++ % 3 == 0) 0.2f else 0f) }
        assertEquals("update allocated $perRun bytes per frame", 0.0, perRun, 1.0)
    }

    @Test
    fun `a malformed gate is refused at construction`() {
        val bad =
            listOf(
                { SilenceGate(0f) },
                { SilenceGate(hopRateHz, openAtRms = 0f) },
                { SilenceGate(hopRateHz, openAtRms = 1e-4f, closeAtRms = 1e-3f) },
                { SilenceGate(hopRateHz, holdSeconds = -1f) },
            )
        for (make in bad) {
            try {
                make()
                throw AssertionError("a malformed gate was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue("the message says nothing useful", expected.message!!.isNotEmpty())
            }
        }
    }
}
