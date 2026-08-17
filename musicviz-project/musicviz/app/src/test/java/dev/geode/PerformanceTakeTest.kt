package dev.geode

import dev.geode.data.PerformanceTake
import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gate for performance takes - "record the performance, not the render".
 *
 * Two properties carry the whole feature. A take must REPRODUCE what was
 * performed: replaying it at any instant has to give back the state the live
 * app was in at that instant, including for a viewer that seeks rather than
 * plays straight through. And it must stay SMALL enough to record
 * continuously, which is why keyframes hold only what changed - a take that
 * wrote all hundred-odd parameters per keyframe would be megabytes a minute
 * and nobody would leave recording on.
 *
 * Robolectric because the format is org.json, which the mockable android.jar
 * stubs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceTakeTest {
    private val base = SceneParams.DEFAULT

    private fun recorder(sceneId: String = "nebula") = PerformanceTake.Recorder(sceneId, base, null)

    /** Keyframes are throttled, so tests step well past the minimum gap. */
    private val step = PerformanceTake.MIN_KEYFRAME_GAP_MS + 20L

    @Test
    fun aTakeReplaysTheStateThatWasPerformed() {
        val rec = recorder()
        rec.append(step, "nebula", base.copy(speed = 2f), null)
        rec.append(step * 2, "julia", base.copy(speed = 2f, zoom = 1.7f), null)
        val take = PerformanceTake.Timeline(rec.finish("t", null, step * 3))

        assertEquals(1f, take.stateAt(0)!!.params.speed, 1e-4f)
        assertEquals("nebula", take.stateAt(0)!!.sceneId)
        assertEquals(2f, take.stateAt(step)!!.params.speed, 1e-4f)
        take.stateAt(step * 2)!!.let {
            assertEquals("julia", it.sceneId)
            assertEquals(2f, it.params.speed, 1e-4f)
            assertEquals(1.7f, it.params.zoom, 1e-4f)
        }
    }

    @Test
    fun aKeyframeCarriesForwardEverythingSetBeforeIt() {
        // The failure this guards: storing only what changed, then replaying
        // each keyframe as if it were the whole state - which would snap every
        // untouched parameter back to its default on the next move.
        val rec = recorder()
        rec.append(step, "nebula", base.copy(saturation = 0.3f), null)
        rec.append(step * 2, "nebula", base.copy(saturation = 0.3f, bloom = 0.8f), null)
        rec.append(step * 3, "nebula", base.copy(saturation = 0.3f, bloom = 0.8f, glitch = 0.2f), null)
        val take = PerformanceTake.Timeline(rec.finish("t", null, step * 4))
        val end = take.stateAt(step * 3)!!.params
        assertEquals(0.3f, end.saturation, 1e-4f)
        assertEquals(0.8f, end.bloom, 1e-4f)
        assertEquals(0.2f, end.glitch, 1e-4f)
    }

    @Test
    fun seekingBackwardsLandsOnTheSameStateAsPlayingThrough() {
        val rec = recorder()
        rec.append(step, "nebula", base.copy(speed = 2f), null)
        rec.append(step * 2, "nebula", base.copy(speed = 3f), null)
        rec.append(step * 3, "nebula", base.copy(speed = 4f), null)
        val take = PerformanceTake.Timeline(rec.finish("t", null, step * 4))
        // Play through to the end, then scrub back: a take describes a whole
        // span, so a seek must rebuild rather than leave the cursor stranded.
        take.stateAt(step * 3)
        assertEquals(2f, take.stateAt(step)!!.params.speed, 1e-4f)
        assertEquals(4f, take.stateAt(step * 3)!!.params.speed, 1e-4f)
        assertEquals(1f, take.stateAt(0)!!.params.speed, 1e-4f)
    }

    @Test
    fun aStateAskedForBeforeTheFirstKeyframeIsNothing() {
        val rec = recorder()
        val take = PerformanceTake.Timeline(rec.finish("t", null, 100L))
        assertNull("a take must not invent state before it starts", take.stateAt(-1))
        assertNotNull(take.stateAt(0))
    }

    @Test
    fun everyParameterSurvivesARoundTrip() {
        // The take format goes through PresetStore's serializer, so this is
        // the same all-fields guarantee PresetRoundtripTest pins for presets:
        // a parameter added later is carried without a second list.
        val moved =
            base.copy(
                speed = 2.5f,
                waterLiquid = 0.25f,
                fluidBeatPattern = 3,
                kaleidoscope = true,
                customPaletteId = "mine",
                paletteBaseOverride = 0.42f,
            )
        val rec = recorder()
        rec.append(step, "fluid", moved, null)
        val out = PerformanceTake.Timeline(rec.finish("t", null, step * 2)).stateAt(step)!!.params
        assertEquals(moved, out)
    }

    @Test
    fun keyframesHoldOnlyWhatChanged() {
        // The size property: this is what makes continuous recording viable.
        val rec = recorder()
        rec.append(step, "nebula", base.copy(speed = 1.1f), null)
        rec.append(step * 2, "nebula", base.copy(speed = 1.2f), null)
        val json = rec.finish("t", null, step * 3)
        val events = org.json.JSONObject(json).getJSONArray("events")
        assertTrue("the opening keyframe must be absolute", events.getJSONObject(0).getJSONObject("p").length() > 50)
        for (i in 1 until events.length()) {
            assertEquals(
                "keyframe $i wrote more than the one parameter that moved",
                1,
                events.getJSONObject(i).getJSONObject("p").length(),
            )
        }
        // A minute of one-slider work must stay in kilobytes, not megabytes.
        assertTrue("delta keyframes are not compact enough (${json.length} chars)", json.length < 20_000)
    }

    @Test
    fun anUnchangedStateWritesNoKeyframe() {
        val rec = recorder()
        val before = rec.size
        assertTrue(!rec.append(step, "nebula", base, null))
        assertEquals(before, rec.size)
    }

    @Test
    fun aBurstOfChangesIsThrottled() {
        // A slider drag emits a state change per frame; without the throttle a
        // single gesture would write sixty keyframes a second.
        val rec = recorder()
        var written = 0
        for (ms in 1L..200L) {
            if (rec.append(ms, "nebula", base.copy(speed = 1f + ms / 1000f), null)) written++
        }
        assertTrue("200 ms of dragging wrote $written keyframes", written <= 3)
        assertTrue("the drag recorded nothing at all", written >= 1)
    }

    @Test
    fun theRecorderStopsRatherThanGrowingWithoutBound() {
        // A take nobody can load is worse than a short one, so recording ends
        // at the cap instead of growing forever. Exercised at a small cap: the
        // real one is about an hour of continuous work.
        val cap = 20
        val rec = PerformanceTake.Recorder("nebula", base, null, maxEvents = cap)
        var at = 0L
        repeat(cap * 2) { i ->
            at += step
            rec.append(at, "nebula", base.copy(speed = 1f + i / 100f), null)
        }
        assertEquals(cap, rec.size)
        assertTrue("a full recorder must refuse more", !rec.append(at + step, "julia", base, null))
        assertTrue("the real cap must be worth having", PerformanceTake.MAX_EVENTS > 10_000)
    }

    @Test
    fun styleAndMilkPresetChangesAreRecorded() {
        val rec = PerformanceTake.Recorder("milkdrop", base, "/milk/a.milk")
        rec.append(step, "milkdrop", base, "/milk/b.milk")
        rec.append(step * 2, "water", base, "/milk/b.milk")
        val take = PerformanceTake.Timeline(rec.finish("t", null, step * 3))
        assertEquals("/milk/a.milk", take.stateAt(0)!!.milkPath)
        assertEquals("/milk/b.milk", take.stateAt(step)!!.milkPath)
        take.stateAt(step * 2)!!.let {
            assertEquals("water", it.sceneId)
            assertEquals("a milk preset must persist across a style change", "/milk/b.milk", it.milkPath)
        }
    }

    @Test
    fun theHeaderCarriesWhatTheTakesListShows() {
        val rec = recorder()
        rec.append(step, "nebula", base.copy(speed = 2f), null)
        val take = PerformanceTake.Timeline(rec.finish("Set 1", "content://media/9", 12_345L))
        assertEquals("Set 1", take.name)
        assertEquals("content://media/9", take.trackUri)
        assertEquals(12_345L, take.durationMs)
        assertEquals(2, take.eventCount)
        assertEquals(step, take.lastEventMs())
    }

    @Test
    fun anEmptyTakeIsRecognisedRatherThanReplayedAsNothing() {
        val empty = PerformanceTake.Timeline("""{"name":"x","durationMs":0,"events":[]}""")
        assertTrue(empty.isEmpty)
        assertNull(empty.stateAt(0))
    }
}
