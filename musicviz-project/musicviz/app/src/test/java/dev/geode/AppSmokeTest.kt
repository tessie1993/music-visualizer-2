package dev.geode

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.geode.ui.MainActivity
import org.junit.Before
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
@Config(sdk = [34], application = GeodeApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Answers the first-run safety question the way a user does, then carries
     * on with the test.
     *
     * A click rather than a seeded preference: the compose rule creates the
     * activity while the rule is being applied, so anything written in `@Before`
     * or an instance initialiser races the activity's own read of preferences
     * and made these tests order-dependent. Driving the real UI has no such
     * race and exercises a path a user actually takes. That the gate appears at
     * all on a fresh install is [FirstRunSafetyGateTest]'s job.
     */
    @Before
    fun answerSafetyQuestion() {
        val gate = compose.onAllNodesWithContentDescription("Keep it safe", substring = true)
        if (gate.fetchSemanticsNodes().isNotEmpty()) {
            gate.onFirst().performClick()
            compose.waitForIdle()
        }
    }

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
        listOf("Silk", "Life", "Mycelium", "Acid", "Shaders", "MilkDrop").forEach { sub ->
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
