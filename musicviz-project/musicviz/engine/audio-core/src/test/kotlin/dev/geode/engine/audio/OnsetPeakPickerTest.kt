package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Causal peak picking over the onset envelope.
 *
 * `docs/quality/bar-visualizer.md` §2.4: "Peak-picking uses an *adaptive*
 * threshold (moving mean/median of the onset envelope + delta) with a
 * refractory period", and "Raw energy thresholds fire on sustained loudness
 * and miss soft transients … fixed thresholds break across genres/volumes".
 * The three tests that matter here are those three sentences.
 *
 * The threshold is built from a moving **median** and the spread around it,
 * not a mean and a standard deviation. Onsets are the outliers in their own
 * window, so they drag a mean up and inflate a standard deviation — the
 * detector becomes less sensitive exactly where the music is busiest, which is
 * the "misses the fill" failure. A median barely moves.
 */
class OnsetPeakPickerTest {
    private val hopRateHz = 100f

    private fun picker(
        sensitivity: Float = 3f,
        refractorySeconds: Float = 0.06f,
    ) = OnsetPeakPicker(
        hopRateHz = hopRateHz,
        sensitivity = sensitivity,
        refractorySeconds = refractorySeconds,
    )

    /** Impulses every [everyFrames] frames on a quiet floor. */
    private fun pulseTrain(
        frames: Int,
        everyFrames: Int,
        amplitude: Float = 1f,
        floor: Float = 0.01f,
    ) = FloatArray(frames) { i -> if (i % everyFrames == 0) amplitude else floor }

    private fun onsetsIn(
        picker: OnsetPeakPicker,
        curve: FloatArray,
    ): List<Int> = curve.indices.filter { picker.accept(curve[it]) }

    @Test
    fun `a clean pulse train is picked at the pulse rate`() {
        val curve = pulseTrain(frames = 1000, everyFrames = 50)
        val hits = onsetsIn(picker(), curve)
        // 20 pulses; the first few pass while the window fills, so allow one.
        assertTrue("picked ${hits.size}", hits.size in 19..20)
        for (h in hits) assertEquals("onset at $h is not on a pulse", 0, h % 50)
    }

    /**
     * The "fixed thresholds break across volumes" failure. The same pattern a
     * hundred times louder must give the same onsets, frame for frame.
     */
    @Test
    fun `the same pattern at any level gives the same onsets`() {
        val quiet = onsetsIn(picker(), pulseTrain(1000, 50, amplitude = 0.01f, floor = 1e-4f))
        val loud = onsetsIn(picker(), pulseTrain(1000, 50, amplitude = 1f, floor = 0.01f))
        assertEquals(quiet, loud)
    }

    /** The "fires on sustained loudness" failure. A held level is not an onset. */
    @Test
    fun `a sustained high level produces no onsets after the step`() {
        val curve = FloatArray(1000) { i -> if (i < 100) 0.01f else 1f }
        val hits = onsetsIn(picker(), curve)
        assertTrue("picked $hits", hits.all { it < 120 })
    }

    /**
     * The "misses soft transients" failure, and the reason for the median: a
     * dense run of onsets must not raise the threshold out of its own reach.
     */
    @Test
    fun `a dense run stays detected instead of raising its own threshold`() {
        val curve = pulseTrain(frames = 1000, everyFrames = 10)
        val hits = onsetsIn(picker(refractorySeconds = 0.05f), curve)
        assertTrue("picked only ${hits.size} of ~100", hits.size > 95)
    }

    @Test
    fun `the refractory window suppresses a double trigger`() {
        val curve = FloatArray(600) { 0.01f }
        curve[300] = 1f
        curve[302] = 1f // 20 ms later
        val hits = onsetsIn(picker(refractorySeconds = 0.06f), curve)
        assertEquals(listOf(300), hits)
    }

    @Test
    fun `silence produces nothing`() {
        assertTrue(onsetsIn(picker(), FloatArray(600)).isEmpty())
    }

    @Test
    fun `a higher sensitivity value picks fewer onsets`() {
        val curve =
            FloatArray(1200) { i ->
                when {
                    i % 50 == 0 -> 1f // strong
                    i % 25 == 0 -> 0.15f // weak, between the strong ones
                    else -> 0.01f
                }
            }
        val loose = onsetsIn(picker(sensitivity = 2f), curve).size
        val strict = onsetsIn(picker(sensitivity = 8f), curve).size
        assertTrue("loose $loose, strict $strict", strict < loose)
    }

    /**
     * Strength has to grade the hit, or every visual event looks the same.
     *
     * It grades against the track's own recent peaks, which is the only thing
     * a causal detector can compare to — the loud hit comes first here for
     * exactly that reason. Nothing at the moment of a soft hit can tell you a
     * louder one is coming later, so a soft-then-loud pair is not a gradeable
     * signal in real time, and asserting otherwise would be asserting
     * clairvoyance.
     */
    @Test
    fun `strength grades a hit against the track's recent peaks`() {
        val picker = picker(refractorySeconds = 0.05f)
        val curve = FloatArray(1200) { 0.01f }
        curve[600] = 3f
        curve[900] = 0.3f
        val strengths = mutableListOf<Float>()
        for (v in curve) if (picker.accept(v)) strengths += picker.strength
        assertEquals(2, strengths.size)
        assertTrue("hard ${strengths[0]}, soft ${strengths[1]}", strengths[1] < strengths[0])
        assertTrue("out of range: $strengths", strengths.all { it in 0f..1f })
    }

    /**
     * Everything the picker holds describes one piece of music, so a reset has
     * to leave it indistinguishable from a fresh instance — otherwise the
     * offline replay, which always starts cold, diverges from live playback.
     */
    @Test
    fun `reset returns the picker to a fresh state`() {
        val curve = pulseTrain(frames = 1000, everyFrames = 50)
        val expected = onsetsIn(picker(), curve)

        val reused = picker()
        onsetsIn(reused, pulseTrain(frames = 500, everyFrames = 37, amplitude = 2f))
        reused.reset()
        assertEquals(expected, onsetsIn(reused, curve))
    }
}
