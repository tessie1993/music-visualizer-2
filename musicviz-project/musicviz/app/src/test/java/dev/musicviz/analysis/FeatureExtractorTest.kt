package dev.musicviz.analysis

import dev.musicviz.audio.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Guards the beat gate in [FeatureExtractor].
 *
 * Historical bug: the sensitivity control topped out at 4 sigma with a
 * hard-coded 333 ms refractory, so a slow, sparse track (a soft kick a second
 * apart, plus strummed chords and brush hits in between) still flashed on
 * every intermediate transient and the user had no way to turn it down
 * further. The threshold and the minimum gap between beats are now both
 * tunable, over one shared set of bounds that the engine clamp and the
 * Settings sliders read from, so the slider can never saturate silently.
 */
class FeatureExtractorTest {
    private fun pulseBands(on: Boolean): FloatArray = FloatArray(64) { if (on) 0.9f else 0.05f }

    /**
     * A slow track at 60 BPM (one kick per second at 60 Hz) with a softer
     * off-beat transient every 250 ms - the pattern that used to strobe.
     * Twelve real kicks fall inside the measurement window.
     */
    private fun slowTrackBands(frame: Int): FloatArray {
        val kick = frame % 60 == 0
        val minor = !kick && frame % 15 == 0
        return FloatArray(64) { i ->
            when {
                i >= 16 -> 0.05f
                kick -> 0.65f
                minor -> 0.40f
                else -> 0.05f
            }
        }
    }

    /** Beats flagged over the 12 s that follow a 3 s warm-up of the flux history. */
    private fun countSlowTrackBeats(
        sigma: Float,
        intervalMs: Float,
    ): Int {
        val extractor = FeatureExtractor(64, hopRateHz = 60f)
        extractor.beatThresholdSigma = sigma
        extractor.beatMinIntervalMs = intervalMs
        val waveform = FloatArray(128)
        var beats = 0
        for (frame in 0 until 900) {
            val f = extractor.extract(slowTrackBands(frame), waveform, 44100)
            if (frame > 180 && f.beat) beats++
        }
        return beats
    }

    @Test
    fun `detects beats on periodic pulses`() {
        val extractor = FeatureExtractor(64, hopRateHz = 60f)
        val waveform = FloatArray(128)
        var beats = 0
        // 120 BPM at 60 fps = pulse every 30 frames; run 8 seconds.
        for (frame in 0 until 480) {
            val on = frame % 30 == 0
            val f = extractor.extract(pulseBands(on), waveform, 44100)
            if (frame > 120 && f.beat) beats++
        }
        assertTrue("expected several beats, got $beats", beats >= 8)
    }

    @Test
    fun `bpm estimate converges near pulse rate`() {
        val extractor = FeatureExtractor(64, hopRateHz = 60f)
        val waveform = FloatArray(128)
        var bpm = 0f
        for (frame in 0 until 720) {
            val on = frame % 30 == 0 // 120 BPM
            bpm = extractor.extract(pulseBands(on), waveform, 44100).bpm
        }
        assertTrue("bpm $bpm should be close to 120 (or harmonic 60/240)", bpm in 55f..65f || bpm in 110f..130f || bpm in 230f..250f)
    }

    @Test
    fun `band groups reflect spectral placement`() {
        val extractor = FeatureExtractor(64)
        val bands = FloatArray(64)
        for (i in 0 until 8) bands[i] = 1f // bass-only
        val f = extractor.extract(bands, FloatArray(128), 44100)
        assertTrue(f.bass > f.treble)
        assertTrue(f.centroid < 0.3f)
    }

    @Test
    fun `silence yields near-zero features`() {
        val extractor = FeatureExtractor(64)
        val f = extractor.extract(FloatArray(64), FloatArray(128), 44100)
        assertEquals(0f, f.rms, 1e-5f)
        assertEquals(false, f.beat)
    }

    @Test
    fun `defaults reproduce the pre-existing beat gate`() {
        val extractor = FeatureExtractor(64, hopRateHz = 60f)
        assertEquals(2.5f, extractor.beatThresholdSigma, 1e-6f)
        // The refractory used to be the hard-coded (hopRateHz / 3).toInt();
        // the millisecond default must still land on the same frame count so
        // existing users see no change.
        assertEquals(
            (60f / 3f).toInt(),
            (60f * extractor.beatMinIntervalMs / 1000f).roundToInt(),
        )
    }

    @Test
    fun `slow sparse track loses its spurious flashes at the new high-sigma end`() {
        val atDefault = countSlowTrackBeats(FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_DEFAULT)
        val atCeiling = countSlowTrackBeats(FeatureExtractor.SIGMA_MAX, FeatureExtractor.INTERVAL_MS_DEFAULT)
        // Only 12 kicks are real, so the default gate is firing on the
        // in-between transients - that is the complaint being fixed.
        assertTrue("default gate should over-trigger here, got $atDefault", atDefault > 16)
        assertTrue("high sigma should suppress the extras, got $atCeiling vs $atDefault", atCeiling < atDefault / 2)
    }

    @Test
    fun `slow track preset keeps the real kicks and drops the rest`() {
        val atDefault = countSlowTrackBeats(FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_DEFAULT)
        val atPreset = countSlowTrackBeats(FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)
        assertTrue("preset should still track the 12 kicks, got $atPreset", atPreset in 8..13)
        assertTrue("preset should flash less than the default, got $atPreset vs $atDefault", atPreset < atDefault)
    }

    @Test
    fun `minimum beat interval caps the flash rate on its own`() {
        val atDefault = countSlowTrackBeats(FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_DEFAULT)
        val atMaxGap = countSlowTrackBeats(FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_MAX)
        // 1200 ms over a 12 s window cannot yield more than 10 beats.
        assertTrue("rate cap not honoured, got $atMaxGap", atMaxGap <= 10)
        assertTrue("wider gap should flash less, got $atMaxGap vs $atDefault", atMaxGap < atDefault)
    }

    @Test
    fun `engine clamp and settings slider share one range`() {
        // AppShell's sliders use these same constants as their valueRange, so
        // proving the engine clamps to them proves the slider cannot saturate
        // against a tighter clamp.
        val engine = AnalysisEngine(PcmRingBuffer())

        engine.beatThresholdSigma = 99f
        assertEquals(FeatureExtractor.SIGMA_MAX, engine.beatThresholdSigma, 1e-6f)
        engine.beatThresholdSigma = -3f
        assertEquals(FeatureExtractor.SIGMA_MIN, engine.beatThresholdSigma, 1e-6f)

        engine.beatMinIntervalMs = 99_999f
        assertEquals(FeatureExtractor.INTERVAL_MS_MAX, engine.beatMinIntervalMs, 1e-6f)
        engine.beatMinIntervalMs = 0f
        assertEquals(FeatureExtractor.INTERVAL_MS_MIN, engine.beatMinIntervalMs, 1e-6f)

        // The old ceiling was 4 sigma; "much less sensitive" has to be reachable.
        assertTrue(FeatureExtractor.SIGMA_MAX > 4f)
        assertTrue(FeatureExtractor.SIGMA_DEFAULT in FeatureExtractor.SIGMA_MIN..FeatureExtractor.SIGMA_MAX)
        assertTrue(FeatureExtractor.SLOW_SIGMA in FeatureExtractor.SIGMA_MIN..FeatureExtractor.SIGMA_MAX)
        assertTrue(FeatureExtractor.INTERVAL_MS_DEFAULT in FeatureExtractor.INTERVAL_MS_MIN..FeatureExtractor.INTERVAL_MS_MAX)
        assertTrue(FeatureExtractor.SLOW_INTERVAL_MS in FeatureExtractor.INTERVAL_MS_MIN..FeatureExtractor.INTERVAL_MS_MAX)

        // A sigma persisted under the old 1.5..4 bounds must still load as-is.
        engine.beatThresholdSigma = 4f
        assertEquals(4f, engine.beatThresholdSigma, 1e-6f)
    }
}
