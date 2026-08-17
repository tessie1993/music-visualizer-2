package dev.geode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seek bar has to exist for people who are not using a pointer.
 *
 * It is drawn as a bare `Canvas` with `pointerInput` gestures, which gives it
 * no role, no value and no action — so TalkBack and switch-access users could
 * not seek anywhere in the app, on either screen that hosts it. For a media
 * player that is an accessibility gap with legal exposure in several markets,
 * and it is invisible to every other kind of test: the control still draws
 * correctly, it simply cannot be operated.
 *
 * Source-text assertions because the property is structural — "this control
 * carries seek semantics" — and the widget is shared by two screens, so the
 * cheapest honest check is that the semantics are on the one definition.
 */
class SeekAccessibilityTest {
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

    private val seekBar: String get() = File(sourceDir(), "ui/PlayerPanels.kt").readText()

    @Test
    fun `the seek bar reports its position to assistive technology`() {
        assertTrue(
            "the seek bar has no progressBarRangeInfo, so it announces no value",
            "progressBarRangeInfo" in seekBar,
        )
    }

    @Test
    fun `the seek bar can be operated without a pointer`() {
        assertTrue(
            "the seek bar has no setProgress action, so assistive tech cannot seek",
            "setProgress" in seekBar,
        )
    }

    @Test
    fun `the seek bar says what it is`() {
        assertTrue("the seek bar has no contentDescription", "contentDescription" in seekBar)
    }

    /**
     * The spoken value and the drawn value come from one place, so a screen
     * reader cannot announce a different time than the one on screen.
     */
    @Test
    fun `the announced time uses the same formatter as the label`() {
        assertTrue("the seek bar does not use formatClock", "formatClock(" in seekBar)
    }

    @Test
    fun `a track over an hour reads as hours, not as minutes past sixty`() {
        assertEquals("1:15:00", formatClock(75 * 60 * 1000L))
        assertEquals("2:06:07", formatClock((2 * 3600 + 6 * 60 + 7) * 1000L))
    }

    @Test
    fun `a short track keeps the plain minute form`() {
        assertEquals("3:24", formatClock((3 * 60 + 24) * 1000L))
        assertEquals("0:07", formatClock(7_000L))
    }

    @Test
    fun `a negative or zero position is floored rather than shown negative`() {
        assertEquals("0:00", formatClock(0L))
        assertEquals("0:00", formatClock(-5_000L))
    }

    /** A permanently denied permission must have somewhere to go. */
    @Test
    fun `the library offers a settings route when it can no longer ask`() {
        val library = File(sourceDir(), "ui/LibraryScreen.kt").readText()
        assertTrue(
            "the library still only re-launches the request, which does nothing after two refusals",
            "shouldShowRequestPermissionRationale" in library,
        )
        assertTrue("no settings escape hatch", "openAppSettings" in library)
    }
}
