package dev.musicviz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The export request held across the document picker survives recreation.
 *
 * `ExportHost.pendingExport` bridges "Render to chosen folder..." to the
 * `CreateDocument` result. The picker is a separate activity, so rotating (or
 * process death) while it is up recreates the composition underneath it; on a
 * plain `remember` the request came back null and the destination the user
 * just picked was silently dropped. Source-level, like [RendererWiringTest]:
 * the holder and its saver are private composition state, so what can be
 * stated is stated about the code - it is saveable, and the saver flattens
 * every field of the request.
 */
class ExportHostSaveableTest {
    private val source: String by lazy { repoFile("src/main/java/dev/musicviz/ui/ExportHost.kt") }

    @Test
    fun thePendingExportIsHeldInSaveableState() {
        assertTrue(
            "pendingExport is no longer rememberSaveable - a rotation during the picker drops the pick again",
            source.contains("pendingExport by rememberSaveable(stateSaver = PendingExportSaver)"),
        )
        // No other holder in this file may quietly regress to plain remember.
        assertFalse("ExportHost holds state in a plain remember again", source.contains("by remember {"))
    }

    @Test
    fun theSaverCarriesEveryFieldOfTheRequest() {
        // A field added to PendingExport but not to the saver restores at a
        // stale default - the picker would then start an export with the wrong
        // option, which is worse than dropping it.
        val declaration = Regex("""data class PendingExport\(([^)]*)\)""").find(source)
        if (declaration == null) {
            fail("ExportHost no longer declares PendingExport")
            error("unreachable")
        }
        val fields = Regex("""val (\w+):""").findAll(declaration.groupValues[1]).map { it.groupValues[1] }.toList()
        assertTrue("no fields parsed out of PendingExport", fields.isNotEmpty())
        for (field in fields) {
            assertTrue("PendingExportSaver does not save PendingExport.$field", source.contains("req.$field"))
        }
    }

    /** Resolves a path under `app/`, whichever directory the tests run from. */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
