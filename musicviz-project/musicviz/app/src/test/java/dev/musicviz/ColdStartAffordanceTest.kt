package dev.musicviz

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * What a FRESH INSTALL is told, on the two screens that had something to say
 * and said the wrong thing.
 *
 * Both defects share a shape: a control that is offered as if it worked,
 * because the state that decides whether it can is not the state it was
 * gated on.
 *
 *  - the Player's empty state offers "Resume last played" unconditionally,
 *    while `resumeLastPlayed()` returns silently on an empty listening
 *    history. On a fresh install one of the empty state's two buttons did
 *    nothing at all - no toast, no navigation - forever. Its sibling
 *    "Shuffle all" was already gated on exactly the right predicate.
 *  - the Equalizer card branched on `available` alone. That is false both
 *    when the device refuses the effect AND before any audio has played
 *    (ExoPlayer's session id is UNSET until its sink initialises), so EVERY
 *    cold start declared the device unsupported and disabled the switch.
 *    `attached` is what tells the two apart.
 *
 * The real Activity is launched (the [AppSmokeTest] pattern) because "before
 * anything has played" is not a state that can be faked convincingly - it is
 * just the app, at launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicVizApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColdStartAffordanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Scrolls the screen's own column to [text].
     *
     * Picked by an anchor it must contain rather than by position: several
     * things on these screens carry a scroll action (`CrystalTabs` is a
     * ScrollableTabRow, the sleep-timer chips are a LazyRow), and which one
     * is first or last is a layout detail. Matching is case-insensitive
     * because `CrystalOverline` uppercases every group title.
     */
    private fun scrollTo(
        anchor: String,
        text: String,
    ) = compose
        .onNode(hasScrollAction() and hasAnyDescendant(hasText(anchor, ignoreCase = true)))
        .performScrollToNode(hasText(text, ignoreCase = true))

    @Test
    fun `resume last played is offered only when there is something to resume`() {
        // Nothing has ever played, so the hero is in its empty state and both
        // history-backed actions must read as unavailable.
        compose.onNodeWithText("Nothing playing", ignoreCase = true).assertExists()
        compose.onNodeWithText("Resume last played").assertIsNotEnabled()
        scrollTo(anchor = "Nothing playing", text = "Shuffle all")
        compose.onNodeWithText("Shuffle all").assertIsNotEnabled()
    }

    @Test
    fun `open library stays live in the empty state`() {
        // The other half: gating the dead button must not gate the working
        // one, or the empty state becomes a dead end.
        compose.onNodeWithText("Open library").assertIsEnabled()
    }

    @Test
    fun `the audio tab still composes its equalizer card`() {
        // The three states themselves are pinned in EqualizerCardStateTest,
        // which can produce them; this only holds the wiring that puts the
        // card on the Audio tab in the first place.
        compose.onNode(hasText("Settings") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Audio") and isSelectable()).performClick()
        compose.waitForIdle()
        scrollTo(anchor = "Playback", text = "Equalizer")
        compose.onNodeWithText("Equalizer", ignoreCase = true).assertExists()
    }
}
