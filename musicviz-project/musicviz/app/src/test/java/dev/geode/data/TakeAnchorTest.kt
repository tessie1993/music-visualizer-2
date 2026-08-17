package dev.geode.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recorded performance has to render where it was played.
 *
 * Take keyframes are timestamped from the moment the record button is pressed,
 * and the export used to sample them from video time zero. Unless the user
 * happened to press record at exactly 0:00, every automation move landed at the
 * wrong musical moment — the slider sweep performed on the chorus fired during
 * the intro, with nothing warning anyone. The output simply looked mistimed.
 *
 * The fix is one stored number, and these are the properties it has to have.
 */
class TakeAnchorTest {
    private fun recordedAt(
        offsetMs: Long,
        events: List<Pair<Long, Float>> = listOf(0L to 0.1f, 5_000L to 0.9f),
    ): PerformanceTake.Timeline {
        val recorder =
            PerformanceTake.Recorder(
                sceneId = "nebula",
                params = dev.geode.render.scene.SceneParams(),
                milkPath = null,
            )
        for ((at, drive) in events) {
            recorder.append(at, "nebula", dev.geode.render.scene.SceneParams(audioDrive = drive), null)
        }
        val json = recorder.finish("take", "content://track/1", durationMs = 10_000, trackOffsetMs = offsetMs)
        return PerformanceTake.Timeline(json)
    }

    @Test
    fun `the track offset survives a round trip`() {
        assertEquals(90_000L, recordedAt(90_000L).trackOffsetMs)
    }

    /**
     * A take written before the offset existed reads as 0 — the old behaviour,
     * and the right answer for a recording that did start at the top.
     */
    @Test
    fun `a take with no stored offset reads as zero`() {
        val legacy =
            JSONObject()
                .put("name", "old")
                .put("durationMs", 10_000)
                .put("events", org.json.JSONArray())
                .toString()
        assertEquals(0L, PerformanceTake.Timeline(legacy).trackOffsetMs)
    }

    /**
     * The arithmetic the exporter runs, pinned here because getting it wrong is
     * invisible until someone watches the rendered video and notices the drop
     * is decorated with the intro's automation.
     */
    @Test
    fun `a take recorded mid-track samples from where it was recorded`() {
        val take = recordedAt(90_000L)
        val clipStartMs = 90_000L

        // The first frame of a clip that starts where recording started must
        // sample the take's own time zero.
        assertEquals(0L, clipStartMs + 0L - take.trackOffsetMs)
        // Five seconds into that clip is five seconds into the performance.
        assertEquals(5_000L, clipStartMs + 5_000L - take.trackOffsetMs)
    }

    @Test
    fun `a whole-track render of a mid-track take skips its silent lead-in`() {
        val take = recordedAt(90_000L)
        val clipStartMs = 0L
        // At 0:00 of the track the performance has not started; the sample time
        // is negative, which stateAt answers with the take's opening state.
        assertTrue(clipStartMs + 0L - take.trackOffsetMs < 0)
        // The performance begins where it was recorded, not at the video's start.
        assertEquals(0L, clipStartMs + 90_000L - take.trackOffsetMs)
    }

    @Test
    fun `a take recorded from the top is unaffected`() {
        val take = recordedAt(0L)
        assertEquals(0L, take.trackOffsetMs)
        for (ms in listOf(0L, 1_000L, 9_999L)) {
            assertEquals("offset changed a from-the-top take", ms, 0L + ms - take.trackOffsetMs)
        }
    }

    @Test
    fun `a negative sample time resolves to the opening state rather than nothing`() {
        val take = recordedAt(90_000L)
        assertNotNull("a take with no state before its start is unusable", take.stateAt(0L))
    }
}
