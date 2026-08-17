package dev.geode.ui

import dev.geode.analysis.AudioFeatures
import dev.geode.data.PaletteStore
import dev.geode.data.Preset
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams
import dev.musicviz.render.scene.PMBridge
import kotlinx.coroutines.flow.StateFlow

/**
 * Graded beat impulse a "switch on a musical moment" decision (intelligent
 * visual playlist, Random mode's switch-on-beat) treats as strong enough to
 * act on. Track-relative by construction - [AudioFeatures.beatImpulse] folds
 * in the macro-energy envelope - so this is "one of this song's bigger hits",
 * not an absolute loudness that quiet masters never reach.
 */
private const val STRONG_MOMENT_IMPULSE = 0.6f

/**
 * The standing "change the visuals for me" behaviours - the visual playlist,
 * Random mode and section staging - extracted from [PlayerViewModel]. Their
 * knobs live inside [VizUiState] (they persist through
 * [AutoVisualsPrefsStore] and render with the rest of the visual state), so
 * reads and writes go through [Host]; the tick functions ride the ViewModel's
 * 500 ms housekeeping loop exactly as before.
 */
internal class AutoVisualsController(
    private val prefsStore: AutoVisualsPrefsStore,
    private val host: Host,
) {
    /** The visual state the modes read and drive, and the funnels they drive it through. */
    interface Host {
        val vizState: StateFlow<VizUiState>

        fun updateViz(transform: (VizUiState) -> VizUiState)

        /** Whether music is playing and where the playhead is (500 ms poll). */
        val isPlaying: Boolean
        val positionMs: Long

        /** The live analysis frame, for switch-on-beat decisions. */
        fun features(): AudioFeatures

        /** Keep-this-preset lock: Random must not step while it is held. */
        val presetLocked: Boolean

        fun selectScene(sceneId: String)

        fun applyPreset(preset: Preset)

        /** Queue a .milk onto the engine (the vizApply side-effect channel). */
        fun applyMilk(
            path: String,
            sceneId: String,
        )

        /** Kick off the offline analysis (section staging needs its sections). */
        fun analyzeCurrentTrack()

        /** User .milk files, off the main thread, for Random's milk pool. */
        fun milkFilesAsync(onDone: (List<MilkFile>) -> Unit)
    }

    private var lastVizSwitchMs = 0L
    private var vizPlaylistIndex = 0
    private var lastRandomSwitchMs = 0L
    private val randomRng = kotlin.random.Random(android.os.SystemClock.elapsedRealtime())

    /** Cached .milk files so random picks don't touch disk on the tick loop. */
    private var cachedMilkFiles: List<MilkFile> = emptyList()

    /**
     * Adds an entry to the visual playlist, deduplicated: a preset already in
     * the list (by [VizPlaylistEntry.presetName]) or an identical entry is
     * not appended again. The heart in Visuals › Presets is a membership
     * toggle, but a playlist that could accumulate silent duplicates from any
     * OTHER caller would drain one copy per un-heart while looking removed.
     * The list is persisted (see [AutoVisualsPrefsStore]) so the entries the
     * standing `vizPlaylistEnabled` instruction rotates survive a restart
     * with it.
     */
    fun addToVizPlaylist(entry: VizPlaylistEntry) {
        val s = host.vizState.value
        val duplicate =
            s.vizPlaylist.any {
                it == entry || (entry.presetName != null && it.presetName == entry.presetName)
            }
        if (duplicate) return
        host.updateViz { it.copy(vizPlaylist = it.vizPlaylist + entry) }
        persistAutoVisuals()
    }

    fun removeVizPlaylistAt(index: Int) {
        val s = host.vizState.value
        if (index in s.vizPlaylist.indices) {
            host.updateViz { it.copy(vizPlaylist = it.vizPlaylist.filterIndexed { i, _ -> i != index }) }
            persistAutoVisuals()
        }
    }

    fun setVizPlaylistEnabled(enabled: Boolean) {
        host.updateViz {
            it.copy(
                vizPlaylistEnabled = enabled,
                randomEnabled = if (enabled) false else it.randomEnabled,
            )
        }
        lastVizSwitchMs = android.os.SystemClock.elapsedRealtime()
        persistAutoVisuals()
    }

    fun setVizPlaylistIntelligent(enabled: Boolean) {
        host.updateViz { it.copy(vizPlaylistIntelligent = enabled) }
        persistAutoVisuals()
    }

    fun setVizPlaylistInterval(seconds: Int) {
        host.updateViz { it.copy(vizPlaylistIntervalSec = seconds.coerceIn(AutoVisualsPrefsStore.INTERVAL_SEC)) }
        persistAutoVisuals()
    }

    fun advanceVizPlaylist() {
        val s = host.vizState.value
        if (!s.vizPlaylistEnabled || s.vizPlaylist.size < 2 || !host.isPlaying) return
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastVizSwitchMs
        val intervalMs = s.vizPlaylistIntervalSec * 1000L
        val due =
            if (s.vizPlaylistIntelligent) {
                // Intelligent: after a minimum dwell, switch on a strong
                // musical moment; force a switch at 2x interval so quiet
                // passages still rotate. "Strong" is the tracker's graded beat
                // impulse, which is TRACK-RELATIVE (it folds in the macro-
                // energy envelope) - the absolute rms gate this replaced never
                // opened on a quietly mastered track, so intelligent mode
                // silently degraded into the plain 2x-interval timer there.
                val f = host.features()
                val minDwell = maxOf(8_000L, intervalMs / 2)
                (elapsed >= minDwell && f.beatImpulse >= STRONG_MOMENT_IMPULSE) || elapsed >= intervalMs * 2
            } else {
                elapsed >= intervalMs
            }
        if (!due) return
        lastVizSwitchMs = now
        vizPlaylistIndex = (vizPlaylistIndex + 1) % s.vizPlaylist.size
        applyVizEntry(s.vizPlaylist[vizPlaylistIndex])
    }

    // ---- Random mode ----

    fun setRandomEnabled(enabled: Boolean) {
        host.updateViz {
            it.copy(
                randomEnabled = enabled,
                vizPlaylistEnabled = if (enabled) false else it.vizPlaylistEnabled,
            )
        }
        lastRandomSwitchMs = android.os.SystemClock.elapsedRealtime()
        if (enabled && host.vizState.value.randomIncludeMilk) refreshMilkCache()
        if (enabled) randomStepNow()
        // randomEnabled itself is session-only, but turning Random on clears
        // the PERSISTED vizPlaylistEnabled, and that clear must stick.
        persistAutoVisuals()
    }

    fun setRandomInterval(seconds: Int) {
        host.updateViz { it.copy(randomIntervalSec = seconds.coerceIn(AutoVisualsPrefsStore.INTERVAL_SEC)) }
        persistAutoVisuals()
    }

    fun setRandomOnBeat(enabled: Boolean) {
        host.updateViz { it.copy(randomOnBeat = enabled) }
        persistAutoVisuals()
    }

    fun setRandomIncludeStyles(enabled: Boolean) {
        host.updateViz { it.copy(randomIncludeStyles = enabled) }
        persistAutoVisuals()
    }

    fun setRandomIncludePresets(enabled: Boolean) {
        host.updateViz { it.copy(randomIncludePresets = enabled) }
        persistAutoVisuals()
    }

    fun setRandomIncludeMilk(enabled: Boolean) {
        host.updateViz { it.copy(randomIncludeMilk = enabled) }
        if (enabled) refreshMilkCache()
        persistAutoVisuals()
    }

    fun setRandomizeColors(enabled: Boolean) {
        host.updateViz { it.copy(randomizeColors = enabled) }
        persistAutoVisuals()
    }

    /**
     * Saves the auto-visuals knobs after every setter above - the same
     * write-on-set pattern the GUI/player prefs use, small enough (nine
     * primitives) not to need the live state's coalescing window.
     */
    private fun persistAutoVisuals() {
        prefsStore.save(host.vizState.value)
    }

    private fun refreshMilkCache() {
        host.milkFilesAsync { cachedMilkFiles = it }
    }

    /** Section the playhead is inside, from the offline analysis boundaries. */
    private fun currentSectionIndex(): Int {
        val sections = host.vizState.value.sections
        if (sections.isEmpty()) return 0
        val pos = host.positionMs
        var idx = 0
        for (boundary in sections) {
            if (boundary <= pos) idx++ else break
        }
        return idx
    }

    /** Section last staged, so a look is applied once per section, not per tick. */
    private var lastStagedSection = -1

    /** A new track has a new structure; section 2 of this one is not section 2 of the last. */
    fun onTrackChanged() {
        lastStagedSection = -1
    }

    /**
     * Applies a look when the playhead crosses into a new section.
     *
     * Deterministic by section INDEX rather than "next in the list": the point
     * is that a chorus looks like the chorus every time, so the third section
     * of a track must get the same look on every play - and on the export.
     * Falls back to the current style's presets when no visual playlist has
     * been built, so the mode works without any setup at all.
     */
    fun advanceSectionStaging() {
        val s = host.vizState.value
        if (!s.sectionStaging || !host.isPlaying) return
        val index = currentSectionIndex()
        if (index == lastStagedSection) return
        lastStagedSection = index
        if (s.vizPlaylist.isNotEmpty()) {
            applyVizEntry(s.vizPlaylist[index % s.vizPlaylist.size])
            return
        }
        val pool = s.presets.filter { it.sceneId == s.sceneId }
        if (pool.isNotEmpty()) host.applyPreset(pool[index % pool.size])
    }

    /**
     * Turns section staging on or off.
     *
     * Switching it on kicks off the offline analysis when it has not run:
     * sections come from that pass, and a mode whose input is missing would
     * otherwise just sit there doing nothing with no way to tell why.
     */
    fun setSectionStaging(enabled: Boolean) {
        host.updateViz { it.copy(sectionStaging = enabled) }
        lastStagedSection = -1
        if (enabled && host.vizState.value.sections.isEmpty()) host.analyzeCurrentTrack()
    }

    fun advanceRandomMode() {
        val s = host.vizState.value
        if (!s.randomEnabled || !host.isPlaying) return
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastRandomSwitchMs
        val intervalMs = s.randomIntervalSec * 1000L
        val due =
            if (s.randomOnBeat) {
                // Switch on a strong musical moment after a minimum dwell;
                // force a switch at 2x interval so quiet passages still move.
                // Graded and track-relative, as in advanceVizPlaylist().
                val f = host.features()
                val minDwell = maxOf(6_000L, intervalMs / 2)
                (elapsed >= minDwell && f.beatImpulse >= STRONG_MOMENT_IMPULSE) || elapsed >= intervalMs * 2
            } else {
                elapsed >= intervalMs
            }
        if (!due) return
        randomStepNow()
    }

    /** Jumps to a random style/preset immediately (also used on enable). */
    fun randomStepNow() {
        if (host.presetLocked) return
        val s = host.vizState.value
        lastRandomSwitchMs = android.os.SystemClock.elapsedRealtime()
        val choices = mutableListOf<VizPlaylistEntry>()
        val sceneIds =
            dev.geode.render.VisualizerRenderer.PARTICLE_SCENES +
                dev.geode.render.VisualizerRenderer.SHADER_SCENES.keys
        if (s.randomIncludeStyles) sceneIds.forEach { choices += VizPlaylistEntry(sceneId = it, label = it) }
        if (s.randomIncludePresets) {
            s.presets.forEach { choices += VizPlaylistEntry(sceneId = it.sceneId, presetName = it.name, label = it.name) }
        }
        if (s.randomIncludeMilk && PMBridge.available) {
            cachedMilkFiles.forEach {
                choices += VizPlaylistEntry(sceneId = SceneIds.MILKDROP, milkPath = it.path, label = it.name)
            }
        }
        if (choices.isEmpty()) return
        var pick = choices[randomRng.nextInt(choices.size)]
        // One retry to avoid landing on the scene already showing.
        if (choices.size > 1 && pick.sceneId == s.sceneId && pick.presetName == null && pick.milkPath == null) {
            pick = choices[randomRng.nextInt(choices.size)]
        }
        applyVizEntry(pick)
        if (s.randomizeColors) {
            // The roll is drawn out here, once: update re-runs its block on a
            // losing compare-and-set, and drawing inside it would give the
            // retry different colours from the ones this step decided on.
            val palette = randomRng.nextInt(SceneParams.PALETTES.size)
            val palette2 = randomRng.nextInt(SceneParams.PALETTES.size)
            val paletteMix = if (randomRng.nextBoolean()) randomRng.nextFloat() * 0.6f else 0f
            val colorShift = randomRng.nextFloat()
            host.updateViz { cur ->
                val rolled =
                    cur.params.copy(
                        palette = palette,
                        palette2 = palette2,
                        paletteMix = paletteMix,
                        colorShift = colorShift,
                    )
                // A custom-palette override outranks the PALETTES lookup, so the
                // new indices stay invisible unless both slots are cleared too.
                cur.copy(params = PaletteStore.clear(PaletteStore.clear(rolled), second = true))
            }
        }
    }

    /** Applies a playlist entry: scene, saved preset params and side effects.
     *  The preset's custom shader (if any) is emitted by [Host.applyPreset]. */
    fun applyVizEntry(entry: VizPlaylistEntry) {
        host.selectScene(entry.sceneId)
        if (entry.presetName != null) {
            host.vizState.value.presets
                .firstOrNull { it.name == entry.presetName && it.sceneId == entry.sceneId }
                ?.let { host.applyPreset(it) }
        }
        if (entry.milkPath != null) {
            host.applyMilk(entry.milkPath, entry.sceneId)
        }
    }
}
