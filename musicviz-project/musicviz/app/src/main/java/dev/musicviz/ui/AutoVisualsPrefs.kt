package dev.musicviz.ui

import android.content.Context

/**
 * Persists the auto-visuals KNOBS - how Random mode and the visual playlist
 * rotate the look while a track plays - across restarts, in the same
 * "musicviz-viz" prefs file that carries the live scene state. Same
 * whole-snapshot load/save pattern as [ThemeStore] and [PlayerPrefsStore],
 * with coercion on load so a stored value can never fall outside the range
 * the setters and sliders enforce.
 *
 * Deliberately knobs only:
 *  - `randomEnabled` stays session state. It is owned by the Now Playing
 *    "Auto" button's four-mode cycle, and an app whose visuals start
 *    switching themselves because of a mode left on weeks ago is the same
 *    kind of surprise as [GuiPrefs.micReactive] opening the microphone at
 *    launch. `vizPlaylistEnabled` IS persisted - it is a standing "play my
 *    hearted looks" instruction, and it only does anything once the playlist
 *    has two entries.
 *  - the playlist CONTENTS are rebuilt from the heart buttons in
 *    Visuals › Presets; persisting the entries here would be a second copy
 *    that drifts from the one the user curates there.
 *
 * There is no parallel data class: [applyTo] and [save] read and write
 * [VizUiState] itself, so the defaults live in exactly one place - the state
 * they mirror.
 */
class AutoVisualsPrefsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-viz", Context.MODE_PRIVATE)

    /** Folds the persisted knobs into [state]; absent keys keep its values. */
    fun applyTo(state: VizUiState): VizUiState =
        state.copy(
            randomIntervalSec = prefs.getInt(KEY_RANDOM_INTERVAL, state.randomIntervalSec).coerceIn(INTERVAL_SEC),
            randomOnBeat = prefs.getBoolean(KEY_RANDOM_ON_BEAT, state.randomOnBeat),
            randomIncludeStyles = prefs.getBoolean(KEY_RANDOM_STYLES, state.randomIncludeStyles),
            randomIncludePresets = prefs.getBoolean(KEY_RANDOM_PRESETS, state.randomIncludePresets),
            randomIncludeMilk = prefs.getBoolean(KEY_RANDOM_MILK, state.randomIncludeMilk),
            randomizeColors = prefs.getBoolean(KEY_RANDOM_COLORS, state.randomizeColors),
            vizPlaylistEnabled = prefs.getBoolean(KEY_PLAYLIST_ENABLED, state.vizPlaylistEnabled),
            vizPlaylistIntervalSec = prefs.getInt(KEY_PLAYLIST_INTERVAL, state.vizPlaylistIntervalSec).coerceIn(INTERVAL_SEC),
            vizPlaylistIntelligent = prefs.getBoolean(KEY_PLAYLIST_INTELLIGENT, state.vizPlaylistIntelligent),
        )

    /** Saves the knobs out of [state]; the rest of the state is not touched. */
    fun save(state: VizUiState) {
        prefs
            .edit()
            .putInt(KEY_RANDOM_INTERVAL, state.randomIntervalSec)
            .putBoolean(KEY_RANDOM_ON_BEAT, state.randomOnBeat)
            .putBoolean(KEY_RANDOM_STYLES, state.randomIncludeStyles)
            .putBoolean(KEY_RANDOM_PRESETS, state.randomIncludePresets)
            .putBoolean(KEY_RANDOM_MILK, state.randomIncludeMilk)
            .putBoolean(KEY_RANDOM_COLORS, state.randomizeColors)
            .putBoolean(KEY_PLAYLIST_ENABLED, state.vizPlaylistEnabled)
            .putInt(KEY_PLAYLIST_INTERVAL, state.vizPlaylistIntervalSec)
            .putBoolean(KEY_PLAYLIST_INTELLIGENT, state.vizPlaylistIntelligent)
            .apply()
    }

    companion object {
        /**
         * Bounds for both switch intervals - the one range the setters clamp
         * to, the sliders offer, and this store coerces stored values into.
         */
        val INTERVAL_SEC: IntRange = 5..300

        private const val KEY_RANDOM_INTERVAL = "auto_random_interval_sec"
        private const val KEY_RANDOM_ON_BEAT = "auto_random_on_beat"
        private const val KEY_RANDOM_STYLES = "auto_random_include_styles"
        private const val KEY_RANDOM_PRESETS = "auto_random_include_presets"
        private const val KEY_RANDOM_MILK = "auto_random_include_milk"
        private const val KEY_RANDOM_COLORS = "auto_randomize_colors"
        private const val KEY_PLAYLIST_ENABLED = "auto_playlist_enabled"
        private const val KEY_PLAYLIST_INTERVAL = "auto_playlist_interval_sec"
        private const val KEY_PLAYLIST_INTELLIGENT = "auto_playlist_intelligent"
    }
}
