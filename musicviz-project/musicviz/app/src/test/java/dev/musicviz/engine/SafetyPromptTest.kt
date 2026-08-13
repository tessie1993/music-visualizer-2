package dev.musicviz.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Slice 0.3b. The prompt is the informed-consent half of the P0: safe visuals
 * are already ON before the choice, so this is not what makes the user safe -
 * it is what makes the opt-out legitimate.
 *
 * These are structural rather than Compose UI tests: the repository has no
 * Compose UI-test harness wired for the shell, and asserting the shape of the
 * prompt in source is honest about that, where a passing unit test on a
 * view-model flag would imply screen coverage that does not exist.
 */
class SafetyPromptTest {
    private val shell by lazy { sourceOf("app/src/main/java/dev/musicviz/ui/AppShell.kt") }

    @Test
    fun `the prompt blocks rather than being dismissible`() {
        // A tap outside must not count as an answer. The crash dialog in the
        // same file already sets this precedent.
        val prompt = promptBlock()
        assertTrue(
            "the safety prompt must not be dismissible by tapping away",
            "onDismissRequest = {}" in prompt,
        )
    }

    @Test
    fun `keeping safer visuals is the primary action`() {
        val prompt = promptBlock()
        val keep = prompt.indexOf("Keep safer visuals")
        val turnOff = prompt.indexOf("Turn off")
        assertTrue("the prompt must offer 'Keep safer visuals'", keep >= 0)
        assertTrue("the prompt must offer an opt-out", turnOff >= 0)
        assertTrue(
            "'Keep safer visuals' must be the confirm action, ahead of the opt-out",
            keep < turnOff,
        )
    }

    @Test
    fun `the opt-out carries a warning, not just a label`() {
        val prompt = promptBlock()
        assertTrue(
            "the opt-out must warn about flashing before it is taken",
            Regex("(?i)(seizure|photosensit|flash)").containsMatchIn(prompt),
        )
    }

    @Test
    fun `the prompt is shown from the stored choice, not a local flag`() {
        // safetyChoicePending() reads ThemeStore.safetyChoice().mustPrompt from
        // disk, so the prompt survives process death and cannot be cleared by
        // anything but a real answer. A hard-coded initial value here would
        // re-ask every launch, or never ask at all.
        assertTrue(
            "the shell must decide from the persisted choice",
            "viewModel.safetyChoicePending()" in shell,
        )
        val vm = sourceOf("app/src/main/java/dev/musicviz/ui/PlayerViewModel.kt")
        assertTrue(
            "safetyChoicePending must derive from the persisted choice",
            Regex("""fun safetyChoicePending\(\)[^\n]*themeStore\.safetyChoice\(\)\.mustPrompt""")
                .containsMatchIn(vm),
        )
    }

    @Test
    fun `answering the prompt records the choice version`() {
        // Otherwise the user is asked again on every launch, and the opt-out
        // never sticks.
        val vm = sourceOf("app/src/main/java/dev/musicviz/ui/PlayerViewModel.kt")
        assertTrue(
            "the view model must persist the answer with choiceMade = true",
            "choiceMade = true" in vm,
        )
        assertFalse(
            "an ordinary setGuiPrefs must not stamp the choice",
            Regex("""fun setGuiPrefs\([^)]*\)\s*\{\s*[^}]*choiceMade = true""").containsMatchIn(vm),
        )
    }

    /** The safety-prompt region of the shell, so assertions cannot pass on unrelated code. */
    private fun promptBlock(): String {
        val marker = shell.indexOf("SafetyChoicePrompt")
        assertTrue("AppShell must host a SafetyChoicePrompt", marker >= 0)
        val fn = shell.indexOf("private fun SafetyChoicePrompt")
        assertTrue("SafetyChoicePrompt must be defined in AppShell.kt", fn >= 0)
        return shell.substring(fn)
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "musicviz-project/musicviz/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        error("$relative not found from ${File("").absolutePath}")
    }
}
