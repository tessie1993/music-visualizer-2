package dev.musicviz.ui

import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import kotlin.random.Random

/**
 * When the two automatic visual switchers — the visual playlist and random
 * mode — may hop to the next look.
 *
 * Both had the same rule written out inline with different constants, which
 * made the difference between them (random mode is a little more eager) look
 * accidental rather than chosen. Pure and headless so the timing is testable
 * without a player, a clock or a GL context.
 */
object AutoSwitch {
    /** Visual playlist: dwell floor and the energy a beat needs to count. */
    const val PLAYLIST_MIN_DWELL_MS: Long = 8_000L
    const val PLAYLIST_RMS: Float = 0.28f

    /** Random mode hops sooner and on slightly quieter beats. */
    const val RANDOM_MIN_DWELL_MS: Long = 6_000L
    const val RANDOM_RMS: Float = 0.25f

    /** Plain interval switching. */
    fun isDue(
        elapsedMs: Long,
        intervalMs: Long,
    ): Boolean = elapsedMs >= intervalMs

    /**
     * Musical switching: after a minimum dwell, hop on a strong moment (a beat
     * with real energy behind it), and force a hop at twice the interval so a
     * quiet passage still rotates.
     *
     * The dwell floor is `max(minDwellMs, intervalMs / 2)`, so shortening the
     * interval below twice the floor stops shortening the dwell — the switcher
     * never strobes, whatever the slider says.
     */
    fun isDueOnMusic(
        elapsedMs: Long,
        intervalMs: Long,
        beat: Boolean,
        rms: Float,
        minDwellMs: Long,
        rmsThreshold: Float,
    ): Boolean {
        val minDwell = maxOf(minDwellMs, intervalMs / 2)
        return (elapsedMs >= minDwell && beat && rms > rmsThreshold) || elapsedMs >= intervalMs * 2
    }
}

/**
 * Builds and draws from the pool random mode hops around in.
 *
 * Pure given its inputs and an [Random]: the caller decides which categories
 * are switched on and whether MilkDrop is even available, and passes empty
 * lists for the rest. Keeping the draw here rather than inline in the
 * ViewModel is what makes the "never land on what is already showing" rule
 * checkable.
 */
object RandomVizPicker {
    /**
     * The pool, in a stable order: styles, then saved presets, then `.milk`
     * files. Order is load-bearing — it is what a seeded [Random] indexes into.
     */
    fun choices(
        styles: List<String>,
        presets: List<Preset>,
        milkFiles: List<MilkFile>,
    ): List<VizPlaylistEntry> =
        buildList {
            styles.forEach { add(VizPlaylistEntry(sceneId = it, label = it)) }
            presets.forEach { add(VizPlaylistEntry(sceneId = it.sceneId, presetName = it.name, label = it.name)) }
            milkFiles.forEach { add(VizPlaylistEntry(sceneId = SceneIds.MILKDROP, milkPath = it.path, label = it.name)) }
        }

    /**
     * Draws one entry, retrying once when the draw is the bare style already
     * showing. One retry, not a loop: a second collision is rare enough to
     * live with, and looping on a pool of one would never terminate.
     *
     * Null when there is nothing to choose from.
     */
    fun pick(
        choices: List<VizPlaylistEntry>,
        currentSceneId: String,
        rng: Random,
    ): VizPlaylistEntry? {
        if (choices.isEmpty()) return null
        val first = choices[rng.nextInt(choices.size)]
        val isBareCurrentStyle =
            first.sceneId == currentSceneId && first.presetName == null && first.milkPath == null
        return if (choices.size > 1 && isBareCurrentStyle) choices[rng.nextInt(choices.size)] else first
    }

    /**
     * Rolls both palette slots, their mix and the colour shift.
     *
     * A custom-palette override outranks the `PALETTES` lookup, so both slots
     * are cleared as well — without that, a rolled index stays invisible to
     * anyone who built their own palette.
     */
    fun rollColors(
        params: SceneParams,
        rng: Random,
    ): SceneParams {
        val rolled =
            params.copy(
                palette = rng.nextInt(SceneParams.PALETTES.size),
                palette2 = rng.nextInt(SceneParams.PALETTES.size),
                paletteMix = if (rng.nextBoolean()) rng.nextFloat() * 0.6f else 0f,
                colorShift = rng.nextFloat(),
            )
        return PaletteStore.clear(PaletteStore.clear(rolled), second = true)
    }
}
