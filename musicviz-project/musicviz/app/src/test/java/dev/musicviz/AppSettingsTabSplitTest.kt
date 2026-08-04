package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The AppShell/AppSettingsTab split. `AppShell.kt` is the shell - root
 * scaffolding, mini-player, search overlay - and the whole settings catalog
 * lives in `AppSettingsTab.kt` beside the other per-concern settings files
 * (ExternalAudioSettings, PlaybackSettings, AboutSettings). These tests read
 * the two sources so a settings section quietly growing back inside the shell
 * file - or the tab losing its cross-file door from [SettingsScreen] - fails
 * the build instead of undoing the split one convenience at a time.
 */
class AppSettingsTabSplitTest {
    /** Every SettingsSection the tab shows, in declaration order. */
    private val sectionTitles =
        listOf(
            "Appearance",
            "Other apps' audio",
            "Live input & touch",
            "Playback",
            "Library",
            "Visual safety",
            "Visuals & Analysis",
            "Auto visuals",
            "Live wallpaper",
            "Export & About",
        )

    @Test
    fun shellFileDefinesNoSettingsComposables() {
        val shell = sourceOf("AppShell.kt")
        listOf("AppSettingsTab", "SettingsSection", "AutoVisualsSettings", "LiveInputSettings").forEach { name ->
            assertFalse(
                "$name belongs in AppSettingsTab.kt, not the shell",
                Regex("fun $name\\(").containsMatchIn(shell),
            )
        }
        assertTrue(
            "SettingsScreen must still mount the extracted tab",
            "AppSettingsTab(viewModel)" in shell,
        )
    }

    @Test
    fun tabFileOwnsTheTabAndItsHelpers() {
        val tab = sourceOf("AppSettingsTab.kt")
        // internal, not private: SettingsScreen calls it from AppShell.kt, and
        // top-level private is file-private in Kotlin.
        assertTrue("the tab must stay callable across files", "internal fun AppSettingsTab(" in tab)
        listOf("SettingsSection", "AutoVisualsSettings", "LiveInputSettings").forEach { name ->
            assertTrue(
                "$name is only used by the tab, so it stays private beside it",
                "private fun $name(" in tab,
            )
        }
        // Same package, so the shell needs no new import for the call.
        assertTrue("package dev.musicviz.ui" in tab.lineSequence().first())
    }

    @Test
    fun theWholeSettingsCatalogMovedAndNoneStayedBehind() {
        val sectionRegex = Regex("SettingsSection\\(\"([^\"]+)\"\\)")
        val inTab = sectionRegex.findAll(sourceOf("AppSettingsTab.kt")).map { it.groupValues[1] }.toList()
        assertEquals("the section list in AppSettingsTab.kt drifted", sectionTitles, inTab)
        assertEquals(
            "settings sections crept back into AppShell.kt",
            emptyList<String>(),
            sectionRegex.findAll(sourceOf("AppShell.kt")).map { it.groupValues[1] }.toList(),
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
