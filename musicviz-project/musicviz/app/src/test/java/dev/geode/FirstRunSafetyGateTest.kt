package dev.geode

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.geode.render.VisualSafetyChoice
import dev.geode.ui.SafetyConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The consent screen itself: what it says, and what each answer means.
 *
 * Composed directly rather than launched through `MainActivity`. Robolectric
 * shares application state — preferences included — between test classes in one
 * JVM, so a MainActivity-level version of this test passed or failed depending
 * on whether another UI test had already clicked an answer, and clearing
 * preferences in an ordered rule did not reliably win that race. An
 * order-dependent test is worse than no test, and the gating decision it was
 * really trying to pin is a structural property, so it is asserted as one in
 * [SafetyConsentContractTest] instead.
 *
 * What is left here is what only a composed test can check: that the screen
 * states the hazard, offers every choice, and reports the right answer for each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstRunSafetyGateTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(onChoose: (VisualSafetyChoice, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent { SafetyConsent(onChoose = onChoose) }
    }

    private fun hasText(text: String): Boolean =
        compose
            .onAllNodesWithText(text, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** A consent screen that does not name the risk is not consent. */
    @Test
    fun the_hazard_is_stated_in_plain_words() {
        show()
        assertTrue("the seizure risk is never named", hasText("seizure"))
        assertTrue("the flash rate is never named", hasText("nine times a second"))
    }

    @Test
    fun every_choice_is_offered_with_its_consequences_spelled_out() {
        show()
        for (option in listOf("Keep it safe", "Safe, and easier on motion", "Full effects")) {
            assertTrue("the consent screen does not offer \"$option\"", hasText(option))
        }
        // The full-effects option must say what it costs, not just its name.
        assertTrue("the full-effects option never states the flash rate it unlocks", hasText("9 Hz"))
    }

    @Test
    fun the_user_is_told_the_visuals_are_limited_until_they_answer() {
        show()
        assertTrue(hasText("the visuals run limited"))
    }

    /**
     * Matched on contentDescription: each option's button carries "title.
     * detail" so a screen reader hears the substance of the choice rather than
     * a bare label, and that is the node a click lands on.
     */
    @Test
    fun keeping_it_safe_reports_the_safe_choice_with_limits_on() {
        var chosen: Pair<VisualSafetyChoice, Boolean>? = null
        show { choice, limited -> chosen = choice to limited }
        compose.onNodeWithContentDescription("Keep it safe", substring = true).performClick()
        assertEquals(VisualSafetyChoice.SAFE to true, chosen)
    }

    @Test
    fun reduced_motion_reports_its_own_choice_with_limits_on() {
        var chosen: Pair<VisualSafetyChoice, Boolean>? = null
        show { choice, limited -> chosen = choice to limited }
        compose.onNodeWithContentDescription("Safe, and easier on motion", substring = true).performClick()
        assertEquals(VisualSafetyChoice.REDUCED_MOTION to true, chosen)
    }

    /** The only answer that may lift the limiter, and it must say so explicitly. */
    @Test
    fun full_effects_is_the_only_answer_that_turns_limits_off() {
        var chosen: Pair<VisualSafetyChoice, Boolean>? = null
        show { choice, limited -> chosen = choice to limited }
        compose.onNodeWithContentDescription("Full effects", substring = true).performClick()
        assertEquals(VisualSafetyChoice.CUSTOM to false, chosen)
    }
}
