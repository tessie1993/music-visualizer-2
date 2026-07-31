package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [PulseTracker], the tempo-phase-locked layer between raw onset
 * detection and the visuals.
 *
 * The complaint it exists to fix: the plain sigma gate fires on every
 * transient that clears the threshold - syncopated hits, hats, fills - and
 * every firing hits the scenes at identical full strength, so busy or soft
 * material reads as random flicker rather than the song's pulse. The tracker
 * locks a beat grid to the track's own tempo, suppresses off-grid candidates
 * while locked (except unmistakable accents), grades each accepted beat by
 * how hard it actually hit, and exposes a continuous beat phase plus a
 * track-relative macro-energy envelope for motion between beats.
 *
 * Everything here is deterministic replay-for-replay: the live extractor and
 * [PulseTracker.decidePulse] must produce identical curves from identical
 * input, or exports would drift from playback.
 */
class PulseTrackerTest {
    private companion object {
        const val HOP = 60f

        /** Enough frames for the 6 s flux history to fill and the lock to settle. */
        const val WARMUP = 700
    }

    private fun tracker(
        sigma: Float = FeatureExtractor.SIGMA_DEFAULT,
        intervalMs: Float = FeatureExtractor.INTERVAL_MS_MIN,
    ): PulseTracker {
        val t = PulseTracker(HOP)
        t.gate.beatThresholdSigma = sigma
        t.gate.beatMinIntervalMs = intervalMs
        return t
    }

    /** Kick every [period] frames, an off-beat transient halfway between. */
    private fun kickAndOffbeatFlux(
        frame: Int,
        period: Int,
        kick: Float = 1f,
        offbeat: Float = 0.55f,
    ): Float =
        when {
            frame % period == 0 -> kick
            frame % period == period / 2 -> offbeat
            else -> 0.03f
        }

    @Test
    fun `locks onto a steady pulse and reports high confidence`() {
        val t = tracker()
        for (frame in 0 until WARMUP + 600) t.step(kickAndOffbeatFlux(frame, 40), 0.4f)
        assertTrue("confidence should be locked-high, got ${t.confidence}", t.confidence >= PulseTracker.LOCK_ENTER)
    }

    @Test
    fun `off-grid transients are suppressed once locked, kicks are kept`() {
        // Period 40 (90 BPM), off-beats at +20: outside the 200 ms refractory,
        // so the plain gate accepts BOTH - the strobe being fixed. The grid
        // keeps the kicks and drops the off-beats.
        val t = tracker()
        var kicks = 0
        var kickBeats = 0
        var offbeatBeats = 0
        for (frame in 0 until WARMUP + 1200) {
            t.step(kickAndOffbeatFlux(frame, 40), 0.4f)
            if (frame < WARMUP) continue
            if (frame % 40 == 0) {
                kicks++
                if (t.beat) kickBeats++
            } else if (t.beat) {
                offbeatBeats++
            }
        }
        // The plain gate fires on every off-beat too; prove it for contrast.
        val gate = FeatureExtractor.BeatGate(HOP)
        gate.beatMinIntervalMs = FeatureExtractor.INTERVAL_MS_MIN
        var gateBeats = 0
        for (frame in 0 until WARMUP + 1200) {
            if (gate.accept(kickAndOffbeatFlux(frame, 40)) && frame >= WARMUP) gateBeats++
        }
        assertTrue("fixture must have kicks", kicks >= 25)
        assertTrue("kicks should still fire, got $kickBeats of $kicks", kickBeats >= kicks * 3 / 4)
        assertTrue("off-beats should be suppressed, got $offbeatBeats", offbeatBeats <= kicks / 8)
        assertTrue(
            "the plain gate should over-trigger here (got $gateBeats vs $kicks kicks), or this test proves nothing",
            gateBeats >= kicks * 3 / 2,
        )
    }

    @Test
    fun `beat strength grades soft hits below hard hits`() {
        val t = tracker()
        var strong = 0f
        var weak = 0f
        for (frame in 0 until WARMUP + 1200) {
            // Alternate hard and soft kicks on the same 40-frame grid.
            val onGrid = frame % 40 == 0
            val hard = frame % 80 == 0
            val flux = if (onGrid) (if (hard) 1.4f else 0.55f) else 0.03f
            t.step(flux, 0.4f)
            if (frame >= WARMUP && t.beat) {
                if (hard) strong = maxOf(strong, t.strength) else weak = maxOf(weak, t.strength)
            }
        }
        assertTrue("both kinds of beats must fire (strong=$strong weak=$weak)", strong > 0f && weak > 0f)
        assertTrue("hard hits should pulse harder ($strong vs $weak)", strong > weak + 0.1f)
        assertTrue("strength must stay in range", strong <= 1f && weak >= PulseTracker.STRENGTH_FLOOR * PulseTracker.ENERGY_BASE)
    }

    @Test
    fun `a huge off-grid accent still fires`() {
        val t = tracker()
        var locked = false
        var accentFired = false
        val accentFrame = WARMUP + 500 + 20 // exactly between two kicks
        for (frame in 0 until WARMUP + 600) {
            val flux = if (frame == accentFrame) 3f else kickAndOffbeatFlux(frame, 40, offbeat = 0.03f)
            t.step(flux, 0.4f)
            if (frame == accentFrame - 1) locked = t.confidence >= PulseTracker.LOCK_ENTER
            if (frame == accentFrame && t.beat) accentFired = true
        }
        assertTrue("fixture must be locked before the accent", locked)
        assertTrue("an unmistakable accent must not be swallowed by the grid", accentFired)
    }

    @Test
    fun `aperiodic material never locks and keeps a softened reactive pulse`() {
        val t = tracker()
        // Irregular spikes: prime-ish gaps with varying amplitude - no tempo.
        val gaps = intArrayOf(23, 41, 31, 53, 29, 47, 37, 59, 43, 61)
        var next = 30
        var gi = 0
        var beats = 0
        var maxStrength = 0f
        var maxConfidence = 0f
        for (frame in 0 until 2400) {
            val spike = frame == next
            if (spike) {
                gi = (gi + 1) % gaps.size
                next += gaps[gi]
            }
            t.step(if (spike) 0.8f + 0.4f * (gi % 3) else 0.03f, 0.3f)
            if (frame >= WARMUP) {
                if (t.beat) {
                    beats++
                    maxStrength = maxOf(maxStrength, t.strength)
                }
                maxConfidence = maxOf(maxConfidence, t.confidence)
            }
        }
        assertTrue("aperiodic spikes should still pulse the visuals, got $beats", beats > 10)
        assertTrue("but must never report a confident lock, got $maxConfidence", maxConfidence < PulseTracker.LOCK_ENTER)
        assertTrue(
            "unlocked pulses are softened, got $maxStrength",
            maxStrength <= PulseTracker.UNLOCKED_SCALE + 1e-4f,
        )
    }

    @Test
    fun `silence coasts calmly instead of re-normalising into flashes`() {
        val t = tracker()
        var beatsInBreak = 0
        var beatsAfter = 0
        for (frame in 0 until WARMUP + 1800) {
            val inBreak = frame in WARMUP + 400 until WARMUP + 700 // a 5 s breakdown
            val flux = if (inBreak) 0.005f else kickAndOffbeatFlux(frame, 40, offbeat = 0.03f)
            t.step(flux, if (inBreak) 0.02f else 0.4f)
            if (t.beat) {
                when {
                    inBreak -> beatsInBreak++
                    frame >= WARMUP + 700 -> beatsAfter++
                }
            }
        }
        assertEquals("a breakdown must stay visually calm", 0, beatsInBreak)
        assertTrue("the pulse must return with the music, got $beatsAfter", beatsAfter > 20)
    }

    @Test
    fun `beat phase ramps between beats and resets on them`() {
        val t = tracker()
        var checked = 0
        var prevPhase = 0f
        for (frame in 0 until WARMUP + 400) {
            t.step(kickAndOffbeatFlux(frame, 40, offbeat = 0.03f), 0.4f)
            if (frame >= WARMUP && t.confidence >= PulseTracker.LOCK_ENTER) {
                assertTrue("phase must stay in 0..1, got ${t.phase}", t.phase in 0f..1f)
                if (t.beat) {
                    assertTrue("phase should be near a grid point on beats, got ${t.phase}", t.phase <= 0.3f || t.phase >= 0.7f)
                } else if (prevPhase in 0.1f..0.8f) {
                    assertTrue("phase must not run backwards mid-interval", t.phase >= prevPhase - 1e-4f)
                }
                prevPhase = t.phase
                checked++
            }
        }
        assertTrue("the fixture must actually lock", checked > 200)
    }

    @Test
    fun `macro energy follows the arc of the song`() {
        val f = PulseTracker.EnergyFollower(HOP)
        var quietEarly = 0f
        var loud = 0f
        var quietAfter = 1f
        for (frame in 0 until 3600) {
            val rms =
                when {
                    frame < 1200 -> 0.12f // verse
                    frame < 2400 -> 0.5f // chorus
                    else -> 0.12f // outro
                }
            val e = f.step(rms)
            when {
                frame in 600 until 1200 -> quietEarly = maxOf(quietEarly, e)
                frame in 1800 until 2400 -> loud = maxOf(loud, e)
                frame >= 3300 -> quietAfter = minOf(quietAfter, e)
            }
        }
        assertTrue("the chorus should read near full energy, got $loud", loud > 0.85f)
        assertTrue("the outro must fall well below the chorus, got $quietAfter", quietAfter < 0.6f)
        assertTrue("energy is track-relative: a steady verse converges high, got $quietEarly", quietEarly > 0.5f)
        assertTrue("silence reports zero", PulseTracker.EnergyFollower(HOP).step(0f) == 0f)
    }

    @Test
    fun `quiet passages soften the same hit`() {
        // Identical flux spikes ride identical grids, but one tracker hears
        // them inside a quiet track: its strengths must come out lower.
        val loud = tracker()
        val quiet = tracker()
        var loudStrength = 0f
        var quietStrength = 0f
        for (frame in 0 until WARMUP + 400) {
            val flux = kickAndOffbeatFlux(frame, 40, offbeat = 0.03f)
            loud.step(flux, 0.5f)
            // A pre-established loud peak that the quiet passage sits under.
            quiet.step(flux, if (frame < 300) 0.9f else 0.1f)
            if (frame >= WARMUP && loud.beat) loudStrength = maxOf(loudStrength, loud.strength)
            if (frame >= WARMUP && quiet.beat) quietStrength = maxOf(quietStrength, quiet.strength)
        }
        assertTrue("both fixtures must fire (loud=$loudStrength quiet=$quietStrength)", loudStrength > 0f && quietStrength > 0f)
        assertTrue("a quiet passage should pulse more gently ($quietStrength vs $loudStrength)", quietStrength < loudStrength)
    }

    @Test
    fun `live extractor and offline replay agree on every field`() {
        // The export path replays cached flux/rms curves; a single frame of
        // disagreement would make an exported video pulse where playback
        // did not. Run the REAL extractor (bands in, features out), then
        // replay its stored curves.
        val extractor = FeatureExtractor(64, hopRateHz = HOP)
        val waveform = FloatArray(128)
        val n = 1500
        val live = ArrayList<AudioFeatures>(n)
        for (frame in 0 until n) {
            val kick = frame % 30 == 0
            val hat = !kick && frame % 15 == 0
            val bands =
                FloatArray(64) { b ->
                    when {
                        b < 8 && kick -> 0.9f
                        b >= 48 && hat -> 0.6f
                        else -> 0.05f
                    }
                }
            live += extractor.extract(bands, waveform, 44100)
        }
        val flux = FloatArray(n) { live[it].flux }
        val rms = FloatArray(n) { live[it].rms }
        val replay =
            PulseTracker.decidePulse(
                flux,
                rms,
                HOP,
                FeatureExtractor.SIGMA_DEFAULT,
                FeatureExtractor.INTERVAL_MS_DEFAULT,
            )
        assertTrue("fixture must contain beats", live.any { it.beat })
        for (i in 0 until n) {
            assertEquals("beat at frame $i", live[i].beat, replay.beat[i])
            assertEquals("strength at frame $i", live[i].beatStrength, replay.strength[i], 0f)
            assertEquals("phase at frame $i", live[i].beatPhase, replay.phase[i], 0f)
            assertEquals("confidence at frame $i", live[i].pulseConfidence, replay.confidence[i], 0f)
            assertEquals("energy at frame $i", live[i].macroEnergy, replay.energy[i], 0f)
        }
    }

    @Test
    fun `beat decision ignores the rms curve`() {
        // decideBeats replays with flux alone; if acceptance ever depended on
        // energy, that flux-only replay would silently drift from playback.
        val flux = FloatArray(2400) { kickAndOffbeatFlux(it, 40) }
        val withRms =
            PulseTracker.decidePulse(
                flux,
                FloatArray(2400) { 0.5f },
                HOP,
                FeatureExtractor.SIGMA_DEFAULT,
                FeatureExtractor.INTERVAL_MS_MIN,
            )
        val withoutRms =
            PulseTracker.decidePulse(
                flux,
                FloatArray(0),
                HOP,
                FeatureExtractor.SIGMA_DEFAULT,
                FeatureExtractor.INTERVAL_MS_MIN,
            )
        assertTrue("fixture must contain beats", withRms.beat.any { it })
        for (i in flux.indices) {
            assertEquals("beat at frame $i", withRms.beat[i], withoutRms.beat[i])
        }
        assertFalse("but strength must differ (energy shapes it)", withRms.strength.contentEquals(withoutRms.strength))
    }

    @Test
    fun `legacy beat flags without strength still read as a full impulse`() {
        // Synthesised features and pre-tracker cache entries carry beat=true
        // with no strength; scenes read beatImpulse and must see the
        // historical full-strength kick, not silence.
        assertEquals(1f, AudioFeatures.empty().copy(beat = true).beatImpulse, 0f)
        assertEquals(0.62f, AudioFeatures.empty().copy(beat = true, beatStrength = 0.62f).beatImpulse, 0f)
        assertEquals(0f, AudioFeatures.empty().copy(beat = false, beatStrength = 0.62f).beatImpulse, 0f)
    }
}
