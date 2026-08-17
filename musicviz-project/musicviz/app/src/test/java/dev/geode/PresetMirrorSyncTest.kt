package dev.geode

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset mirror (Settings › Paths) follows the store on EVERY write path.
 *
 * mirrorPresetToChosenFolder ran from savePreset only, so the mirror was a
 * write-once log: a deleted preset lived on in the user's chosen folder
 * forever, and a moved one was never refreshed. deletePreset now removes the
 * mirrored .json and paired .milk (names captured before the store delete -
 * fileOf resolves through the disk), and movePresetToFolder re-mirrors.
 *
 * Pinned as a source scan, following [ParamSurface] and ViewModelSurfaceTest:
 * the mirror is a SAF DocumentFile tree, which Robolectric has no documents
 * provider to stand in for, and the question - does each mutation path call
 * its mirror half - is recorded in the code alone. The name derivations the
 * removal relies on are behaviour-tested in SafeFileNameTest.
 */
class PresetMirrorSyncTest {
    // The library lives in PresetLibraryController since the ViewModel decomposition.
    private val source = ParamSurface.source("ui/PresetLibraryController.kt")

    /** The body of `fun name(` up to the next function declaration. */
    private fun functionBody(name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("PresetLibraryController no longer declares $name", start >= 0)
        val next = source.indexOf("\n    fun ", start)
        val nextPrivate = source.indexOf("\n    private fun ", start)
        val end =
            listOf(next, nextPrivate)
                .filter { it >= 0 }
                .minOrNull() ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun `deletePreset removes the mirrored files`() {
        assertTrue(
            "deletePreset no longer cleans the mirror - deleted presets pile up in the user's chosen folder",
            functionBody("deletePreset").contains("removeMirroredPreset("),
        )
        assertTrue(
            "the mirror removal helper is gone",
            source.contains("private fun removeMirroredPreset("),
        )
    }

    @Test
    fun `movePresetToFolder re-mirrors`() {
        assertTrue(
            "movePresetToFolder no longer re-mirrors - the mirrored copy drifts from the store",
            functionBody("movePresetToFolder").contains("mirrorPresetToChosenFolder("),
        )
    }

    @Test
    fun `the mirror names are captured before the store delete`() {
        // fileOf resolves through the disk; called after presetStore.delete
        // it returns null and the mirror is never cleaned. Order is the bug.
        val body = functionBody("deletePreset")
        val capture = body.indexOf("removeMirroredPreset(")
        val delete = body.indexOf("store.delete(")
        assertTrue("deletePreset lost either the capture or the delete", capture >= 0 && delete >= 0)
        assertTrue("mirror names are captured AFTER the file is deleted, so they resolve to null", capture < delete)
    }
}
