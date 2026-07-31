package dev.musicviz.ui

import java.io.File

/*
 * Persistence seams (architecture step 2 of the refactor plan): interfaces
 * over the JSON/file/preferences stores so ViewModels and future controllers
 * depend on contracts, not concrete storage. The concrete stores keep their
 * exact behavior and formats; these interfaces only name what already
 * exists. Swapping storage (DataStore/Room) later means adding a second
 * implementation, not editing every caller.
 *
 * They live in the ui package with the stores for now — the package/module
 * split is deliberately a LATER refactor step, after the ViewModel split.
 */

/** Saved visual presets, organized in a shallow folder tree. */
interface PresetRepository {
    fun list(): List<Preset>

    fun save(
        preset: Preset,
        folder: String = "",
    )

    fun delete(name: String)

    fun folders(): List<String>

    fun folderOf(name: String): String

    fun addFolder(path: String)

    fun renameFolder(
        from: String,
        to: String,
    )

    fun moveToFolder(
        name: String,
        folder: String,
    )

    /** The on-disk JSON file for a saved preset, for mirroring/export; null when absent. */
    fun fileOf(name: String): File?
}

/** Imported tracks plus cached analysis and user-edited metadata overrides. */
interface LibraryRepository {
    fun list(): List<LibraryTrack>

    fun addAll(tracks: List<LibraryTrack>): List<LibraryTrack>

    fun updateAnalysis(
        uri: String,
        title: String,
        durationMs: Long,
        bpm: Float,
        key: String = "",
    ): List<LibraryTrack>

    fun updateMetadata(
        uri: String,
        title: String,
        artist: String,
    ): List<LibraryTrack>

    fun updateMetadata(
        uri: String,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNo: Int,
        comment: String,
    ): List<LibraryTrack>

    fun remove(uri: String): List<LibraryTrack>
}

/** Play history: last-played ordering and play counts. */
interface HistoryRepository {
    fun recordPlay(
        uri: String,
        title: String,
    )

    fun recentlyPlayed(limit: Int = 20): List<HistoryStore.Entry>

    fun mostPlayed(limit: Int = 20): List<HistoryStore.Entry>
}

/** Named, ordered music playlists referencing tracks by uri. */
interface MusicPlaylistRepository {
    fun list(): List<MusicPlaylist>

    fun save(playlist: MusicPlaylist)

    fun delete(name: String)

    fun addTrack(
        name: String,
        uri: String,
    ): MusicPlaylist

    fun rename(
        oldName: String,
        newName: String,
    ): Boolean

    fun move(
        name: String,
        from: Int,
        to: Int,
    ): MusicPlaylist

    fun removeTrack(
        name: String,
        uri: String,
    ): MusicPlaylist
}

/**
 * The live visual customization — what the user has dialled in right now, as
 * opposed to a preset they explicitly saved. Restored on every app start.
 */
interface LiveVizRepository {
    /** The stored state, or null on first run / unreadable data. */
    fun load(): LiveViz?

    fun save(state: LiveViz)
}

/**
 * Customize parameters the user has locked against "Randomize unlocked".
 *
 * Keyed by display label rather than by a stable id, which is why a rename is
 * silent at runtime; see [ParamLockStore] for the consequence and where the
 * migration to stable ids would land.
 */
interface ParamLockRepository {
    fun load(): Set<String>

    fun save(locked: Set<String>)
}
