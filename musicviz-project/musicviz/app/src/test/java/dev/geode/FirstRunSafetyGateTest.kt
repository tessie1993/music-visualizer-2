package dev.geode

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.geode.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A fresh install is asked about flashing before it can reach anything that
 * flashes.
 *
 * Its own class, with no stored answer, because that is the only way to get the
 * genuine first-run path: the compose rule launches the activity while the rule
 * is being applied, so a test that seeds preferences in `@Before` — or one that
 * clears them and recreates — is no longer testing a cold start. Every other
 * UI test answers the question in an instance initialiser precisely so it can
 * get past this screen; this one is the reason that screen is worth getting
 * past.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = GeodeApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstRunSafetyGateTest {
    /**
     * Clears the stored answer before the activity is created.
     *
     * Ordered rules, not `@Before`: the compose rule builds the activity while
     * IT is being applied, and `@Before` runs after that — too late to affect
     * what the activity reads. Robolectric also shares preferences between test
     * classes in one JVM, so without this the gate test inherits whatever answer
     * an earlier UI test clicked and finds the shell instead of the question.
     */
    @get:Rule(order = 0)
    val freshInstall =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    RuntimeEnvironment
                        .getApplication()
                        .getSharedPreferences("geode-prefs", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                    base.evaluate()
                }
            }
        }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_fresh_install_is_asked_before_anything_flashes() {
        compose.onNodeWithText("Before the visuals start").assertIsDisplayed()
    }

    /**
     * Matched against the UNMERGED semantics tree, and on existence rather than
     * display.
     *
     * Each option's button carries a contentDescription of "title. detail" so a
     * screen reader hears the substance of the choice rather than a bare label.
     * That description replaces the child Text in the MERGED tree, so a
     * merged-tree text match finds nothing and a merged-tree description match
     * is sensitive to how the button composes — both made this assertion
     * order-dependent. The unmerged tree contains the option labels and their
     * detail text as written, which is what is actually being asserted.
     * Existence rather than display because the screen scrolls and the last
     * option sits below the fold on Robolectric's display.
     */
    @Test
    fun every_choice_is_offered_with_its_consequences_spelled_out() {
        for (option in listOf("Keep it safe", "Safe, and easier on motion", "Full effects")) {
            assertTrue(
                "the consent screen does not offer \"$option\"",
                compose
                    .onAllNodesWithText(option, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
        }
        // The full-effects option must say what it costs, not just its name.
        assertTrue(
            "the full-effects option never states the flash rate it unlocks",
            compose
                .onAllNodesWithText("9 Hz", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    /** The risk has to be named, or it is not consent. */
    @Test
    fun the_hazard_is_stated_before_the_choice() {
        compose
            .onNodeWithText("seizure", substring = true)
            .assertIsDisplayed()
    }
}
