package dev.musicviz

import dev.musicviz.audio.MicGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gate for "the microphone is registered too soft".
 *
 * Everything downstream of the ring buffer - the FFT bands, the beat
 * threshold, the emitters - was calibrated against the playback tap, which
 * carries a decoded track at close to full scale. A room across a microphone
 * is 20-30 dB below that, and MicCapture asks for UNPROCESSED, which switches
 * off the automatic gain a voice source would have applied. The samples were
 * right and tiny, so the visuals barely moved.
 */
class MicGainTest {
    /** RMS of the first [n] samples. */
    private fun rms(
        buf: FloatArray,
        n: Int = buf.size,
    ): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / n).toFloat()
    }

    /** One block of a steady tone at [amplitude], as the capture reads them. */
    private fun tone(
        amplitude: Float,
        block: Int,
        frames: Int = 1024,
    ): FloatArray = FloatArray(frames) { amplitude * sin((block * frames + it) * 0.07f) }

    /** Runs [blocks] of a steady tone and returns the last block, processed. */
    private fun settle(
        gain: MicGain,
        amplitude: Float,
        blocks: Int = 400,
        userGain: Float = 1f,
    ): FloatArray {
        var last = FloatArray(0)
        for (b in 0 until blocks) {
            last = tone(amplitude, b)
            gain.process(last, last.size, userGain)
        }
        return last
    }

    @Test
    fun aQuietRoomIsLiftedToTheLevelATrackPlaysAt() {
        // The whole point: the analysis cannot tell the two sources apart and
        // must not need to.
        val out = settle(MicGain(), amplitude = 0.01f)
        assertEquals("a quiet room did not reach playback level", 0.18f, rms(out), 0.05f)
    }

    @Test
    fun aRoomThirtyDecibelsDownStillArrivesUsable() {
        // 0.004 amplitude is roughly speech across a room: below what the
        // automatic stage alone can close, since it stops at 32x rather than
        // amplifying whatever noise floor a phone has by an unbounded amount.
        // It gives all 32x, and the default trim covers the rest - which is
        // why that default is above 1.
        val before = rms(tone(0.004f, 0))
        val auto = settle(MicGain(), amplitude = 0.004f)
        assertEquals("the automatic stage did not give its full ceiling", 32f, rms(auto) / before, 1f)

        val shipped =
            settle(
                MicGain(),
                amplitude = 0.004f,
                userGain = dev.musicviz.audio.MicCapture.DEFAULT_SENSITIVITY,
            )
        assertTrue(
            "a room 30 dB down does not reach playback level as shipped (${rms(shipped)})",
            rms(shipped) > 0.12f,
        )
    }

    @Test
    fun aLoudRoomIsNotAmplifiedFurther() {
        // The automatic stage only ever lifts; a loud source is already where
        // the analysis wants it, and pushing it higher would just clip.
        val out = settle(MicGain(), amplitude = 0.6f)
        assertTrue("a loud room was pushed past full scale", out.max() <= 1f)
        assertTrue("a loud room was boosted anyway (${rms(out)})", rms(out) < 0.6f)
    }

    @Test
    fun silenceIsNotAmplifiedIntoALightShow() {
        // Without a noise floor the follower drives the gain to its ceiling in
        // a quiet room and turns preamp hiss into visuals.
        val gain = MicGain()
        var last = FloatArray(0)
        for (b in 0 until 400) {
            last = FloatArray(1024) { 0.0002f * sin(it * 0.3f) }
            gain.process(last, last.size, 1f)
        }
        assertTrue("silence was amplified (${rms(last)})", rms(last) < 0.01f)
        assertEquals("gain drifted off unity on silence", 1f, gain.autoGain, 0.05f)
    }

    @Test
    fun theUserTrimIsAppliedOnTopAndActuallyChangesTheOutput() {
        // The slider must not be swallowed by the follower compensating for
        // it - that would make the control do nothing, which is the bug this
        // whole file exists for.
        val low = settle(MicGain(), amplitude = 0.01f, userGain = 0.5f)
        val high = settle(MicGain(), amplitude = 0.01f, userGain = 4f)
        assertTrue("turning sensitivity up did not make it louder", rms(high) > rms(low) * 2f)
    }

    @Test
    fun outputNeverLeavesTheValidSampleRange() {
        // A square edge from a hard clamp is broadband noise: it would light
        // every FFT band at once, which reads as a flash on every beat.
        val gain = MicGain()
        for (b in 0 until 200) {
            val buf = tone(0.02f, b)
            gain.process(buf, buf.size, 8f)
            for (v in buf) {
                assertTrue("sample $v left [-1, 1]", abs(v) <= 1f)
                assertTrue("sample was not finite", v.isFinite())
            }
        }
    }

    @Test
    fun theGainMovesSlowlyEnoughToLeaveTheBeatsIntact() {
        // This is a level follower, not a compressor. If it tracked fast
        // enough to flatten beat-to-beat dynamics it would take away exactly
        // what the beat tracker keys off.
        val gain = MicGain()
        settle(gain, amplitude = 0.02f, blocks = 300)
        val steady = gain.autoGain
        // One loud block, as a kick would be.
        val hit = tone(0.6f, 301)
        gain.process(hit, hit.size, 1f)
        assertTrue(
            "one loud block moved the gain by ${abs(gain.autoGain - steady) / steady}",
            abs(gain.autoGain - steady) / steady < 0.15f,
        )
    }

    @Test
    fun reopeningTheMicrophoneForgetsTheOldRoom() {
        val gain = MicGain()
        settle(gain, amplitude = 0.004f)
        assertTrue("gain never rose in a quiet room", gain.autoGain > 4f)
        gain.reset()
        assertEquals("reset did not return to unity", 1f, gain.autoGain, 1e-6f)
    }

    @Test
    fun anEmptyBlockIsNotADivideByZero() {
        val gain = MicGain()
        gain.process(FloatArray(0), 0, 1f)
        gain.process(FloatArray(16), 0, 1f)
        assertEquals(1f, gain.autoGain, 1e-6f)
    }
}
