package dev.geode

import dev.geode.render.VisualSafety
import dev.geode.render.VisualSafetyChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first-run safety question, held to its two promises.
 *
 * Geode can flash the whole screen at 9 Hz with limits off, which is squarely
 * in the band that provokes photosensitive seizures. Two things have to be true
 * and stay true: an unanswered app runs limited, and the screen that asks does
 * not itself demonstrate the hazard.
 *
 * Source-text assertions rather than UI tests because what is being pinned is a
 * design commitment — "this screen must not animate a flash" is a property of
 * what the file is allowed to contain, and a UI test would only catch it if it
 * happened to sample the right frame.
 */
class SafetyConsentContractTest {
    private val consentFile get() = File(sourceDir(), "ui/SafetyConsent.kt")

    private val source: String get() = consentFile.readText()

    /**
     * Resolves the main source root whichever directory the tests run from —
     * the same upward walk the other source-text gates use, because Gradle's
     * working directory for a test task is not the repo root.
     */
    private fun sourceDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, prefix + "src/main/java/dev/geode")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("source root not found from ${File("").absolutePath}")
    }

    @Test
    fun `the consent screen exists`() {
        assertTrue("SafetyConsent.kt is missing at ${consentFile.path}", consentFile.isFile)
    }

    /** The promise the whole feature rests on: silence is not consent. */
    @Test
    fun `an unanswered choice resolves to the safe limits`() {
        val resolved = VisualSafety.resolve(VisualSafetyChoice.UNKNOWN, permissive())
        assertEquals(VisualSafety.SafetyConfig.SAFE_DEFAULTS, resolved)
        assertTrue("an unanswered app is not limited", resolved.enabled)
        assertFalse("an unanswered app is neutral, i.e. unclamped", resolved.isNeutral)
    }

    /** Only an explicit answer can turn the limiter off. */
    @Test
    fun `only the custom choice can lift the limits`() {
        for (choice in VisualSafetyChoice.entries) {
            val resolved = VisualSafety.resolve(choice, permissive())
            if (choice == VisualSafetyChoice.CUSTOM) {
                assertFalse("CUSTOM must pass the stored config through", resolved.enabled)
            } else {
                assertTrue("$choice must stay limited", resolved.enabled)
            }
        }
    }

    /**
     * The screen must not demonstrate the hazard it is asking about. Showing an
     * unconsented user a 9 Hz sample to help them decide about 9 Hz samples is
     * the exact harm the screen exists to prevent, so it carries no animation
     * clock at all.
     */
    @Test
    fun `the consent screen contains no animation`() {
        val text = source
        val forbidden =
            listOf(
                "rememberInfiniteTransition",
                "animateFloat",
                "withFrameNanos",
                "LaunchedEffect",
                "AnimatedVisibility",
                "GLSurfaceView",
                "VisualizerScreen",
            )
        val found = forbidden.filter { it in text }
        assertEquals("the consent screen must not animate or render visuals", emptyList<String>(), found)
    }

    /** Every choice the settings screen offers has to be reachable here too. */
    @Test
    fun `the screen offers every real choice`() {
        val text = source
        for (choice in VisualSafetyChoice.entries) {
            if (choice == VisualSafetyChoice.UNKNOWN) continue
            assertTrue("$choice is not offered on the consent screen", "VisualSafetyChoice.$choice" in text)
        }
    }

    /** A consent screen that does not name the risk is not consent. */
    @Test
    fun `the screen states the hazard in plain words`() {
        val text = source.lowercase()
        assertTrue("the seizure risk is never named", "seizure" in text)
        assertTrue("the flash rate is never named", "nine times a second" in text || "9 hz" in text)
    }

    /**
     * The gate itself, asserted structurally.
     *
     * A UI test of this was order-dependent — Robolectric shares preferences
     * between test classes, so whichever screen test ran first left an answer
     * behind. What actually has to be true is that the shell shows the consent
     * screen while the stored choice is UNKNOWN, and that is a property of the
     * shell's source.
     */
    @Test
    fun `the shell gates the app on an unanswered choice`() {
        val shell = File(sourceDir(), "ui/AppShell.kt").readText()
        assertTrue("the shell never mounts the consent screen", "SafetyConsent(" in shell)
        assertTrue(
            "the consent screen is not gated on an unanswered choice",
            "VisualSafetyChoice.UNKNOWN" in shell,
        )
    }

    private fun permissive() =
        VisualSafety.SafetyConfig(
            enabled = false,
            maxFlashHz = 30f,
            maxFlashDepth = 1f,
            allowInversion = true,
            reducedMotion = false,
        )
}
