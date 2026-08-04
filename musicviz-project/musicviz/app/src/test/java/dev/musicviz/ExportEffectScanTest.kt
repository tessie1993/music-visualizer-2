package dev.musicviz

import dev.musicviz.export.VideoExporter
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.ui.PerformanceTake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Export-side effect gating for replayed takes and the beam.
 *
 * The exporter allocates FlowField/RippleSim ONCE, before its frame loop, but
 * a take supplies each frame's own parameters - so the allocate-or-not answer
 * has to come from the WHOLE performance. The bug this pins: the decision
 * used to sample only the take's end state, so a set that toggled Flow on
 * mid-song and off again before the end exported with no field allocated and
 * the effect silently missing from the entire video.
 *
 * Also pins the beam's trail gate: the live renderer persists BeamScene's
 * canvas regardless of the Trails toggle (phosphor - the decay between
 * frames IS the afterglow), and the export used to hard-clear it into a
 * single-frame wire. The gate and retention remap here must stay identical
 * to VisualizerRenderer's.
 *
 * Robolectric because takes are org.json, which the mockable android.jar
 * stubs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportEffectScanTest {
    private val flat = SceneParams.DEFAULT

    /** Frames a five-second render at 30 fps asks the scan about. */
    private val fps = 30
    private val totalFrames = 5_000 * fps / 1000

    /** A take toggling [mutate] on over [onMs, offMs) and off either side. */
    private fun toggledTake(
        onMs: Long,
        offMs: Long,
        mutate: SceneParams.(Boolean) -> SceneParams,
    ): PerformanceTake.Timeline {
        val rec = PerformanceTake.Recorder("nebula", flat.mutate(false), null)
        rec.append(onMs, "nebula", flat.mutate(true), null)
        rec.append(offMs, "nebula", flat.mutate(false), null)
        return PerformanceTake.Timeline(rec.finish("t", null, 5_000L))
    }

    /** The exact lambda shape the ViewModel hands the exporter. */
    private fun paramsAt(take: PerformanceTake.Timeline): (Long) -> SceneParams = { ms -> take.stateAt(ms)?.params ?: flat }

    @Test
    fun aTakeTogglingFlowOnMidSongAndOffAgainStillAllocatesTheField() {
        val take = toggledTake(1_000L, 2_000L) { copy(flowEnabled = it) }
        val use = VideoExporter.scanEffectUse(paramsAt(take), flat, totalFrames, fps)
        assertTrue("the whole-timeline scan must see the mid-song Flow window", use.flowField)
        // Witness for the bug: the take's END state - all the old decision
        // looked at - has Flow off again, so end-state sampling skipped the
        // allocation and the exported video lost the effect entirely.
        assertFalse(take.stateAt(4_999L)!!.params.flowEnabled)
    }

    @Test
    fun aTakeTogglingRippleOnMidSongAndOffAgainStillAllocatesTheSim() {
        val take = toggledTake(1_000L, 2_000L) { copy(rippleOverlayEnabled = it) }
        val use = VideoExporter.scanEffectUse(paramsAt(take), flat, totalFrames, fps)
        assertTrue("the whole-timeline scan must see the mid-song ripple window", use.rippleOverlay)
        assertFalse(take.stateAt(4_999L)!!.params.rippleOverlayEnabled)
    }

    @Test
    fun aTakeThatNeverEnablesAnEffectAllocatesNothing() {
        // The other half of the allocate-or-not question: a take that never
        // asks for the effect must not pay for the service's FBOs.
        val rec = PerformanceTake.Recorder("nebula", flat, null)
        rec.append(1_000L, "nebula", flat.copy(speed = 2f), null)
        val take = PerformanceTake.Timeline(rec.finish("t", null, 5_000L))
        val use = VideoExporter.scanEffectUse(paramsAt(take), flat, totalFrames, fps)
        assertFalse(use.flowField)
        assertFalse(use.rippleOverlay)
    }

    @Test
    fun anEffectLiveAtTheVeryLastFrameIsStillSeen() {
        // The scan samples the loop's own timestamps, so a toggle that lands
        // between the final frame and the end of the audio must still count.
        val lastFrameMs = (totalFrames - 1) * 1000L / fps
        val rec = PerformanceTake.Recorder("nebula", flat, null)
        rec.append(lastFrameMs, "nebula", flat.copy(flowEnabled = true), null)
        val take = PerformanceTake.Timeline(rec.finish("t", null, 5_000L))
        assertTrue(VideoExporter.scanEffectUse(paramsAt(take), flat, totalFrames, fps).flowField)
    }

    @Test
    fun aFlatExportIsDecidedByTheDialogParamsAlone() {
        // No take: every frame renders sceneParams, so those decide - the
        // behaviour every export had before takes existed.
        assertFalse(VideoExporter.scanEffectUse(null, flat, totalFrames, fps).flowField)
        val on = flat.copy(flowEnabled = true, rippleOverlayEnabled = true)
        val use = VideoExporter.scanEffectUse(null, on, totalFrames, fps)
        assertTrue(use.flowField)
        assertTrue(use.rippleOverlay)
    }

    @Test
    fun theBeamPersistsWhateverTheTrailsToggleSays() {
        // Live twin: VisualizerRenderer's `persists` gate. Beam and Curl Flow
        // keep their canvas with Trails off; particles need the toggle; a
        // scene that is none of these hard-clears even with it on.
        assertTrue(VideoExporter.canvasPersists(isCurlFlow = false, isBeam = true, trails = false, isParticle = false))
        assertTrue(VideoExporter.canvasPersists(isCurlFlow = true, isBeam = false, trails = false, isParticle = false))
        assertTrue(VideoExporter.canvasPersists(isCurlFlow = false, isBeam = false, trails = true, isParticle = true))
        assertFalse(VideoExporter.canvasPersists(isCurlFlow = false, isBeam = false, trails = false, isParticle = true))
        assertFalse(VideoExporter.canvasPersists(isCurlFlow = false, isBeam = false, trails = true, isParticle = false))
    }

    @Test
    fun beamRetentionKeepsTheLiveRenderersPhosphorFloorAndSlider() {
        // The live remap is 0.55 + 0.44 * trailLength, capped at 0.99: a
        // floor so the trace always has an afterglow, the Trail length
        // slider setting how long above it. If export and live ever diverge,
        // the render stops matching the screen the user approved it from.
        assertEquals(0.55f, VideoExporter.beamRetention(0f), 1e-6f)
        assertEquals(0.77f, VideoExporter.beamRetention(0.5f), 1e-6f)
        assertEquals(0.99f, VideoExporter.beamRetention(1f), 1e-6f)
        // A modulated slider can leave 0..1; the cap and floor must hold.
        assertEquals(0.99f, VideoExporter.beamRetention(2f), 1e-6f)
        assertTrue(VideoExporter.beamRetention(-1f) >= 0f)
        // The slider must actually do something between its endpoints.
        assertTrue(VideoExporter.beamRetention(0.9f) > VideoExporter.beamRetention(0.1f))
    }
}
