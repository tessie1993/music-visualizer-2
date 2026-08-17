package dev.geode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dead ViewModel API stays dead, and the one that found a caller stays alive.
 *
 * The visuals-hub audit triaged the zero-caller surface:
 *  - `nextMilkPresetAsync` / `nextBuiltInMilkPresetBlocking` / `builtInIndex`:
 *    the "Next preset" button they served no longer exists, and the built-in
 *    .milk corpus they walked was itself removed (milkPresetFilesAsync
 *    deletes stale copies). Deleted rather than wired - there is nothing left
 *    to wire them to.
 *  - `mostPlayed()`: a one-line pass-through to [HistoryStore.mostPlayed]
 *    that no screen called; callers use the store, which is already exposed
 *    where it is needed. Deleted.
 *  - `removeVizPlaylistAt`: dead at audit time, but the heart-membership
 *    toggle in Visuals › Presets now calls it - kept, and this test holds the
 *    caller in place so it cannot quietly become dead API again.
 *  - The next zero-caller batch (`analyzePlaylist`, `clearArtPaletteNote`,
 *    the quick-preset trio, `playlistTracks`, `removeFromLibrary`, `seekBy`):
 *    swipe/gesture and playlist affordances whose screens were rebuilt
 *    without them. Deleted; pinned below like the trio above.
 */
class DeadVmApiTest {
    private val viewModel = ParamSurface.source("ui/PlayerViewModel.kt")

    @Test
    fun `the deleted milk-cycling trio does not come back`() {
        for (name in listOf("nextMilkPresetAsync", "nextBuiltInMilkPresetBlocking", "builtInIndex")) {
            assertFalse(
                "$name is back in PlayerViewModel; it was deleted as dead API - wire a caller or keep it out",
                viewModel.contains(name),
            )
        }
    }

    @Test
    fun `the mostPlayed pass-through does not come back`() {
        assertFalse(
            "PlayerViewModel.mostPlayed() is back; callers read HistoryStore directly",
            Regex("""fun mostPlayed\(""").containsMatchIn(viewModel),
        )
    }

    @Test
    fun `the deleted playlist and gesture batch does not come back`() {
        val batch =
            listOf(
                "analyzePlaylist",
                "clearArtPaletteNote",
                "nextQuickPreset",
                "prevQuickPreset",
                "stepQuickPreset",
                "playlistTracks",
                "removeFromLibrary",
                "seekBy",
            )
        for (name in batch) {
            assertFalse(
                "$name is back in PlayerViewModel; it was deleted as dead API - wire a caller or keep it out",
                viewModel.contains(name),
            )
        }
    }

    @Test
    fun `removeVizPlaylistAt keeps its UI caller`() {
        assertTrue("removeVizPlaylistAt was deleted but the playlist heart needs it", viewModel.contains("fun removeVizPlaylistAt("))
        val ui = File(ParamSurface.moduleRoot, "app/src/main/java/dev/geode/ui")
        val called =
            ui
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "PlayerViewModel.kt" }
                .any { it.readText().contains("removeVizPlaylistAt(") }
        assertTrue(
            "no composable calls removeVizPlaylistAt any more - it is dead API again; delete it or re-wire the heart",
            called,
        )
    }
}
