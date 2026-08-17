package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the app's words live.
 *
 * The app shipped with fourteen strings in `strings.xml` and every other word
 * typed straight into Kotlin, which means it renders in English no matter what
 * language the phone is set to. That is not a polish item: an app whose UI
 * cannot follow the device locale cannot be listed as supporting any other
 * language, so it is invisible to most of the store.
 *
 * Extraction is the work, but keeping it extracted is the part a test has to
 * do — one `Text("Cancel")` typed into a screen next year is not visible in
 * review and never comes back. So the covered files are declared here, scanned
 * as text, and any user-visible literal inside them fails the build.
 *
 * ## Why the file list is explicit
 *
 * Two lists rather than "scan everything": [localized] is finished and must
 * stay clean, [pending] is not converted yet and says why. A pending entry
 * that no longer exists fails too, so the list cannot quietly rot into a
 * permanent excuse — and moving a file between the lists is the visible act of
 * finishing it.
 *
 * Parsing source text rather than inspecting the compiled UI follows the other
 * gates here (`ParamSurface`, `ParamRandomizerFluidTest`): the question is
 * about the CODE — whether a word was written as a literal or as a resource —
 * and that distinction is gone by the time anything runs.
 */
class LocalizationSurfaceTest {
    /**
     * The Gradle module root, found by walking up from wherever the test
     * runner happened to start — the same trick `ParamSurface` uses, because
     * the unit-test working directory is not the module directory.
     */
    private val moduleRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("geode project root not found from ${File("").absolutePath}")

    private val sources = File(moduleRoot, "app/src/main/java/dev/geode")
    private val strings = File(moduleRoot, "app/src/main/res/values/strings.xml")

    /**
     * Converted. No user-visible literal may appear in these.
     */
    private val localized: List<String> =
        listOf(
            "ui/AppShell.kt",
            "ui/SettingsDialog.kt",
            "ui/ExternalAudioSettings.kt",
            "ui/ExportSettings.kt",
            "ui/AudioSettings.kt",
            "ui/FolderSettings.kt",
            "ui/EqualizerSettings.kt",
            "ui/PlaybackSettings.kt",
            "ui/StudioScreen.kt",
            "ui/LibraryScreen.kt",
            "ui/VisualizerScreen.kt",
            "ui/PlayerPanels.kt",
            "ui/PlayerScreen.kt",
            "ui/SafetyConsent.kt",
            "ui/TrackInfoEditor.kt",
            "ui/AboutSettings.kt",
            "ui/LookSettings.kt",
            "ui/BehaviorSettings.kt",
            "ui/AutoVisualsSettings.kt",
        )

    /**
     * Not converted, and why. Each of these needs work beyond swapping a
     * literal for a lookup, so each is its own slice rather than a hurried
     * edit inside someone else's.
     */
    private val pending: Map<String, String> =
        mapOf(
            "ui/CustomizeTabs.kt" to
                "the key/label split exists now (LabeledSlider display=), so conversion is " +
                "mechanical - each control keeps its English key and gains a translated display",
            "ui/VisualsHub.kt" to
                "renders the Customize surface, so it shares CustomizeTabs' key-vs-label problem",
            "ui/PaletteMaker.kt" to "carries Customize-keyed control labels",
            "ui/CrystalControls.kt" to "carries Customize-keyed control labels",
            "ui/BuiltInPresets.kt" to "preset names are persisted identifiers, not display text",
            "ui/theme/ThemePackCatalog.kt" to "theme pack ids are persisted identifiers",
        )

    /**
     * Words that reach the screen but must not be translated, and why.
     *
     * The distinction is whether the string is only ever *shown*. A genre chip
     * is also *written* — into the track's genre field, where it is matched
     * against the tags already on the files — so translating it would make one
     * chip save a different value per device language. Anything listed here
     * needs its reason to survive review, which is why it is a map.
     */
    private val storedNotShown: Map<String, Map<String, String>> =
        mapOf(
            "ui/TrackInfoEditor.kt" to
                listOf("Electronic", "Rock", "Pop", "Hip-Hop", "Jazz", "Classical", "Ambient", "Other")
                    .associateWith { "written into the track's genre tag, so it must be the same word on every device" },
        )

    /**
     * Every string literal in the file, with the line it starts on.
     *
     * Written by hand rather than with one regex because Kotlin's raw strings,
     * escapes and `${...}` templates all defeat the obvious `"([^"]*)"` — and
     * the interesting literals here are precisely the long, interpolated,
     * multi-line ones that a naive pattern skips. KDoc and `//` comments are
     * skipped, so an example in a doc comment is not a finding.
     */
    private class Scanner(
        private val text: String,
    ) {
        private var at = 0
        private var line = 1
        val found = mutableListOf<Pair<Int, String>>()

        fun scan(): List<Pair<Int, String>> {
            while (at < text.length) {
                when {
                    text[at] == '\n' -> {
                        line++
                        at++
                    }
                    text.startsWith("//", at) -> skipTo(text.indexOf('\n', at))
                    text.startsWith("/*", at) -> skipTo(endOf("*/", at + 2))
                    text.startsWith("\"\"\"", at) -> takeRaw()
                    text[at] == '"' -> takeQuoted()
                    else -> at++
                }
            }
            return found
        }

        /** Moves past a run, counting the newlines inside it. */
        private fun skipTo(exclusiveEnd: Int) {
            val stop = if (exclusiveEnd < 0) text.length else exclusiveEnd
            line += text.substring(at, stop).count { it == '\n' }
            at = stop
        }

        private fun endOf(
            token: String,
            from: Int,
        ): Int {
            val found = text.indexOf(token, from)
            return if (found < 0) text.length else found + token.length
        }

        private fun takeRaw() {
            val bodyStart = at + 3
            val close = text.indexOf("\"\"\"", bodyStart)
            val bodyEnd = if (close < 0) text.length else close
            found += line to text.substring(bodyStart, bodyEnd)
            skipTo(if (close < 0) text.length else close + 3)
        }

        private fun takeQuoted() {
            val start = line
            val body = StringBuilder()
            at++
            while (at < text.length && text[at] != '"') {
                if (text[at] == '\\' && at + 1 < text.length) {
                    body.append(text[at + 1])
                    at += 2
                    continue
                }
                if (text[at] == '\n') line++
                body.append(text[at])
                at++
            }
            at++
            found += start to body.toString()
        }
    }

    private fun literalsOf(text: String): List<Pair<Int, String>> = Scanner(text).scan()

    /**
     * Shapes that are code wearing quotes: identifiers, keys, mime types,
     * paths, formats. None of them is language, and flagging them would push
     * the next person to suppress the gate rather than use it.
     */
    private val notLanguage =
        listOf(
            // camelCase identifier, animation label
            Regex("""^[a-z][A-Za-z0-9_]*$"""),
            // SCREAMING_CASE constant
            Regex("""^[A-Z0-9_]+$"""),
            // file name, prefs name, dotted key, package
            Regex("""^[a-z0-9_.\-]+$"""),
            // mime type, uri, path — no spaces
            Regex("""^\S*[/:]\S*$"""),
            // punctuation, separators, numbers
            Regex("""^[^A-Za-z]*$"""),
            // a bare format specifier
            Regex("""^%[-\d.]*[a-zA-Z]$"""),
            // date/time skeleton
            Regex("""^[yMdHhmsSaEz:.\-/ ]+$"""),
        )

    /**
     * Does this literal put words in front of a person?
     *
     * A literal counts when what is left after removing `${...}` holes reads
     * like prose — two or more letter-runs, or one that is a word rather than
     * an identifier. Interpolation holes are removed first so
     * `"Bass boost ${x}%"` is judged on "Bass boost".
     */
    @Suppress("ReturnCount")
    private fun isLanguage(raw: String): Boolean {
        val bare =
            raw
                .replace(Regex("""\$\{[^}]*}"""), " ")
                .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), " ")
                .trim()
        if (bare.length < 2 || bare.none { it.isLetter() }) return false
        if (notLanguage.any { it.matches(bare) }) return false
        val words = bare.split(Regex("""[^A-Za-z']+""")).filter { it.length > 1 }
        if (words.isEmpty()) return false
        // One word is language only if it is written like a word — "Cancel",
        // not "playlistRowShift" or "sRGB".
        return words.size > 1 || Regex("""^[A-Z]?[a-z]+$""").matches(words.single())
    }

    private fun offendersIn(relative: String): List<String> {
        val file = File(sources, relative)
        assertTrue("$relative is listed here but does not exist", file.isFile)
        val exempt = storedNotShown[relative].orEmpty()
        return literalsOf(file.readText())
            .filter { (_, literal) -> isLanguage(literal) && literal !in exempt }
            .map { (line, literal) -> "$relative:$line  \"${literal.take(60)}\"" }
    }

    @Test
    fun `converted screens carry no hardcoded user-visible text`() {
        val offenders = localized.flatMap(::offendersIn)
        assertEquals(
            "these read out in English regardless of device language:\n${offenders.joinToString("\n")}",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every pending file still exists and is still declared with a reason`() {
        for ((relative, reason) in pending) {
            assertTrue("$relative is pending conversion but is gone", File(sources, relative).isFile)
            assertTrue("$relative has no reason for being pending", reason.length > 20)
        }
    }

    @Test
    fun `an untranslated literal is only excused with a reason, on a converted file`() {
        for ((relative, reasons) in storedNotShown) {
            assertTrue("$relative excuses literals but is not a converted file", relative in localized)
            for ((literal, reason) in reasons) {
                assertTrue("\"$literal\" in $relative has no reason to stay English", reason.length > 20)
            }
        }
    }

    @Test
    fun `a file is either converted or declared pending, never both`() {
        val both = localized.toSet() intersect pending.keys
        assertEquals("listed as done and as pending", emptySet<String>(), both)
    }

    @Test
    fun `every string the code asks for is defined`() {
        val declared =
            Regex("""<string name="([^"]+)"""")
                .findAll(strings.readText())
                .map { it.groupValues[1] }
                .toSet()
        val referenced =
            sources
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { f -> Regex("""R\.string\.([A-Za-z0-9_]+)""").findAll(f.readText()).map { it.groupValues[1] } }
                .toSet()
        val missing = (referenced - declared).sorted()
        assertEquals("referenced from Kotlin but absent from strings.xml", emptyList<String>(), missing)
    }

    /**
     * The mechanism the Customize conversion depends on: a control's lock and
     * randomizer identity ([label], stable English, persisted) is a separate
     * argument from what the user reads ([display], translatable). If a
     * refactor collapses them again, translating a label would silently break
     * every saved lock and turn the randomizer's matching into a no-op -
     * invisible in review, permanent in prefs.
     */
    @Test
    fun `customize controls key their locks on label while rendering display`() {
        val tabs = File(sources, "ui/CustomizeTabs.kt").readText()
        for (shape in listOf("LabeledSlider", "LabeledIntSlider", "CheckRow", "LockableChipLabel")) {
            val declaration =
                Regex("""fun $shape\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(tabs)
                    ?.groupValues
                    ?.get(1)
            assertTrue("$shape is gone from CustomizeTabs", declaration != null)
            assertTrue(
                "$shape lost its display/label split - localizing it would break lock persistence",
                declaration!!.contains("display: String = label"),
            )
        }
        assertTrue(
            "LockChip must be keyed on label, never on display",
            !Regex("""LockChip\(display\)""").containsMatchIn(tabs),
        )
        assertTrue(
            "ControlLabelRow's lockKey position must receive label",
            Regex("ControlLabelRow\\(\"\\$" + "display[^\"]*\", label\\)").containsMatchIn(tabs),
        )
    }

    @Test
    fun `the shipped languages are declared for the system language picker`() {
        val config = File(moduleRoot, "app/src/main/res/xml/locales_config.xml")
        assertTrue("Android 13+ cannot offer a per-app language without this", config.isFile)
        assertTrue(
            "the manifest does not point at the locale config, so the picker never appears",
            File(moduleRoot, "app/src/main/AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""),
        )
    }
}
