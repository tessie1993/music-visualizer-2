package dev.musicviz.ui

import android.app.Application
import dev.musicviz.data.PerformanceTake
import dev.musicviz.data.TakeInfo
import dev.musicviz.data.TakeStore
import dev.musicviz.render.scene.SceneParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Replay tick rate; a take's keyframes are ~80 ms apart. */
private const val TAKE_REPLAY_HZ = 30L

/**
 * Performance-take state: what is being recorded, what is being replayed, and
 * the saved takes list.
 */
data class TakeUiState(
    val takes: List<TakeInfo> = emptyList(),
    val recording: Boolean = false,
    val recordedEvents: Int = 0,
    val recordedMs: Long = 0L,
    /** Name of the take currently replaying, or null. */
    val replaying: String? = null,
    val replayMs: Long = 0L,
    val replayEndMs: Long = 0L,
    /**
     * Take the next video export replays, or null for the live settings.
     *
     * PARAMETER AUTOMATION ONLY, with one honest exception: the export scene
     * is built from the take's FIRST scene event (see [exportSceneIdFor]), so
     * a take performed on another style at least renders on the style it was
     * recorded on. A style SWITCH inside a take still cannot be reproduced
     * offline without teaching the exporter to create, swap and release
     * scenes mid-render. Everything else a take holds - every slider, colour,
     * FX and fluid setting, moving exactly as it was performed - does reach
     * the file. The export dialog says so where the take is chosen.
     */
    val exportTake: String? = null,
    /**
     * Transient user-facing note about the last recording action - set when a
     * stop discarded a single-keyframe take, cleared automatically a few
     * seconds later and on the next recording. Without it a discard was
     * indistinguishable from a successful save.
     */
    val note: String? = null,
)

/**
 * Performance takes - record, replay, save, rename - extracted from
 * [PlayerViewModel]. A take is a recording of the visual STATE, so the
 * controller watches the live state through [Host] and drives replays back
 * through the same funnels a hand does.
 */
internal class TakeController(
    application: Application,
    private val scope: CoroutineScope,
    /** The shared store-writer lane; deletes ride it so they stay ordered with preset writes. */
    private val storeWriter: java.util.concurrent.Executor,
    private val host: Host,
) {
    /** The live visual state a recording captures and a replay drives. */
    interface Host {
        val vizState: StateFlow<VizUiState>
        val activeMilkPath: String?

        /** URI of the playing track a finished take is tagged with, or null. */
        val trackUri: String?

        fun selectScene(sceneId: String)

        fun setSceneParams(params: SceneParams)

        /** Queue a .milk the replay reached onto the engine. */
        fun applyMilk(
            path: String,
            sceneId: String,
        )
    }

    private val store = TakeStore(application)

    private val _state = MutableStateFlow(TakeUiState())

    /** Recording/replay state for the Takes tab. */
    val state: StateFlow<TakeUiState> = _state

    private var recorder: PerformanceTake.Recorder? = null
    private var recordStartMs = 0L
    private var recordJob: Job? = null
    private var recordTickJob: Job? = null
    private var replayJob: Job? = null

    /**
     * Starts recording the live visual state.
     *
     * Driven from [Host.vizState] rather than from each control, so anything
     * that moves the visuals is captured by construction - sliders, presets,
     * Randomize, style switches, the auto-switcher - and a control added later
     * is recorded without being told to.
     */
    fun startRecording() {
        if (_state.value.recording) return
        stopReplay()
        val s = host.vizState.value
        recorder = PerformanceTake.Recorder(s.sceneId, s.params, host.activeMilkPath)
        recordStartMs = android.os.SystemClock.elapsedRealtime()
        _state.update { it.copy(recording = true, recordedEvents = 1, recordedMs = 0L, note = null) }
        recordJob =
            scope.launch {
                // One collector on the state flow, not a polling loop: a
                // keyframe exists because something changed, and the recorder
                // throttles the burst a slider drag produces.
                host.vizState.collect { live ->
                    val rec = recorder ?: return@collect
                    val at = android.os.SystemClock.elapsedRealtime() - recordStartMs
                    rec.append(at, live.sceneId, live.params, host.activeMilkPath)
                    _state.update { it.copy(recordedEvents = rec.size, recordedMs = at) }
                    if (!rec.hasRoom) stopRecording()
                }
            }
        // The clock is a clock, not a change counter: the collector above only
        // fires on param traffic, so an untouched recording read "0:00" for
        // its whole length. One tick a second is plenty for a wall clock.
        recordTickJob =
            scope.launch {
                while (true) {
                    delay(1_000L)
                    val at = android.os.SystemClock.elapsedRealtime() - recordStartMs
                    _state.update { if (it.recording) it.copy(recordedMs = at) else it }
                }
            }
    }

    /**
     * Stops recording and saves the take.
     *
     * A take with a single keyframe is discarded: it is a still, and offering
     * to replay one would be offering to replay nothing.
     *
     * The naming and the save go to IO and the name is not returned, because
     * both halves are disk work: [defaultTakeName] reads and fully parses every
     * saved take to find the lowest free number, and the save writes the whole
     * take document - a long performance is megabytes of JSON. The Takes list
     * is where the saved name shows up, and [refresh] republishes it when
     * the write lands.
     */
    fun stopRecording(name: String? = null) {
        val rec = recorder ?: return
        recordJob?.cancel()
        recordJob = null
        recordTickJob?.cancel()
        recordTickJob = null
        recorder = null
        val durationMs = android.os.SystemClock.elapsedRealtime() - recordStartMs
        _state.update { it.copy(recording = false, recordedEvents = 0, recordedMs = 0L) }
        if (rec.size <= 1) {
            // Discarding is right - a one-keyframe take is a still - but
            // doing it SILENTLY made "stop" and "save" look identical. The
            // note is transient: it clears itself, and the next recording
            // clears it early.
            _state.update { it.copy(note = TAKE_DISCARDED_NOTE) }
            scope.launch {
                delay(TAKE_NOTE_MS)
                _state.update { if (it.note == TAKE_DISCARDED_NOTE) it.copy(note = null) else it }
            }
            refresh()
            return
        }
        // Off the recorder before the hop: it is this controller's only
        // reference and startRecording() may replace it before the IO thread
        // gets there.
        val trackUri = host.trackUri
        val requested = name?.takeIf { it.isNotBlank() }
        scope.launch(Dispatchers.IO) {
            val label = requested ?: defaultTakeName()
            store.save(label, rec.finish(label, trackUri, durationMs))
            refresh()
        }
    }

    /** "Take 3" — the lowest number not already on disk. Reads every take; IO only. */
    private fun defaultTakeName(): String {
        val taken = store.list().map { it.name }.toSet()
        var n = 1
        while ("Take $n" in taken) n++
        return "Take $n"
    }

    /**
     * Replays a take over the live visuals.
     *
     * Ticks at [TAKE_REPLAY_HZ] rather than riding the 500 ms housekeeping
     * loop: a take's keyframes are 80 ms apart, so a coarser clock would turn
     * a swept slider into a staircase. The take drives the same
     * [Host.setSceneParams] / [Host.selectScene] funnels a hand does, which is
     * why the renderer's settings fade smooths between keyframes for free.
     */
    fun playTake(name: String) {
        if (_state.value.recording) stopRecording()
        stopReplay()
        replayJob =
            scope.launch {
                // Reading the take back is a whole document parsed - the same
                // work refresh goes to IO for, times one take rather than
                // divided across the list.
                val timeline = withContext(Dispatchers.IO) { store.load(name) } ?: return@launch
                if (timeline.isEmpty) return@launch
                val endMs = maxOf(timeline.lastEventMs(), timeline.durationMs)
                _state.update { it.copy(replaying = name, replayMs = 0L, replayEndMs = endMs) }
                val startedAt = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    val at = android.os.SystemClock.elapsedRealtime() - startedAt
                    timeline.stateAt(at)?.let { state ->
                        if (state.sceneId.isNotEmpty() && state.sceneId != host.vizState.value.sceneId) {
                            host.selectScene(state.sceneId)
                        }
                        if (state.params != host.vizState.value.params) host.setSceneParams(state.params)
                        state.milkPath?.takeIf { it != host.activeMilkPath }?.let { path ->
                            host.applyMilk(path, state.sceneId)
                        }
                    }
                    _state.update { it.copy(replayMs = at) }
                    if (at >= endMs) break
                    delay(1000L / TAKE_REPLAY_HZ)
                }
                _state.update { it.copy(replaying = null, replayMs = 0L, replayEndMs = 0L) }
            }
    }

    /** Stops a replay, leaving the visuals wherever the take had reached. */
    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _state.update { it.copy(replaying = null, replayMs = 0L, replayEndMs = 0L) }
    }

    fun deleteTake(name: String) {
        if (_state.value.replaying == name) stopReplay()
        storeWriter.execute {
            store.delete(name)
            val listed = store.list()
            _state.update { it.copy(takes = listed) }
        }
    }

    /**
     * Renames a take, surfacing [TakeStore.rename]'s answer instead of
     * dropping it: the dialog already refuses blank and duplicate names up
     * front, so a false here is the disk disagreeing with the screen (a file
     * created between the check and the click, an unwritable store) - and a
     * caller that cannot see it closes over a rename that never happened.
     */
    fun renameTake(
        from: String,
        to: String,
    ): Boolean {
        val renamed = store.rename(from, to)
        if (renamed) {
            if (_state.value.replaying == from) stopReplay()
            refresh()
        }
        return renamed
    }

    /**
     * Re-reads the takes list off the main thread.
     *
     * Listing means parsing each take's JSON for its header, and a set of long
     * takes is megabytes of it - cheap in absolute terms, but not something to
     * do on the main thread at launch, which is one of the callers.
     */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val listed = store.list()
            withContext(Dispatchers.Main) { _state.update { it.copy(takes = listed) } }
        }
    }

    /**
     * The take the video export should replay, or null for "render the live
     * settings". Parameter automation only - see [TakeUiState.exportTake].
     */
    fun setExportTake(name: String?) {
        _state.update { it.copy(exportTake = name) }
    }

    /** The chosen export take, loaded from disk; null for live settings. */
    fun loadExportTake(): PerformanceTake.Timeline? =
        _state.value.exportTake
            ?.let { store.load(it) }
            ?.takeUnless { it.isEmpty }

    private companion object {
        /** What the Takes tab shows when a stop discarded a one-keyframe take. */
        const val TAKE_DISCARDED_NOTE = "Nothing changed — take not saved"

        /** How long [TakeUiState.note] stays up before clearing itself. */
        const val TAKE_NOTE_MS = 4_000L
    }
}
