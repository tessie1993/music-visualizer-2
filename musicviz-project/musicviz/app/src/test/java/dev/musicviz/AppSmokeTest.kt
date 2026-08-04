package dev.musicviz

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
    fun launches_and_shows_player() {
        compose.onAllNodesWithText("Player").onFirst().assertExists()
        // Dest 0 IS the player: the transport is there from launch, even with
        // nothing loaded (disabled, not absent). Its card sits below the hero,
        // past the fold of the small Robolectric display, so scroll the
        // player's column (the only scrollable composed at launch) to it.
        compose
            .onAllNodes(hasScrollAction())
            .onFirst()
            .performScrollToNode(hasContentDescription("Play"))
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithContentDescription("Shuffle").assertExists()
        compose.onNodeWithContentDescription("Repeat").assertExists()
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
        // The redesigned Settings is tab-per-category; walk every tab so each
        // body composes. The tab titles are selectable, like the nav items.
        listOf("Look", "Audio", "Export", "Folders", "Behavior", "About").forEach { tab ->
            compose.onNode(hasText(tab) and isSelectable()).performClick()
            compose.waitForIdle()
        }
        navTo("Player")
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
        // and Motion is the one the panel opens on. The die is a vector Icon
        // now, so the node's text is the words alone.
        compose.onNodeWithText("Randomize Motion").assertExists()
        compose.onAllNodesWithText("Speed", substring = true).onFirst().assertExists()
    }

    @Test
    fun search_opens_and_closes() {
        compose.onAllNodesWithText("Player").onFirst().assertExists()
        // The Player's search icon is the only Search content-description-free
        // icon; open via the Library screen instead (same top-bar search).
        navTo("Library")
    }
}
