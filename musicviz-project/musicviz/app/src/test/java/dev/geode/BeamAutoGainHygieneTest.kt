package dev.geode

import dev.geode.analysis.AudioFeatures
import dev.geode.render.scene.BeamScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [BeamScene.update]'s ingest hygiene: whatever the analyzer sends, nothing
 * non-finite may reach the auto-gain accumulator or the waveform store the
 * texture upload reads. `max()` propagates NaN, so one bad sample would
 * poison the frame's peak - stalling the gain - and draw as garbage beam
 * geometry; the scrub reads it as silence instead, the same at-ingest
 * hygiene the fluid pipeline applies.
 *
 * Robolectric only for the Context the constructor stores; update() itself
 * is pure math, no GL. The private state is read back by reflection, the
 * [ParticleGatingTest] way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeamAutoGainHygieneTest {
    private fun scene() = BeamScene(RuntimeEnvironment.getApplication())

    private fun features(wave: FloatArray) = AudioFeatures(bands = FloatArray(16), waveform = wave)

    private fun field(name: String) = BeamScene::class.java.getDeclaredField(name).also { it.isAccessible = true }

    private fun gainOf(s: BeamScene): Float = field("autoGain").getFloat(s)

    private fun samplesOf(s: BeamScene): FloatArray = field("samples").get(s) as FloatArray

    @Test
    fun aNonFiniteSampleNeverReachesTheGainOrTheTexture() {
        for (poison in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val s = scene()
            val wave = FloatArray(64) { 0.4f }.also { it[13] = poison }
            repeat(300) { s.update(features(wave), 1f / 60f) }
            assertTrue("autoGain went non-finite from $poison", gainOf(s).isFinite())
            for (v in samplesOf(s)) {
                assertTrue("a $poison sample reached the waveform texture upload", v.isFinite())
            }
        }
    }

    @Test
    fun aPoisonedSampleReadsAsSilenceNotAsThePeak() {
        // The scrubbed run must settle to the same gain as the same wave with
        // the bad sample already silent: the poison must neither latch the
        // gain where it stood nor read as an infinitely loud peak.
        fun settledGain(wave: FloatArray): Float {
            val s = scene()
            repeat(300) { s.update(features(wave), 1f / 60f) }
            return gainOf(s)
        }
        val clean = FloatArray(64) { 0.4f }
        val zeroed = clean.copyOf().also { it[13] = 0f }
        val poisoned = clean.copyOf().also { it[13] = Float.NaN }
        assertEquals(settledGain(zeroed), settledGain(poisoned), 0f)
    }

    @Test
    fun theGainStillAdaptsAroundTheScrub() {
        // Guard the guard: scrubbing must not have disabled the auto-gain -
        // quiet material still settles to a higher gain than loud material.
        fun settledGain(level: Float): Float {
            val s = scene()
            repeat(600) { s.update(features(FloatArray(64) { level }), 1f / 60f) }
            return gainOf(s)
        }
        assertTrue("auto-gain no longer adapts", settledGain(0.1f) > settledGain(0.9f) + 0.5f)
    }
}
