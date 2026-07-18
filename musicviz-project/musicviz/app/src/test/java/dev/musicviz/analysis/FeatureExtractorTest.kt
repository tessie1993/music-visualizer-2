package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {
    private fun pulseBands(on: Boolean): FloatArray = FloatArray(64) { if (on) 0.9f else 0.05f }

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
}
