package dev.musicviz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlayerViewModel's main init block must be the LAST thing in the class.
 *
 * Kotlin runs property initializers and init blocks strictly in declaration
 * order, and the main init launches on Main.immediate - it executes
 * synchronously until its first suspension. While it sat mid-class, any
 * property declared after it was still null during that synchronous stretch;
 * the compiler does not catch it, so it shipped as an on-device NPE inside
 * the ViewModel constructor (the app failing to start) that Robolectric's
 * deferred looper hid. The fields it needed grew "MUST be declared before the
 * init block" comments - a rule enforced by nothing.
 *
 * With the init block at the physical end of the class, every property is
 * initialized before it runs and declaration order stops mattering. This scan
 * makes that placement the rule: a property declared after the final init
 * block fails the build instead of waiting to become a launch crash. Source
 * scanning, not reflection, following [ParamSurface]: the question is about
 * declaration ORDER, which the runtime objects do not record.
 */
class InitOrderTest {
    private val source = ParamSurface.source("ui/PlayerViewModel.kt")

    /** Class-member declarations sit at exactly one indent level (4 spaces). */
    private val memberProperty =
        Regex("""(?m)^ {4}(?:(?:private|internal|protected|public|override|lateinit|final)\s+)*va[lr]\s""")

    private fun afterFinalInit(): String {
        val marker = "\n    init {"
        val at = source.lastIndexOf(marker)
        assertTrue("PlayerViewModel has no init block at member indent", at >= 0)
        return source.substring(at)
    }

    @Test
    fun `the final init block is the main one`() {
        // The block that starts the engine and the 500 ms housekeeping loop
        // is the one whose synchronous stretch reads half the class - it is
        // the block this rule exists for, so it must be the one at the end.
        val tail = afterFinalInit()
        assertTrue(
            "the main init block (AudioBus consumer + housekeeping loop) is no longer the final one",
            tail.contains(".addConsumer()") && tail.contains("delay(500)"),
        )
    }

    @Test
    fun `no property is declared after the final init block`() {
        val offender = memberProperty.find(afterFinalInit())
        assertTrue(
            "property declared after the final init block - it would be null while the init's " +
                "synchronous stretch runs. Declare it above the init block. Offender: " +
                (offender?.value ?: ""),
            offender == null,
        )
    }

    @Test
    fun `the hand-enforced ordering comments are gone`() {
        // The placement rule replaced them; a comment coming back means
        // someone is about to re-create the hazard it described.
        assertFalse(source.contains("MUST be declared before"))
    }
}
