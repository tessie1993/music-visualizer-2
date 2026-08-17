package dev.geode.render.scene

import android.content.Context
import java.io.File

/**
 * The .milk presets the app ships with.
 *
 * ## Why this exists
 *
 * MilkDrop is the feature the app advertises by having a tab for it, and that
 * tab said "None yet — load a .milk file" on a fresh install. An earlier commit
 * removed the bundled presets for being poor and never replaced them, so the
 * single most recognisable thing in the app was a dead end unless the user
 * already knew where .milk files live on the internet. The engine was never
 * broken; there was simply nothing to render.
 *
 * The pack is written for this app rather than collected from elsewhere. That
 * is partly licensing — the community archives are a mix of terms that would
 * each need clearing — and partly that presets sourced at random are how the
 * last set ended up being deleted.
 *
 * ## Why they install to the user's directory
 *
 * The browser lists `filesDir/milk`, and everything the user does to a preset —
 * rename, delete, edit and re-save — works on files there. Reading the pack
 * straight from assets would have made six presets that behave differently from
 * every other one in the list: undeletable, and un-editable. Copying them in
 * once makes them ordinary files the user owns.
 *
 * "Once" is the important part. A user who deletes a preset they dislike must
 * not find it back after the next launch, so the marker records that the
 * install happened rather than checking whether the files are present.
 */
object MilkStarterPack {
    /** Where the bundled presets sit inside the APK. */
    private const val ASSET_DIR = "milk"

    /**
     * Records that the pack has been installed, and at which version.
     *
     * A file rather than a preference because it lives beside what it
     * describes: clearing app data removes both together, and a restore that
     * brings back the presets brings back the marker with them.
     */
    private const val MARKER = ".starter-pack"

    /**
     * Bumped when the pack gains presets. An existing install then receives
     * only the new files — never a copy of one the user has already deleted,
     * because the marker's version says which generation they were offered.
     */
    const val VERSION: Int = 1

    /**
     * Copies any not-yet-offered presets into [target], and returns how many
     * were written.
     *
     * Blocking; call off the main thread. Failure is silent by design: a
     * missing starter pack is an emptier list, not a reason to fail a launch,
     * and the engine renders whatever else is there.
     */
    @Suppress("ReturnCount")
    fun install(
        context: Context,
        target: File = File(context.filesDir, "milk"),
    ): Int {
        if (readMarker(target) >= VERSION) return 0
        if (!target.isDirectory && !target.mkdirs()) return 0
        val written =
            runCatching { context.assets.list(ASSET_DIR).orEmpty() }
                .getOrDefault(emptyArray())
                .filter { it.endsWith(".milk") }
                // A file already there is the user's — a re-import, an edit, or
                // a save under the same name. Overwriting it would be this
                // feature silently undoing their work.
                .filterNot { File(target, it).exists() }
                .count { copyPreset(context, it, File(target, it)) }
        writeMarker(target)
        return written
    }

    /**
     * Copies one preset out of the APK, reporting whether it landed.
     *
     * A half-written file is worse than a missing one — projectM would report a
     * parse error on a preset the user never chose to have — so a failed copy
     * takes its own remains with it.
     */
    private fun copyPreset(
        context: Context,
        assetName: String,
        destination: File,
    ): Boolean {
        val copied =
            runCatching {
                context.assets.open("$ASSET_DIR/$assetName").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
        if (!copied) runCatching { destination.delete() }
        return copied
    }

    private fun readMarker(target: File): Int = runCatching { File(target, MARKER).readText().trim().toInt() }.getOrDefault(0)

    private fun writeMarker(target: File) {
        runCatching { File(target, MARKER).writeText(VERSION.toString()) }
    }
}
