package dev.geode

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The GLSL editor's draft cannot blow the saved-state Binder budget.
 *
 * Saved instance state rides a Binder transaction capped around 1 MB shared by
 * the whole activity, and `rememberSaveable` contributes to it - persisting a
 * whole shader source there is a TransactionTooLargeException waiting for a
 * backgrounding. `GlslHubTab` therefore saves its draft through a capped saver
 * that drops oversized sources (the editor re-seeds those from the renderer's
 * committed copy). Source-level, like [ExportHostSaveableTest]: the holder and
 * its saver are private composition state, so what can be stated is stated
 * about the code.
 */
class GlslDraftBinderBudgetTest {
    private val source: String by lazy { repoFile("src/main/java/dev/geode/ui/VisualsHub.kt") }

    @Test
    fun theShaderDraftIsSavedThroughTheCappedSaver() {
        assertTrue(
            "the GLSL draft no longer routes through ShaderDraftSaver - an uncapped rememberSaveable " +
                "puts the whole shader source back into the ~1 MB saved-state Binder transaction",
            source.contains("var source by rememberSaveable(viz.sceneId, stateSaver = ShaderDraftSaver)"),
        )
    }

    @Test
    fun theSaverDropsOversizedDrafts() {
        // `save` returning null is how a Saver declines to persist a value;
        // anything else (truncation, unconditional save) either corrupts the
        // draft or reintroduces the Binder risk.
        assertTrue(
            "ShaderDraftSaver no longer declines to save drafts over the cap",
            source.contains("draft.takeIf { it.length <= MAX_SAVED_SHADER_DRAFT_CHARS }"),
        )
    }

    @Test
    fun theCapStaysWellUnderTheBinderBudget() {
        val cap = Regex("""MAX_SAVED_SHADER_DRAFT_CHARS = (\d+) \* 1024""").find(source)
        if (cap == null) {
            fail("VisualsHub no longer declares MAX_SAVED_SHADER_DRAFT_CHARS as KiB")
            error("unreachable")
        }
        val kib = cap.groupValues[1].toInt()
        // The 1 MB transaction is shared with every other saveable in the
        // activity (and chars save as UTF-16, doubling the bytes), so the cap
        // must stay a small fraction of it.
        assertTrue("MAX_SAVED_SHADER_DRAFT_CHARS is $kib KiB - too close to the shared 1 MB budget", kib <= 64)
        assertTrue("MAX_SAVED_SHADER_DRAFT_CHARS is $kib KiB - too small to hold a realistic draft", kib >= 1)
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
