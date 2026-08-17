package dev.geode.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which slice of a track a render covers.
 *
 * Getting a fifteen-second clip used to cost a full-song render plus a second
 * pass through the Studio to trim it — two renders and two files for the thing
 * people most want to post. The rules that matter are that a whole-track
 * request stays on the old path (which skips the seek entirely), and that a
 * request which cannot be honoured degrades to the whole track rather than
 * producing an empty or backwards clip.
 */
class ExportRangeTest {
    private val trackMs = 240_000L

    @Test
    fun `a slice in the middle is taken as asked`() {
        val range = ExportRange.of(startMs = 90_000, endMs = 105_000, trackDurationMs = trackMs)
        assertNotNull(range)
        assertEquals(90_000L, range!!.startMs)
        assertEquals(15_000L, range.durationMs)
        assertEquals(105_000L, range.endMs)
    }

    /**
     * Null, not a range spanning everything: "no range" is what the exporter's
     * existing whole-track path takes, and that path skips the seek — both
     * faster and byte-identical to every export made before ranges existed.
     */
    @Test
    fun `the whole track is no range at all`() {
        assertNull(ExportRange.of(0, trackMs, trackMs))
        assertNull(ExportRange.of(0, trackMs * 2, trackMs))
    }

    @Test
    fun `a range past the end is clamped into the track`() {
        val range = ExportRange.of(startMs = 230_000, endMs = 999_000, trackDurationMs = trackMs)
        assertNotNull(range)
        assertEquals(230_000L, range!!.startMs)
        assertEquals(10_000L, range.durationMs)
    }

    @Test
    fun `a negative start is clamped to the beginning`() {
        val range = ExportRange.of(startMs = -5_000, endMs = 20_000, trackDurationMs = trackMs)
        assertNotNull(range)
        assertEquals(0L, range!!.startMs)
        assertEquals(20_000L, range.durationMs)
    }

    /** Degrade to the whole track rather than render something unusable. */
    @Test
    fun `a slice too short to be worth rendering is refused`() {
        assertNull(ExportRange.of(10_000, 10_500, trackMs))
        assertNull(ExportRange.of(10_000, 10_000, trackMs))
    }

    @Test
    fun `a backwards range is refused`() {
        assertNull(ExportRange.of(60_000, 30_000, trackMs))
    }

    @Test
    fun `an unknown track length is refused`() {
        assertNull(ExportRange.of(0, 15_000, 0))
        assertNull(ExportRange.of(0, 15_000, -1))
    }

    @Test
    fun `the shortest allowed slice is exactly at the limit`() {
        val range = ExportRange.of(0, ExportRange.MIN_DURATION_MS, trackMs)
        assertNotNull(range)
        assertEquals(ExportRange.MIN_DURATION_MS, range!!.durationMs)
    }

    /** Constructing an impossible range is a bug, not a user input. */
    @Test
    fun `the constructor rejects a nonsensical range`() {
        assertThrows(IllegalArgumentException::class.java) { ExportRange(-1, 1_000) }
        assertThrows(IllegalArgumentException::class.java) { ExportRange(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { ExportRange(0, -1_000) }
    }

    /**
     * The exporter samples visual features at `range.startMs + frameTime` while
     * the video and audio are rebased to zero. Pinning the arithmetic here
     * because getting it wrong renders the intro's visuals over the drop's
     * audio, which looks like a broken engine rather than a wrong offset.
     */
    @Test
    fun `every rendered frame samples inside the range and never past it`() {
        val range = ExportRange(90_000, 15_000)
        val fps = 30
        val totalFrames = (range.durationMs * fps / 1000).toInt()
        assertEquals(450, totalFrames)

        val first = range.startMs + 0L
        assertEquals("the first frame is not the start of the range", range.startMs, first)

        // Within one frame period of the end, allowing a millisecond for the
        // integer truncation of a 33.3 ms frame at 30 fps.
        val last = range.startMs + (totalFrames - 1) * 1000L / fps
        assertTrue("the last frame samples past the range: $last", last < range.endMs)
        assertTrue(
            "the last frame stops more than a frame short of the range: $last",
            range.endMs - last <= 1000L / fps + 1,
        )

        for (frame in 0 until totalFrames) {
            val sourceMs = range.startMs + frame * 1000L / fps
            assertTrue("frame $frame samples before the range", sourceMs >= range.startMs)
            assertTrue("frame $frame samples past the range", sourceMs < range.endMs)
        }
    }
}
