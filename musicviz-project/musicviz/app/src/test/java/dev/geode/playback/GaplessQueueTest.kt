package dev.geode.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Gapless playback is a property of never rebuilding the pipeline mid-album.
 *
 * Media3 trims LAME/iTunSMPB encoder delay and padding sample-accurately, but
 * only *within one playlist*: advancing by `seekToNextMediaItem` keeps the sink
 * configured and the trim applied, while handing the player a new playlist for
 * the next track tears the pipeline down and builds it again. The audible result
 * is the click between album tracks that gapless exists to remove, and there is
 * no compile error, no failing assertion and no log line to find it by - it
 * sounds like a bad rip.
 *
 * So this pins the shape rather than the sound: a new playlist is handed over
 * only where a queue genuinely *opens*, and `prepare()` is only called there
 * too. Both are cheap to violate. "Next plays the wrong track, fix it by calling
 * setMediaItems with the right one" is a plausible-looking one-line change that
 * would pass every other test in this suite.
 *
 * Deliberately a source-text gate and not a Robolectric one. What would have to
 * be observed - that no sink reconfiguration happens across an item boundary and
 * that the trim was applied - needs a real audio pipeline; Robolectric has no
 * decoder, so a behavioural version of this test could only assert that the item
 * index changed, which is not the invariant. The honest device-side check is
 * still two spliced sine files and an FFT at the seam, by hand.
 */
class GaplessQueueTest {
    private companion object {
        private const val VIEW_MODEL = "ui/PlayerViewModel.kt"

        /**
         * Every function allowed to hand the player a playlist, and why each one
         * is a queue opening rather than a track advance.
         *
         * Adding a line here is a claim that playback is starting from nothing at
         * that point. If the honest description is "and then the next track
         * plays", the call does not belong in the function at all.
         */
        val QUEUE_OPENINGS: Map<String, String> =
            mapOf(
                "fun playFrom(" to "the one funnel a tap-a-row play takes: opens the list the track belongs to",
                "fun open(" to "files handed in from outside the app, which are a new queue by definition",
                "private fun prepareLastPlayed(" to "restores last session's track at startup, before anything plays",
            )

        /**
         * The other legitimate reason to prepare: recovery, not opening.
         *
         * A [androidx.media3.common.PlaybackException] leaves the player in
         * STATE_IDLE — the pipeline is already torn down by the failure — and
         * prepare() is the documented way back out. That is the opposite of the
         * case [QUEUE_OPENINGS] guards against, which is re-preparing a pipeline
         * that is still running fine.
         *
         * Kept separate from [QUEUE_OPENINGS] on purpose: recovery may prepare,
         * but it may NOT hand the player a new playlist, and the setMediaItems
         * test still holds it to that.
         */
        val ERROR_RECOVERY: Map<String, String> =
            mapOf(
                "override fun onPlayerError(" to "the player is in STATE_IDLE after a failure; prepare() is the way back",
            )

        /** Navigation within the queue: what an advance is allowed to be. */
        val TRANSPORT: List<String> = listOf("fun next(", "fun previous(")

        /**
         * Files that own a PRIVATE player and may hand IT a playlist.
         *
         * The gapless invariant guards the shared music pipeline; a muted
         * one-clip preview player torn down with its screen has no album to
         * be gapless across. An entry here is only honoured when the file
         * really does build its own ExoPlayer - verified below - so the
         * exemption cannot be borrowed to smuggle a queue rebuild into the
         * shared player.
         */
        val PRIVATE_PLAYERS: Map<String, String> =
            mapOf(
                "ui/StudioPreview.kt" to
                    "muted clip-preview player, released when the editor closes; the music pipeline never sees it",
            )
    }

    @Test
    fun `only a queue opening hands the player a playlist`() {
        val text = withoutComments(source(VIEW_MODEL))
        val declared = QUEUE_OPENINGS.keys.sumOf { occurrences(functionBody(text, it), "setMediaItems(") }
        assertEquals(
            "setMediaItems is called somewhere that is not a declared queue opening. If a queue really " +
                "opens there, declare it in QUEUE_OPENINGS with a reason; if it is a track advance, it " +
                "rebuilds the audio pipeline and loses the gapless trim - navigate the playlist instead",
            occurrences(text, "setMediaItems("),
            declared,
        )
        assertTrue("no setMediaItems call found at all - has the funnel moved?", declared > 0)
    }

    @Test
    fun `no other file hands the player a playlist`() {
        val offenders =
            mainSources()
                .filter { (path, text) -> path != VIEW_MODEL && withoutComments(text).contains("setMediaItems(") }
                .filterNot { (path, _) -> path in PRIVATE_PLAYERS }
                .map { (path, _) -> path }
                .sorted()
        assertEquals(
            "a second place builds the queue - the player has one playlist and one owner",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `a private-player exemption is reasoned and builds its own player`() {
        for ((path, reason) in PRIVATE_PLAYERS) {
            assertTrue("$path has no honest reason for a private player", reason.length > 20)
            assertTrue(
                "$path claims a private player but never builds one - it is using the shared pipeline",
                source(path).contains("ExoPlayer.Builder("),
            )
        }
    }

    @Test
    fun `the single-item form is never used`() {
        // setMediaItem(one) left the player holding a ONE-item queue, so Next and
        // Previous had nowhere to go - the bug playFrom's KDoc describes. It is
        // also a pipeline rebuild per track, so it breaks gapless twice over.
        val offenders =
            mainSources()
                .filter { (_, text) -> Regex("setMediaItem\\s*\\(").containsMatchIn(withoutComments(text)) }
                .map { (path, _) -> path }
                .sorted()
        assertEquals(
            "setMediaItem (singular) rebuilds the pipeline for every track and strands the transport",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `preparing happens only where a queue opens`() {
        // prepare() after an item transition is the same teardown by another
        // name: it reinitialises the source and the sink for a queue the player
        // is already playing.
        val text = withoutComments(source(VIEW_MODEL))
        val allowed = QUEUE_OPENINGS.keys + ERROR_RECOVERY.keys
        val declared = allowed.sumOf { occurrences(functionBody(text, it), ".prepare()") }
        assertEquals(
            "prepare() is called outside a queue opening or an error recovery, which reinitialises " +
                "a pipeline that is already running",
            occurrences(text, ".prepare()"),
            declared,
        )
    }

    @Test
    fun `next and previous move within the playlist`() {
        val text = withoutComments(source(VIEW_MODEL))
        for (transport in TRANSPORT) {
            val body = functionBody(text, transport)
            assertFalse(
                "$transport hands over a new playlist instead of navigating the current one",
                body.contains("setMediaItems("),
            )
            assertFalse("$transport re-prepares the player", body.contains(".prepare()"))
            assertTrue(
                "$transport no longer navigates the playlist - gapless only survives within one",
                body.contains("seekToNextMediaItem()") || body.contains("seekToPreviousMediaItem()"),
            )
        }
    }

    @Test
    fun `the comment stripper does not hide a real call`() {
        // Four of the seven textual matches for setMediaItems in main/ are prose
        // about the bug it replaced, so every test above reads stripped source -
        // which makes the stripper itself load-bearing.
        val stripped =
            withoutComments(
                """
                // player.setMediaItems(gone)
                /** KDoc mentioning setMediaItems(also gone) */
                /* block
                   setMediaItems(gone too) */
                val url = "https://example.com/a" // trailing
                player.setMediaItems(kept)
                """.trimIndent(),
            )
        assertEquals(1, occurrences(stripped, "setMediaItems("))
        assertTrue("a // inside a string literal truncated the line", stripped.contains("https://example.com/a"))
    }

    /** [source] with comments removed, so prose about a call is not a call. */
    private fun withoutComments(source: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(source, "")
            .lines()
            .joinToString("\n") { beforeLineComment(it) }

    /**
     * [line] up to its `//`, ignoring one inside a string literal.
     *
     * The quote count is a heuristic - it does not understand escapes or raw
     * strings - but it is the direction that matters: a `//` in a URL must not
     * swallow the rest of the line and hide a call after it.
     */
    private fun beforeLineComment(line: String): String {
        var quotes = 0
        for (i in 0 until (line.length - 1).coerceAtLeast(0)) {
            if (line[i] == '"') quotes++
            if (line[i] == '/' && line[i + 1] == '/' && quotes % 2 == 0) return line.substring(0, i)
        }
        return line
    }

    private fun occurrences(
        text: String,
        needle: String,
    ): Int = text.split(needle).size - 1

    /** The body of [signature]'s function, by brace matching from its `{`. */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val at = source.indexOf(signature)
        if (at < 0) fail("$signature not found in $VIEW_MODEL")
        var depth = 0
        var i = source.indexOf('{', at)
        val start = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        fail("unbalanced braces after $signature")
        error("unreachable")
    }

    /** Every main-source Kotlin file, keyed by its path below `dev/geode/`. */
    private fun mainSources(): List<Pair<String, String>> {
        val root = repoDir("src/main/java/dev/geode")
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    private fun source(relative: String): String =
        File(repoDir("src/main/java/dev/geode"), relative).also {
            if (!it.isFile) fail("$relative not found")
        }.readText()

    /** Resolves a directory under `app/`, whichever directory the tests run from. */
    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
