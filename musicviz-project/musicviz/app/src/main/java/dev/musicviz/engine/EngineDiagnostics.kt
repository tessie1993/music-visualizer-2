package dev.musicviz.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An immutable reading of the engine's counters at one instant.
 *
 * Detached from the live [EngineDiagnostics] on purpose: a caller that holds a
 * snapshot while rendering a settings screen must not see values shift under it.
 */
data class EngineDiagnosticsSnapshot(
    val activeLeases: Int = 0,
    val pcmOverruns: Long = 0L,
    val pcmFramesSkipped: Long = 0L,
    val analysisSequence: Long = 0L,
    val lastAnalysisLatencyNanos: Long = 0L,
    val droppedFrames: Long = 0L,
    val contextLossCount: Long = 0L,
)

/**
 * Local, bounded engine counters.
 *
 * WHY THESE ARE PRIMITIVE ATOMICS, NOT EVENTS: every `on*` method below is
 * callable from a real-time path - the PCM callback, the analysis worker, the GL
 * thread. Recording an event object, appending to a list, or formatting a string
 * there would allocate per frame and violate the master plan's no-steady-state-
 * allocation gate. So each signal is a counter bump, the storage is fixed size,
 * and all formatting happens in [report], which only ever runs on user action.
 *
 * NOTHING LEAVES THE DEVICE. There is no remote telemetry and no network client;
 * the app ships without the INTERNET permission. [report] produces text the user
 * explicitly asks for and chooses what to do with.
 */
class EngineDiagnostics {
    private val leases = AtomicInteger(0)
    private val overruns = AtomicLong(0L)
    private val framesSkipped = AtomicLong(0L)
    private val analysisSeq = AtomicLong(0L)
    private val analysisLatency = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val contextLoss = AtomicLong(0L)

    fun onLeaseOpened() {
        leases.incrementAndGet()
    }

    /**
     * A lease closed. Clamped at zero because lease closing is idempotent by
     * contract (Phase 1.2) - a double close is legal, and must not make the
     * aggregate demand read as idle while a consumer is still running.
     */
    fun onLeaseClosed() {
        leases.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun onPcmOverrun(skippedFrames: Long) {
        overruns.incrementAndGet()
        framesSkipped.addAndGet(skippedFrames)
    }

    fun onAnalysisFrame(
        sequence: Long,
        latencyNanos: Long,
    ) {
        analysisSeq.set(sequence)
        analysisLatency.set(latencyNanos)
    }

    fun onFrameDropped() {
        dropped.incrementAndGet()
    }

    fun onContextLost() {
        contextLoss.incrementAndGet()
    }

    fun snapshot(): EngineDiagnosticsSnapshot =
        EngineDiagnosticsSnapshot(
            activeLeases = leases.get(),
            pcmOverruns = overruns.get(),
            pcmFramesSkipped = framesSkipped.get(),
            analysisSequence = analysisSeq.get(),
            lastAnalysisLatencyNanos = analysisLatency.get(),
            droppedFrames = dropped.get(),
            contextLossCount = contextLoss.get(),
        )

    /** Clears every counter, e.g. when a new source session starts. */
    fun reset() {
        leases.set(0)
        overruns.set(0L)
        framesSkipped.set(0L)
        analysisSeq.set(0L)
        analysisLatency.set(0L)
        dropped.set(0L)
        contextLoss.set(0L)
    }

    /**
     * A plain-text report for the user to attach to a bug report.
     *
     * Runs on user action only - never per frame. A capability downgrade is
     * stated outright rather than implied by a missing value, so the report is
     * truthful about which engine actually ran.
     */
    fun report(selection: EngineSelection): String {
        val s = snapshot()
        return buildString {
            appendLine("MusicViz engine diagnostics")
            appendLine("---------------------------")
            appendLine("Active engine: ${selection.active}")
            when (selection) {
                is EngineSelection.Active -> Unit
                is EngineSelection.FellBack -> {
                    appendLine("Requested:     ${selection.requested} (not running)")
                    appendLine("Why:           ${selection.reason}")
                }
            }
            appendLine()
            appendLine("Active leases:            ${s.activeLeases}")
            appendLine("PCM overruns:             ${s.pcmOverruns} (${s.pcmFramesSkipped} frames skipped)")
            appendLine("Analysis sequence:        ${s.analysisSequence}")
            appendLine("Last analysis latency:    ${s.lastAnalysisLatencyNanos / 1_000_000.0} ms")
            appendLine("Dropped frames:           ${s.droppedFrames}")
            appendLine("Context loss:             ${s.contextLossCount}")
        }
    }
}
