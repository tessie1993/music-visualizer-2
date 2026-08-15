package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The Settings redesign: `AppShell.kt` stays the shell, and the settings
 * catalog lives behind [SETTINGS_TAB_TITLES] - a six-tab host in
 * `AppSettingsTab.kt` whose every tab body is its own file (LookSettings,
 * AudioSettings, ExportSettings, FolderSettings, BehaviorSettings,
 * AboutSettings) beside the per-concern files exactly one tab mounts
 * (PlaybackSettings, EqualizerSettings, ExternalAudioSettings,
 * AutoVisualsSettings).
 *
 * These tests read the sources so three regressions fail the build instead
 * of accreting quietly:
 *  - a settings section growing back inside the shell file,
 *  - the tab list drifting from the six agreed categories,
 *  - a control from the old ten-section catalog going missing, or growing a
 *    second copy in a second tab (`theWholeSettingsCatalogMovedAndNoneStayedBehind`).
 */
class AppSettingsTabSplitTest {
    /** The six categories, in on-screen order. */
    private val tabTitles = listOf("Look", "Audio", "Export", "Folders", "Behavior", "About")

    /** Tab body file per category. */
    private val tabBodyFiles =
        mapOf(
            "Look" to "LookSettings.kt",
            "Audio" to "AudioSettings.kt",
            "Export" to "ExportSettings.kt",
            "Folders" to "FolderSettings.kt",
            "Behavior" to "BehaviorSettings.kt",
            "About" to "AboutSettings.kt",
        )

    /**
     * The whole settings corpus: the host, the six tab bodies, and the
     * per-concern files a tab mounts. "Appears in exactly one file" below is
     * judged against this set.
     */
    private val corpusFiles =
        listOf(
            "AppSettingsTab.kt",
            "LookSettings.kt",
            "AudioSettings.kt",
            "ExportSettings.kt",
            "FolderSettings.kt",
            "BehaviorSettings.kt",
            "AboutSettings.kt",
            "AutoVisualsSettings.kt",
            "PlaybackSettings.kt",
            "EqualizerSettings.kt",
            "ExternalAudioSettings.kt",
        )

    /**
     * Every control of the old ten-section catalog (plus the new-in-redesign
     * font colour, text size and export defaults), each mapped to the ONE
     * file that owns it now. Strings are the user-facing labels where they
     * are distinctive, and code anchors where a label would be too generic.
     */
    private val catalog =
        mapOf(
            // LOOK - old "Appearance" section.
            ".setTheme(" to "LookSettings.kt",
            "Accent intensity" to "LookSettings.kt",
            "Background dim" to "LookSettings.kt",
            "Follow system light/dark" to "LookSettings.kt",
            "Font color" to "LookSettings.kt",
            "Text size" to "LookSettings.kt",
            "Bar opacity" to "LookSettings.kt",
            "Player position" to "LookSettings.kt",
            "Corner style" to "LookSettings.kt",
            "Compact mini-player" to "LookSettings.kt",
            "Clear-overlay Visuals menu" to "LookSettings.kt",
            "Boot animation" to "LookSettings.kt",
            // AUDIO - old "Playback" section (via PlaybackSettings.kt).
            "Fade on pause" to "PlaybackSettings.kt",
            "Skip silence" to "PlaybackSettings.kt",
            "Pause when unplugged" to "PlaybackSettings.kt",
            "Keep screen on" to "PlaybackSettings.kt",
            "Auto-resume last track" to "PlaybackSettings.kt",
            "Sleep timer" to "PlaybackSettings.kt",
            "Let the track finish" to "PlaybackSettings.kt",
            // AUDIO - equalizer (via EqualizerSettings.kt).
            "Bass boost" to "EqualizerSettings.kt",
            "Loudness" to "EqualizerSettings.kt",
            // AUDIO - old "Visuals & Analysis" section, minus the cache.
            "Beat sensitivity" to "AudioSettings.kt",
            "Minimum gap between beats" to "AudioSettings.kt",
            "Slow track" to "AudioSettings.kt",
            "Preset morph" to "AudioSettings.kt",
            "Colour from the musical key" to "AudioSettings.kt",
            // AUDIO - the live-input half of old "Live input & touch".
            "React to the microphone" to "AudioSettings.kt",
            "Tune for what the phone is hearing" to "AudioSettings.kt",
            // AUDIO - old "Other apps' audio" (via ExternalAudioSettings.kt).
            "Visualize other apps" to "ExternalAudioSettings.kt",
            // EXPORT - the button from old "Export & About" + new defaults.
            "Export video" to "ExportSettings.kt",
            "Platform preset" to "ExportSettings.kt",
            "Quality" to "ExportSettings.kt",
            "Frame rate" to "ExportSettings.kt",
            "Aspect ratio" to "ExportSettings.kt",
            "Loop-safe" to "ExportSettings.kt",
            // FOLDERS - old "Library" section + the cache readout.
            "Choose preset folder" to "FolderSettings.kt",
            "Add folder" to "FolderSettings.kt",
            "Rescan" to "FolderSettings.kt",
            "Analysis cache" to "FolderSettings.kt",
            // BEHAVIOR - the touch half of old "Live input & touch".
            "Smear the visuals with a finger" to "BehaviorSettings.kt",
            "Pinch and twist the canvas" to "BehaviorSettings.kt",
            "Use a connected display" to "BehaviorSettings.kt",
            // BEHAVIOR - old "Visual safety" section.
            "Flashing and motion" to "BehaviorSettings.kt",
            "Limit flashing" to "BehaviorSettings.kt",
            "Maximum flashes per second" to "BehaviorSettings.kt",
            "Maximum flash strength" to "BehaviorSettings.kt",
            "Allow invert and solarize" to "BehaviorSettings.kt",
            "Slow the motion down" to "BehaviorSettings.kt",
            // BEHAVIOR - old "Auto visuals" section (via AutoVisualsSettings.kt).
            "Switch on a strong beat" to "AutoVisualsSettings.kt",
            "MilkDrop presets" to "AutoVisualsSettings.kt",
            "Roll the colours too" to "AutoVisualsSettings.kt",
            "Play the visual playlist" to "AutoVisualsSettings.kt",
            "Wait for a strong moment" to "AutoVisualsSettings.kt",
            // BEHAVIOR - old "Live wallpaper" section.
            "Set as live wallpaper" to "BehaviorSettings.kt",
            // ABOUT - the rest of old "Export & About".
            "Open source licenses" to "AboutSettings.kt",
            "Privacy policy" to "AboutSettings.kt",
        )

    /**
     * Per-concern composables each mounted by exactly one tab body, as call
     * sites (the `(viewModel)` form cannot match the definitions, whose
     * parameter lists carry types).
     */
    private val mounts =
        mapOf(
            "PlaybackSettingsSection(viewModel)" to "AudioSettings.kt",
            "EqualizerSettings(viewModel)" to "AudioSettings.kt",
            "ExternalAudioSettings(viewModel)" to "AudioSettings.kt",
            "AutoVisualsGroup(viewModel)" to "BehaviorSettings.kt",
            "AboutSection()" to "AboutSettings.kt",
        )

    @Test
    fun theHostShowsExactlyTheSixAgreedTabs() {
        val host = sourceOf("AppSettingsTab.kt")
        val expected = tabTitles.joinToString(", ") { "\"$it\"" }
        assertTrue(
            "the tab list in AppSettingsTab.kt drifted from listOf($expected)",
            "listOf($expected)" in host,
        )
        assertTrue("the selected tab must survive navigation", "rememberSaveable" in host)
        assertTrue("the tab strip is CrystalTabs, like every tab strip here", "CrystalTabs(" in host)
    }

    @Test
    fun everyTabBodyLivesInItsOwnFile() {
        tabBodyFiles.forEach { (tab, file) ->
            val body = sourceOf(file)
            assertTrue(
                "$file must define the $tab tab's composable",
                Regex("internal fun \\w+SettingsTab\\(").containsMatchIn(body),
            )
            assertTrue("$file is in the ui package", "package dev.musicviz.ui" in body.lineSequence().first())
        }
        // The host mounts each of them, so none is an orphan.
        val host = sourceOf("AppSettingsTab.kt")
        listOf(
            "LookSettingsTab(",
            "AudioSettingsTab(",
            "ExportSettingsTab(",
            "FolderSettingsTab(",
            "BehaviorSettingsTab(",
            "AboutSettingsTab(",
        ).forEach { call -> assertTrue("the host must mount $call", call in host) }
    }

    @Test
    fun theWholeSettingsCatalogMovedAndNoneStayedBehind() {
        val corpus = corpusFiles.associateWith { sourceOf(it) }
        catalog.forEach { (needle, home) ->
            val holders = corpus.filterValues { needle in it }.keys.sorted()
            assertEquals(
                "\"$needle\" must appear in exactly one settings file ($home), found in: $holders",
                listOf(home),
                holders,
            )
        }
    }

    @Test
    fun perConcernFilesAreMountedByExactlyOneTab() {
        val corpus = corpusFiles.associateWith { sourceOf(it) }
        mounts.forEach { (call, home) ->
            val holders = corpus.filterValues { call in it }.keys.sorted()
            assertEquals(
                "$call must be mounted by exactly one tab body ($home), found in: $holders",
                listOf(home),
                holders,
            )
        }
    }

    @Test
    fun shellFileDefinesNoSettingsComposables() {
        val shell = sourceOf("AppShell.kt")
        listOf("AppSettingsTab", "SettingsGroup", "AutoVisualsGroup", "LiveInputGroup").forEach { name ->
            assertFalse(
                "$name belongs in the settings files, not the shell",
                Regex("fun $name\\(").containsMatchIn(shell),
            )
        }
        assertTrue(
            "SettingsScreen must still mount the extracted tab host",
            "AppSettingsTab(viewModel" in shell,
        )
    }

    @Test
    fun theCollapsibleSectionEraIsOver() {
        // The old one-scroll design was ten collapsible SettingsSection blocks;
        // the redesign is flat groups inside tabs. The helper coming back
        // would mean sections nesting inside tabs again.
        (corpusFiles + "AppShell.kt").forEach { file ->
            assertFalse(
                "$file resurrects the collapsible SettingsSection helper",
                "fun SettingsSection(" in sourceOf(file),
            )
        }
    }

    @Test
    fun customizeLeftSettingsAndKeptItsOneDoorInVisuals() {
        // The Customize panel belongs to the Visuals hub; Settings no longer
        // mounts a second copy of it. The shell may still MENTION it in docs
        // (the KDoc explains where it went), so this matches the call.
        assertFalse(
            "SettingsScreen must not mount CustomizePanel any more",
            "CustomizePanel(viewModel" in sourceOf("AppShell.kt"),
        )
        assertTrue(
            "the Visuals hub keeps the one CustomizePanel door",
            "CustomizePanel(viewModel, visualizerView)" in sourceOf("VisualsHub.kt"),
        )
    }

    private fun sourceOf(fileName: String): String {
        val relatives =
            listOf(
                "src/main/java/dev/musicviz/ui/$fileName",
                "app/src/main/java/dev/musicviz/ui/$fileName",
            )
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (rel in relatives) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$fileName not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
