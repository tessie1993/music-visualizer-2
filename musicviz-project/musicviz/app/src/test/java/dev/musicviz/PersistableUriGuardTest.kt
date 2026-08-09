package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `takePersistableUriPermission` in the app is inside a `runCatching`.
 *
 * The call is not optional-looking and it is not safe: a DocumentsProvider is
 * free to return a grant that is not persistable, and taking one that was not
 * offered throws `SecurityException`. Three of the four call sites already
 * guarded it - MainActivity's `onPersistUri`, `importTracks`, `importFolder` -
 * and the fourth, Settings › Folders › Choose preset folder, did not. It runs
 * on the main thread inside an ActivityResult callback, so the throw was a
 * process crash on picking the wrong folder.
 *
 * Held as an INVARIANT rather than as a fourth patched instance, because the
 * defect class is "another SAF picker gets wired up and the author copies the
 * unguarded shape": the guard is a property every call site must have, and a
 * new one is exactly when nobody is looking.
 */
class PersistableUriGuardTest {
    private val mainSources: File by lazy { File(ParamSurface.moduleRoot, "app/src/main/java/dev/musicviz") }

    private fun kotlinFiles(): List<File> =
        mainSources
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    @Test
    fun every_persistable_uri_grant_is_taken_inside_a_runCatching() {
        val unguarded = mutableListOf<String>()
        var total = 0
        for (file in kotlinFiles()) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("takePersistableUriPermission", from)
                if (at < 0) break
                total++
                from = at + 1
                // The guard has to be the enclosing expression, so look back
                // over the statement the call belongs to rather than the whole
                // file: a runCatching somewhere else in the same function is
                // not a guard on this call.
                val window = text.substring(maxOf(0, at - 240), at)
                if (!window.contains("runCatching")) {
                    val line = text.substring(0, at).count { it == '\n' } + 1
                    unguarded += "${file.relativeTo(mainSources)}:$line"
                }
            }
        }
        assertTrue("no takePersistableUriPermission call sites found - the scan went stale", total >= 4)
        assertEquals(
            "an unguarded takePersistableUriPermission crashes the process when a provider " +
                "returns a non-persistable grant; wrap it in runCatching and only record the " +
                "folder when the grant was actually taken",
            emptyList<String>(),
            unguarded,
        )
    }

    @Test
    fun the_preset_folder_is_only_recorded_when_the_grant_was_taken() {
        // The other half of that call site: a folder whose permission dies
        // with this process is not one to persist as the preset mirror, so
        // the preference write is conditional on the grant.
        val folders = ParamSurface.source("ui/FolderSettings.kt")
        assertTrue(
            "FolderSettings records presetMirrorUri without checking that the grant was taken",
            Regex("""if \(persisted\) viewModel\.setGuiPrefs\(gui\.copy\(presetMirrorUri = uri\.toString\(\)\)\)""")
                .containsMatchIn(folders),
        )
    }
}
