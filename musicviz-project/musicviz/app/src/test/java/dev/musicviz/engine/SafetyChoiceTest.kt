package dev.musicviz.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 0.3 — the P0 the master plan opens with.
 *
 * Today `GuiPrefs.safeVisuals` defaults to `false` and the persisted default
 * matches, so a 9 Hz full-frame strobe is reachable by a user who has never
 * been asked anything. The defect is not the clamp - [dev.musicviz.render.VisualSafety]
 * is sound - it is that **absence of a choice is being read as consent**.
 *
 * These tests pin the distinction that fixes it: a stored `safeVisuals=false`
 * with no recorded choice version is an unanswered question, not an opt-out.
 */
class SafetyChoiceTest {
    @Test
    fun `a fresh install has made no choice`() {
        val choice = SafetyChoice.resolve(storedVersion = null, storedSafeVisuals = false)
        assertEquals(SafetyChoice.NotChosen, choice)
    }

    @Test
    fun `with no choice recorded, safe visuals are on`() {
        val choice = SafetyChoice.resolve(storedVersion = null, storedSafeVisuals = false)
        assertTrue("safe visuals must be ON until the user chooses", choice.safeVisuals)
    }

    @Test
    fun `with no choice recorded, the app owes the user the prompt`() {
        assertTrue(SafetyChoice.resolve(null, storedSafeVisuals = false).mustPrompt)
    }

    @Test
    fun `an upgraded install's old false is not informed consent`() {
        // The exact regression this slice exists to prevent. Before v2 the
        // default was false and nobody was asked, so reading that stored false
        // as an opt-out would silently keep every existing user unprotected.
        val choice = SafetyChoice.resolve(storedVersion = null, storedSafeVisuals = false)
        assertTrue("an upgrade with no v2 choice must be protected", choice.safeVisuals)
        assertTrue("and must still be asked", choice.mustPrompt)
    }

    @Test
    fun `an explicit opt-in is honored and not re-asked`() {
        val choice =
            SafetyChoice.resolve(
                storedVersion = SafetyChoice.CURRENT_VERSION,
                storedSafeVisuals = true,
            )
        assertTrue(choice.safeVisuals)
        assertFalse(choice.mustPrompt)
    }

    @Test
    fun `an explicit opt-out is honored`() {
        // Adults may turn this off. The plan requires the opt-out to survive -
        // the fix for the P0 is informed consent, not removing the choice.
        val choice =
            SafetyChoice.resolve(
                storedVersion = SafetyChoice.CURRENT_VERSION,
                storedSafeVisuals = false,
            )
        assertFalse(choice.safeVisuals)
        assertFalse(choice.mustPrompt)
    }

    @Test
    fun `an opt-out is reported as unrestricted by user choice`() {
        // So exports, takes and diagnostics can say WHY a frame was unclamped
        // instead of implying the safety pass simply did nothing.
        val optedOut = SafetyChoice.resolve(SafetyChoice.CURRENT_VERSION, storedSafeVisuals = false)
        assertEquals(SafetyPolicy.UnrestrictedByUserChoice, optedOut.policy)

        val protected = SafetyChoice.resolve(SafetyChoice.CURRENT_VERSION, storedSafeVisuals = true)
        assertEquals(SafetyPolicy.Clamped, protected.policy)

        val unasked = SafetyChoice.resolve(null, storedSafeVisuals = false)
        assertEquals(
            "an unasked user is clamped by default, not 'by user choice'",
            SafetyPolicy.ClampedPendingChoice,
            unasked.policy,
        )
    }

    @Test
    fun `a choice from an older version is asked again`() {
        // The version marker exists so a future change to what the choice MEANS
        // can re-ask rather than inheriting consent for a different question.
        val stale = SafetyChoice.resolve(storedVersion = 0, storedSafeVisuals = false)
        assertEquals(SafetyChoice.NotChosen, stale)
        assertTrue(stale.safeVisuals)
    }

    @Test
    fun `a choice from a newer version is respected, not downgraded`() {
        // A preference file written by a newer build must not cause a re-prompt
        // loop or silently flip the user's setting back on.
        val newer =
            SafetyChoice.resolve(
                storedVersion = SafetyChoice.CURRENT_VERSION + 1,
                storedSafeVisuals = false,
            )
        assertFalse(newer.mustPrompt)
        assertFalse(newer.safeVisuals)
    }
}
