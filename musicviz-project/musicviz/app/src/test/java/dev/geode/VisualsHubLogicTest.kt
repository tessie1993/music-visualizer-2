package dev.geode

import dev.geode.data.Preset
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.VisualStyleCatalog
import dev.geode.ui.VizPlaylistEntry
import dev.geode.ui.builtInPresetMatchesScene
import dev.geode.ui.builtInPresetSceneFamily
import dev.geode.ui.presetReplaceTarget
import dev.geode.ui.suggestedSceneToOffer
import dev.geode.ui.takeRenameError
import dev.geode.ui.vizPlaylistIndexOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The Visuals hub's row/dialog logic, extracted as pure functions so the four
 * silent-failure paths the audit found stay closed:
 *
 *  - the preset heart is a membership TOGGLE (tap out, no duplicates), not a
 *    write-only append;
 *  - the take rename dialog refuses names TakeStore.rename would silently
 *    reject (blank, taken);
 *  - Save asks before replacing an existing user preset, and never mistakes a
 *    built-in for one;
 *  - the Built-in section matches by scene FAMILY, so the hyperspace and
 *    cymatics substyles are not shown an empty shelf;
 *  - the SceneSuggester's pick is offered exactly while it differs from the
 *    active scene.
 */
class VisualsHubLogicTest {
    private fun preset(
        name: String,
        sceneId: String = SceneIds.NEBULA,
    ) = Preset(name = name, sceneId = sceneId, attack = 0.5f, decay = 0.2f)

    private fun entry(presetName: String?) =
        VizPlaylistEntry(sceneId = SceneIds.NEBULA, presetName = presetName, label = presetName ?: "step")

    // ---- Heart = membership toggle (finding 3) ----

    @Test
    fun heartReadsMembershipByPresetName() {
        val playlist = listOf(entry("Dawn"), entry("Dusk"))
        assertEquals(0, vizPlaylistIndexOf(playlist, "Dawn"))
        assertEquals(1, vizPlaylistIndexOf(playlist, "Dusk"))
        assertEquals(-1, vizPlaylistIndexOf(playlist, "Noon"))
        assertEquals(-1, vizPlaylistIndexOf(emptyList(), "Dawn"))
    }

    @Test
    fun heartTogglesInsteadOfDuplicating() {
        // Present -> the tap resolves to a REMOVE at the found index, so the
        // playlist shrinks; absent -> an add. Never a second copy.
        val playlist = listOf(entry("Dawn"), entry("Dusk"))
        val idx = vizPlaylistIndexOf(playlist, "Dusk")
        assertTrue(idx >= 0)
        val afterRemove = playlist.filterIndexed { i, _ -> i != idx }
        assertEquals(-1, vizPlaylistIndexOf(afterRemove, "Dusk"))
        assertEquals(0, vizPlaylistIndexOf(afterRemove, "Dawn"))
    }

    @Test
    fun heartDrainsPreexistingDuplicatesOneTapAtATime() {
        // Playlists saved before the toggle could stack copies: removal is by
        // FIRST match, so each tap removes exactly one and membership (the
        // tint) holds until the last copy is gone.
        var playlist = listOf(entry("Dawn"), entry("Dusk"), entry("Dawn"))
        val first = vizPlaylistIndexOf(playlist, "Dawn")
        assertEquals(0, first)
        playlist = playlist.filterIndexed { i, _ -> i != first }
        assertEquals(1, vizPlaylistIndexOf(playlist, "Dawn"))
        playlist = playlist.filterIndexed { i, _ -> i != 1 }
        assertEquals(-1, vizPlaylistIndexOf(playlist, "Dawn"))
    }

    @Test
    fun heartIgnoresNonPresetPlaylistSteps() {
        // Scene-only or .milk steps carry no presetName; a preset row's heart
        // must never claim (or remove) one of those.
        val playlist = listOf(entry(null), entry("Dawn"))
        assertEquals(1, vizPlaylistIndexOf(playlist, "Dawn"))
        assertEquals(-1, vizPlaylistIndexOf(playlist, "step"))
    }

    // ---- Take rename gating (finding 5) ----

    @Test
    fun blankTakeNameDisablesConfirm() {
        assertNotNull(takeRenameError("Old", "", listOf("Old")))
    }

    @Test
    fun collidingTakeNameDisablesConfirmCaseInsensitively() {
        val names = listOf("Sunset", "First Set")
        assertNotNull(takeRenameError("Old", "Sunset", names + "Old"))
        assertNotNull(takeRenameError("Old", "sunset", names + "Old"))
        assertNotNull(takeRenameError("Old", "FIRST SET", names + "Old"))
    }

    @Test
    fun renamingATakeToItselfIsNotACollision() {
        // Same name (any case) collides with nothing - it is the take's own
        // file - so confirm stays enabled and the rename is a harmless no-op
        // or a case fix.
        assertNull(takeRenameError("Sunset", "Sunset", listOf("Sunset", "Other")))
        assertNull(takeRenameError("Sunset", "sunset", listOf("Sunset", "Other")))
    }

    @Test
    fun freshTakeNameEnablesConfirm() {
        assertNull(takeRenameError("Old", "Brand New", listOf("Old", "Sunset")))
        assertNull(takeRenameError("Old", "New", emptyList()))
    }

    // ---- Save-preset confirm-replace (finding 8) ----

    @Test
    fun savingANewNameNeedsNoConfirmation() {
        val presets = listOf(preset("Dawn"), preset("nebula · Prism"))
        assertNull(presetReplaceTarget("Dusk", presets))
    }

    @Test
    fun savingAnExistingUserPresetNameAsksFirst() {
        val presets = listOf(preset("Dawn"), preset("Dusk"))
        assertEquals("Dawn", presetReplaceTarget("Dawn", presets))
        // savePreset trims before it writes, so the padded name is the same save.
        assertEquals("Dawn", presetReplaceTarget("  Dawn  ", presets))
    }

    @Test
    fun builtInsAreNeverReportedAsReplaceTargets() {
        // savePreset launders " · " (reserved for built-ins) to " - ", so a
        // typed built-in name cannot overwrite one; the dialog must not claim
        // it would.
        val presets = listOf(preset("nebula · Prism"))
        assertNull(presetReplaceTarget("nebula · Prism", presets))
        // But the laundered form IS a user name; saving it again collides.
        val withLaundered = presets + preset("nebula - Prism")
        assertEquals("nebula - Prism", presetReplaceTarget("nebula · Prism", withLaundered))
    }

    @Test
    fun replaceTargetMatchesExactNamesOnly() {
        // PresetStore.save replaces by exact name; a different case is a
        // different preset file, so it must not trigger the dialog.
        val presets = listOf(preset("Dawn"))
        assertNull(presetReplaceTarget("dawn", presets))
    }

    // ---- Built-in section family matching (finding 13) ----

    @Test
    fun everyHyperspaceSubstyleShowsTheHyperspaceBuiltIns() {
        VisualStyleCatalog.hyperspaceIds.forEach { id ->
            assertEquals(id, SceneIds.HYPERSPACE, builtInPresetSceneFamily(id))
            assertTrue(id, builtInPresetMatchesScene(SceneIds.HYPERSPACE, id))
        }
    }

    @Test
    fun everyCymaticsSubstyleShowsTheCymaticsBuiltIns() {
        VisualStyleCatalog.cymaticsIds.forEach { id ->
            assertEquals(id, SceneIds.CYMATICS, builtInPresetSceneFamily(id))
            assertTrue(id, builtInPresetMatchesScene(SceneIds.CYMATICS, id))
        }
    }

    @Test
    fun familiesDoNotBleedIntoEachOther() {
        val hyperSub = VisualStyleCatalog.hyperspaceIds.first { it != SceneIds.HYPERSPACE }
        val cymaticsSub = VisualStyleCatalog.cymaticsIds.first { it != SceneIds.CYMATICS }
        assertFalse(builtInPresetMatchesScene(SceneIds.CYMATICS, hyperSub))
        assertFalse(builtInPresetMatchesScene(SceneIds.HYPERSPACE, cymaticsSub))
        assertFalse(builtInPresetMatchesScene(SceneIds.NEBULA, hyperSub))
    }

    @Test
    fun nonSubstyleScenesKeepExactMatching() {
        assertEquals(SceneIds.NEBULA, builtInPresetSceneFamily(SceneIds.NEBULA))
        assertTrue(builtInPresetMatchesScene(SceneIds.NEBULA, SceneIds.NEBULA))
        assertFalse(builtInPresetMatchesScene(SceneIds.PLASMA, SceneIds.NEBULA))
        // BEAM has no built-in looks yet (accepted gap): its family is itself,
        // so nothing foreign shows up on it either.
        assertEquals(SceneIds.BEAM, builtInPresetSceneFamily(SceneIds.BEAM))
        assertFalse(builtInPresetMatchesScene(SceneIds.HYPERSPACE, SceneIds.BEAM))
    }

    // ---- Suggested chip visibility (finding 2) ----

    @Test
    fun noSuggestionMeansNoChip() {
        assertNull(suggestedSceneToOffer(null, SceneIds.NEBULA))
    }

    @Test
    fun anAppliedSuggestionMeansNoChip() {
        assertNull(suggestedSceneToOffer(SceneIds.FLUID, SceneIds.FLUID))
    }

    @Test
    fun aDifferingSuggestionIsOffered() {
        assertEquals(SceneIds.FLUID, suggestedSceneToOffer(SceneIds.FLUID, SceneIds.NEBULA))
    }

    // ---- Wiring: the formerly dead APIs now have hub callers ----

    @Test
    fun theHubActuallyCallsTheFormerlyDeadViewModelApis() {
        // The audit's DEAD API list: removeTexture, removeVizPlaylistAt and
        // suggestedSceneId all existed with zero UI callers. The pure
        // functions above prove the logic; this proves the hub is wired to it.
        val hub = visualsHubSource()
        assertTrue("texture rows must delete via removeTexture", "viewModel.removeTexture(" in hub)
        assertTrue("the heart must remove via removeVizPlaylistAt", "viewModel.removeVizPlaylistAt(" in hub)
        assertTrue("the Styles tab must read suggestedSceneId", "suggestedSceneId" in hub)
    }

    @Test
    fun milkDropDocNoLongerPromisesANextButton() {
        val doc = visualsHubSource().lineSequence().first { "Dedicated MilkDrop tab" in it }
        assertFalse("the MilkDrop tab has no Next button; the doc must not invent one", "Next" in doc)
    }

    private fun visualsHubSource(): String {
        val relatives =
            listOf(
                "src/main/java/dev/geode/ui/VisualsHub.kt",
                "app/src/main/java/dev/geode/ui/VisualsHub.kt",
            )
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (rel in relatives) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("VisualsHub.kt not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
