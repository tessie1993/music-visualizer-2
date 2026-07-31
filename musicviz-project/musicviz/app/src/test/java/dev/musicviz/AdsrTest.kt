package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.AdsrConfig
import dev.musicviz.render.AdsrEngine
import dev.musicviz.render.EnvBand
import dev.musicviz.render.LfoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the beat-triggered ADSR envelopes, and specifically their velocity
 * behavior: a synth envelope triggered by a MIDI note peaks at that note's
 * velocity, and since [dev.musicviz.analysis.PulseTracker] grades every beat
 * by how hard it hit, these envelopes do the same. Before that, a brushed
 * snare in a quiet bridge drove them to exactly the same full-scale peak as
 * the first hit of the drop, which is what made the modulated parameters
 * lurch identically all track long.
 *
 * The user's attack/decay/sustain/release TIMES must keep meaning what they
 * say - only the distance travelled scales - so the attack-duration assertion
 * below is as important as the peak ones.
 */
class AdsrTest {
    private companion object {
        const val DT = 1f / 60f
        const val ATTACK = 0.1f
    }

    private fun engine(
        attack: Float = ATTACK,
        sustain: Float = 0.5f,
        retrigger: Boolean = true,
    ): AdsrEngine =
        AdsrEngine().apply {
            configs =
                listOf(
                    AdsrConfig(
                        enabled = true,
                        targets = listOf(LfoTarget.ZOOM),
                        attack = attack,
                        decay = 0.25f,
                        sustain = sustain,
                        release = 0.35f,
                        amount = 1f,
                        band = EnvBand.BASS,
                        gateThreshold = 0.25f,
                        retrigger = retrigger,
                    ),
                    AdsrConfig(),
                )
        }

    private fun features(
        beat: Boolean = false,
        strength: Float = 0f,
        bass: Float = 0.5f,
    ): AudioFeatures = AudioFeatures.empty().copy(beat = beat, beatStrength = strength, bass = bass)

    /** Peak level reached over [frames] after one beat of the given strength. */
    private fun peakAfterBeat(
        strength: Float,
        frames: Int = 12,
    ): Float {
        val e = engine()
        var peak = 0f
        e.tick(DT, features(beat = true, strength = strength))
        repeat(frames) { peak = maxOf(peak, e.tick(DT, features())[0]) }
        return peak
    }

    @Test
    fun `envelope peak follows the beat's graded strength`() {
        val soft = peakAfterBeat(0.35f)
        val medium = peakAfterBeat(0.7f)
        val hard = peakAfterBeat(1f)
        assertTrue("a soft hit must still open the envelope, got $soft", soft > 0.1f)
        assertTrue("harder hits must go further ($soft < $medium < $hard)", soft < medium && medium < hard)
        assertEquals("a full-strength beat still reaches the top", 1f, hard, 1e-3f)
        assertTrue("a soft hit must stay well short of full scale, got $soft", soft < 0.5f)
    }

    @Test
    fun `a legacy beat flag with no strength still peaks at full scale`() {
        // Synthesised idle features and pre-tracker cache entries carry
        // beat=true with no strength; they must behave exactly as before.
        assertEquals(1f, peakAfterBeat(0f), 1e-3f)
    }

    @Test
    fun `the attack still takes the configured time at any strength`() {
        // Only the DISTANCE scales; the rate scales with it, so "attack =
        // 100 ms" stays 100 ms whether the hit was soft or hard.
        for (strength in listOf(0.35f, 0.7f, 1f)) {
            val e = engine()
            var frames = 0
            var prev = e.tick(DT, features(beat = true, strength = strength))[0]
            // Count frames until the level stops rising (attack -> decay).
            while (frames < 60) {
                val v = e.tick(DT, features())[0]
                if (v <= prev + 1e-6f) break
                prev = v
                frames++
            }
            val seconds = frames * DT
            assertTrue(
                "attack at strength $strength took ${seconds}s, expected about $ATTACK",
                seconds in ATTACK * 0.7f..ATTACK * 1.4f,
            )
        }
    }

    @Test
    fun `a louder retrigger mid-attack raises the ceiling, a softer one does not lower it`() {
        val loudThenSoft = engine()
        loudThenSoft.tick(DT, features(beat = true, strength = 1f))
        loudThenSoft.tick(DT, features(beat = true, strength = 0.35f)) // softer retrigger
        var peak = 0f
        repeat(12) { peak = maxOf(peak, loudThenSoft.tick(DT, features())[0]) }
        assertEquals("a soft retrigger must not yank a rising envelope down", 1f, peak, 1e-3f)

        val softThenLoud = engine()
        softThenLoud.tick(DT, features(beat = true, strength = 0.35f))
        softThenLoud.tick(DT, features(beat = true, strength = 1f)) // louder retrigger
        var peak2 = 0f
        repeat(12) { peak2 = maxOf(peak2, softThenLoud.tick(DT, features())[0]) }
        assertEquals("a louder retrigger must raise the ceiling", 1f, peak2, 1e-3f)
    }

    @Test
    fun `sustain is scaled by the triggering hit, not held at full`() {
        // Bass energy stays above the gate, so the envelope holds in sustain;
        // where it holds must reflect how hard the beat that opened it was.
        fun sustainLevel(strength: Float): Float {
            val e = engine()
            e.tick(DT, features(beat = true, strength = strength, bass = 0.8f))
            var last = 0f
            repeat(90) { last = e.tick(DT, features(bass = 0.8f))[0] }
            return last
        }
        val soft = sustainLevel(0.35f)
        val hard = sustainLevel(1f)
        assertTrue("both must sustain something (soft=$soft hard=$hard)", soft > 0f && hard > 0f)
        assertTrue("a soft hit must sustain lower ($soft vs $hard)", soft < hard)
    }

    @Test
    fun `a disabled envelope outputs nothing and resets its ceiling`() {
        val e = engine()
        e.tick(DT, features(beat = true, strength = 1f))
        e.configs = listOf(AdsrConfig(), AdsrConfig())
        assertEquals(0f, e.tick(DT, features())[0], 0f)
        // Re-enabling must not resurrect the old ceiling for a soft hit.
        e.configs = engine().configs
        e.tick(DT, features(beat = true, strength = 0.35f))
        var peak = 0f
        repeat(12) { peak = maxOf(peak, e.tick(DT, features())[0]) }
        assertTrue("a soft hit after re-enabling must stay soft, got $peak", peak < 0.5f)
    }
}
