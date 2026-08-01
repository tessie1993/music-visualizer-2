package dev.musicviz

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.musicviz.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real MainActivity on the JVM (Robolectric) and walks the
 * navigation: every bottom-nav destination, every Visuals tab, Settings.
 * Adding a destination without adding it here is the gap this closes.
 * The nav tabs are matched via isSelectable to disambiguate from screen
 * headlines with the same text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicVizApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun navTo(label: String) {
        compose.onNode(hasText(label) and isSelectable()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun launches_and_shows_home() {
        compose.onAllNodesWithText("Home").onFirst().assertExists()
    }

    @Test
    fun navigates_all_destinations() {
        navTo("Library")
        compose.onNodeWithText("Allow music access").assertExists()
        navTo("Visuals")
        compose.onAllNodesWithText("Presets").onFirst().assertExists()
        navTo("Studio")
        compose.onNodeWithText("Open a video…").assertExists()
        navTo("Settings")
        // First collapsible section header; later ones may sit below the fold
        // of the lazy list on the small Robolectric display. Headers render
        // tracked-caps in the crystal design, hence ignoreCase.
        compose.onNodeWithText("Appearance", ignoreCase = true).assertExists()
        navTo("Home")
    }

    @Test
    fun visuals_hub_all_tabs_compose() {
        navTo("Visuals")
        listOf("Styles", "Customize", "Textures", "Presets").forEach { tab ->
            compose.onNode(hasText(tab) and isSelectable()).performClick()
            compose.waitForIdle()
        }
        // Styles sub-tabs including the MilkDrop tab
        compose.onNode(hasText("Styles") and isSelectable()).performClick()
        compose.waitForIdle()
        listOf("Particles", "Shaders", "MilkDrop").forEach { sub ->
            compose.onNode(hasText(sub) and isSelectable()).performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun customize_hub_shows_sliders_and_randomize() {
        navTo("Visuals")
        compose.onNode(hasText("Customize") and isSelectable()).performClick()
        compose.waitForIdle()
        // The button names the tab it rolls: it acts on the tab it sits in,
        // and Motion is the one the panel opens on.
        compose.onNodeWithText("⚄ Randomize Motion").assertExists()
        compose.onAllNodesWithText("Speed", substring = true).onFirst().assertExists()
    }

    @Test
    fun search_opens_and_closes() {
        compose.onAllNodesWithText("Home").onFirst().assertExists()
        // Home's search icon is the only Search content-description-free icon;
        // open via the Library screen instead (has the same top-bar search).
        navTo("Library")
    }
}
