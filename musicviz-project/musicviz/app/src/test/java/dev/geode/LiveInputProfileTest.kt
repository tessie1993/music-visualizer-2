package dev.geode

import dev.geode.analysis.AnalysisEngine
import dev.geode.analysis.BeatTuning
import dev.geode.analysis.LiveInputProfile
import dev.geode.engine.audio.SampleRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract between [LiveInputProfile] and the engine clamps.
 *
 * Historical bug: ROOM shipped beatSigma = 1.15 and INSTRUMENT 1.0 sigma /
 * 130 ms - all below [BeatTuning.SENSITIVITY_MIN] / [BeatTuning.INTERVAL_MS_MIN].
 * [AnalysisEngine] clamped them on write, so the profiles' documented extra
 * sensitivity never ran, while the Settings sliders showed the unclamped
 * numbers the engine was not using. The profiles are pinned to the engine
 * floors (a deliberate limitation until a DSP review widens the engine range),
 * and this test makes any future drift below a floor fail the build.
 */
class LiveInputProfileTest {
    @Test
    fun `every profile survives the engine clamp unchanged`() {
        for (profile in LiveInputProfile.entries) {
            assertEquals(
                "$profile beatSigma must not be silently clamped",
                profile.beatSigma,
                profile.beatSigma.coerceIn(BeatTuning.SENSITIVITY_MIN, BeatTuning.SENSITIVITY_MAX),
                0f,
            )
            assertEquals(
                "$profile beatIntervalMs must not be silently clamped",
                profile.beatIntervalMs,
                profile.beatIntervalMs.coerceIn(BeatTuning.INTERVAL_MS_MIN, BeatTuning.INTERVAL_MS_MAX),
                0f,
            )
        }
    }

    @Test
    fun `profile values round-trip through a running engine`() {
        // The acceptance criterion itself: what a profile writes is what the
        // engine runs, so Settings can never display a value the engine isn't
        // using.
        val engine = AnalysisEngine(SampleRing(1 shl 16, 2))
        for (profile in LiveInputProfile.entries) {
            engine.beatSensitivity = profile.beatSigma
            assertEquals("$profile beatSigma", profile.beatSigma, engine.beatSensitivity, 0f)
            engine.beatMinIntervalMs = profile.beatIntervalMs
            assertEquals("$profile beatIntervalMs", profile.beatIntervalMs, engine.beatMinIntervalMs, 0f)
        }
    }

    @Test
    fun `profile sensitivity ordering matches the documented intent`() {
        // ROOM and INSTRUMENT were designed to be the most sensitive profiles;
        // pinned to the floor they must stay at least as sensitive as the rest.
        for (profile in listOf(LiveInputProfile.ROOM, LiveInputProfile.INSTRUMENT)) {
            assertTrue(
                "$profile must be more beat-sensitive than SPEAKER",
                profile.beatSigma < LiveInputProfile.SPEAKER.beatSigma,
            )
        }
        // VOICE deliberately goes the other way on the gap floor: syllables
        // are not beats, so it needs the longest refractory of any profile.
        for (profile in LiveInputProfile.entries) {
            assertTrue(
                "VOICE must have the longest gap floor, but $profile matches or exceeds it",
                profile == LiveInputProfile.VOICE || profile.beatIntervalMs < LiveInputProfile.VOICE.beatIntervalMs,
            )
        }
    }
}
