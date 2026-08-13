package dev.musicviz.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 0.2. Diagnostics exist so later phases can *prove* lease, cursor and
 * frame behaviour instead of asserting it. Two properties matter here:
 *
 * 1. Recording is allocation-free and bounded - it runs on hot paths, so it may
 *    not grow a list or build a string per event.
 * 2. The exported report is local text the user asks for. Nothing leaves the
 *    device; the app ships without the INTERNET permission.
 */
class EngineDiagnosticsTest {
    @Test
    fun `a fresh instance reports zeroes, not nulls`() {
        val snap = EngineDiagnostics().snapshot()
        assertEquals(0, snap.activeLeases)
        assertEquals(0L, snap.pcmOverruns)
        assertEquals(0L, snap.droppedFrames)
        assertEquals(0L, snap.contextLossCount)
        assertEquals(0L, snap.analysisSequence)
    }

    @Test
    fun `counters accumulate`() {
        val d = EngineDiagnostics()
        d.onLeaseOpened()
        d.onLeaseOpened()
        d.onLeaseClosed()
        d.onPcmOverrun(skippedFrames = 128)
        d.onPcmOverrun(skippedFrames = 64)
        d.onFrameDropped()
        d.onContextLost()
        d.onAnalysisFrame(sequence = 42L, latencyNanos = 2_000_000L)

        val snap = d.snapshot()
        assertEquals(1, snap.activeLeases)
        assertEquals(2L, snap.pcmOverruns)
        assertEquals(192L, snap.pcmFramesSkipped)
        assertEquals(1L, snap.droppedFrames)
        assertEquals(1L, snap.contextLossCount)
        assertEquals(42L, snap.analysisSequence)
        assertEquals(2_000_000L, snap.lastAnalysisLatencyNanos)
    }

    @Test
    fun `a snapshot is a detached value, not a live view`() {
        val d = EngineDiagnostics()
        d.onFrameDropped()
        val first = d.snapshot()
        d.onFrameDropped()

        // If snapshot() handed back a live reference, `first` would now read 2.
        assertEquals(1L, first.droppedFrames)
        assertEquals(2L, d.snapshot().droppedFrames)
        assertNotEquals(first, d.snapshot())
    }

    @Test
    fun `lease count never goes negative on unbalanced close`() {
        // close() is idempotent by contract, so a double close must not push the
        // aggregate demand below zero and make the engine look idle while a
        // consumer is still running.
        val d = EngineDiagnostics()
        d.onLeaseOpened()
        d.onLeaseClosed()
        d.onLeaseClosed()
        assertEquals(0, d.snapshot().activeLeases)
    }

    @Test
    fun `the report names every counter and the active generation`() {
        val d = EngineDiagnostics()
        d.onLeaseOpened()
        d.onPcmOverrun(skippedFrames = 8)
        val report =
            d.report(
                selection = EngineGeneration.resolve(EngineGeneration.V2, v2Available = false),
            )

        assertTrue(report.contains("MusicViz engine diagnostics"))
        assertTrue(report.contains("Active leases"))
        assertTrue(report.contains("PCM overruns"))
        assertTrue(report.contains("Dropped frames"))
        assertTrue(report.contains("Context loss"))
        // A fallback must be visible in the exported report, with its reason -
        // this is the artefact a user attaches to a bug report.
        assertTrue(report.contains("LEGACY"))
        assertTrue(report.contains(EngineGeneration.UNAVAILABLE_REASON))
    }

    @Test
    fun `reset clears counters for a new session`() {
        val d = EngineDiagnostics()
        d.onFrameDropped()
        d.onContextLost()
        d.reset()
        val snap = d.snapshot()
        assertEquals(0L, snap.droppedFrames)
        assertEquals(0L, snap.contextLossCount)
    }
}
