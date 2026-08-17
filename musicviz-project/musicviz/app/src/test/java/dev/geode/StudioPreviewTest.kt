package dev.geode

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Studio's preview player: the first playback in an editor whose every
 * decision was previously verified by waiting out a full re-render.
 *
 * The contract that matters is parity - the preview must show what the
 * export will render, which is only guaranteed while both sides consume the
 * SAME [dev.geode.export.ClipEdit] functions. These are source gates because
 * the drift that breaks parity (a preview building its own effect chain, a
 * dropped clipping call) compiles cleanly and shows up only as "the render
 * doesn't match what I saw".
 */
class StudioPreviewTest {
    private val moduleRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("module root not found")

    private fun source(relative: String): String = File(moduleRoot, "app/src/main/java/dev/geode/$relative").readText()

    @Test
    fun `the preview plays the same edit the export renders`() {
        val preview = source("ui/StudioPreview.kt")
        assertTrue(
            "the preview must clip through ClipEdit.clipping() - the export's own trim",
            preview.contains("edit.clipping()"),
        )
        assertTrue(
            "the preview must grade through ClipEdit.videoEffects() - the export's own chain",
            preview.contains("edit.videoEffects()"),
        )
        assertTrue(
            "the preview must retime through the edit's speed",
            preview.contains("PlaybackParameters(edit.speed"),
        )
    }

    @Test
    fun `the preview never builds its own effect chain`() {
        val preview = source("ui/StudioPreview.kt")
        for (effect in listOf("Brightness(", "Contrast(", "HslAdjustment", "RgbFilter", "Presentation.create")) {
            assertTrue(
                "StudioPreview constructs $effect itself - parity with the export is now a manual promise",
                !preview.contains(effect),
            )
        }
    }

    @Test
    fun `the preview loops the cut, muted, and releases its player`() {
        val preview = source("ui/StudioPreview.kt")
        assertTrue(
            "the preview must loop the trimmed window so the cut can be judged as a loop",
            preview.contains("REPEAT_MODE_ONE"),
        )
        assertTrue(
            "the preview must stay muted - it plays over the app's own music session",
            preview.contains("volume = 0f"),
        )
        assertTrue(
            "the player must be released when the editor leaves composition",
            preview.contains("onDispose") && preview.contains(".release()"),
        )
        assertTrue(
            "slider drags must debounce the pipeline rebuild (delay-then-cancel)",
            preview.contains("delay("),
        )
    }

    @Test
    fun `the clip editor actually shows the preview`() {
        assertTrue(
            "ClipEditor lost its preview - trimming is blind again",
            source("ui/StudioScreen.kt").contains("ClipPreview("),
        )
    }
}
