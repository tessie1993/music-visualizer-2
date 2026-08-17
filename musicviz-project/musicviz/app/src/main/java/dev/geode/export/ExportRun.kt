package dev.geode.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The lifetime a video render actually needs, and the progress the notification
 * reads.
 *
 * ## Why it is not the ViewModel's
 *
 * A 4K render of a four-minute track is tens of minutes of GPU work. It used to
 * run on `viewModelScope`, and `onCleared` cancelled it deliberately — so
 * switching apps, letting the screen time out, or the process being reclaimed
 * threw the render away with no message and no partial recovery. A creator who
 * started a render and answered a text came back to nothing. That is the most
 * rage-inducing failure this feature has, and it was by construction.
 *
 * The scope here outlives every screen. On its own that would only move the
 * problem — a background process is still killable — so [ExportService] runs
 * alongside it for as long as [running] is true, which is what actually buys
 * the render the right to keep going.
 *
 * A process-wide holder rather than an injected dependency, matching the other
 * buses in this codebase: the service, the ViewModel and the notification all
 * need the same single answer to "is a render happening, and how far in", and
 * there is exactly one render at a time by design.
 */
object ExportRun {
    /**
     * Survives every screen. `SupervisorJob` so a failed render does not poison
     * the scope for the next one.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What a render is doing, for the notification and the in-app indicator. */
    data class State(
        val running: Boolean = false,
        /** 0..1, or null while the length is still unknown. */
        val progress: Float? = null,
        /** What is being rendered, for the notification's text. */
        val label: String = "",
        /**
         * Time left, in whole seconds, or null while an estimate would be a
         * guess. See [RenderEta] for why it stays quiet at the start.
         */
        val secondsRemaining: Long? = null,
    )

    private val eta = RenderEta()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val running: Boolean get() = _state.value.running

    /** Marks a render as started. [label] names the track in the notification. */
    fun begin(label: String) {
        eta.reset()
        _state.value = State(running = true, progress = null, label = label)
    }

    /**
     * Publishes render progress; ignored when no render is running.
     *
     * [atMs] is injectable so the estimate can be tested without a clock.
     */
    fun publish(
        progress: Float,
        atMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        val current = _state.value
        if (!current.running) return
        val clamped = progress.coerceIn(0f, 1f)
        _state.value = current.copy(progress = clamped, secondsRemaining = eta.sample(clamped, atMs))
    }

    /**
     * Marks the render as over, however it ended.
     *
     * Saved, cancelled and failed all land here: the service's only question is
     * whether to keep the process alive, and the answer is the same for all
     * three. What actually happened is reported by the export state the UI
     * already reads.
     */
    fun finish() {
        eta.reset()
        _state.value = State()
    }
}
