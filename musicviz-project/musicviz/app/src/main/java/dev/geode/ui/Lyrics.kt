package dev.geode.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** One line of a lyric sheet, with the time it lands on when timed. */
data class LyricLine(
    /** Milliseconds into the track, or -1 for an untimed line. */
    val timeMs: Long,
    val text: String,
)

/**
 * A track's words: timed if we can get them, plain if not.
 *
 * [synced] is what decides whether the player scrolls: an LRC sheet with
 * timestamps drives a highlighted current line, a plain text blob is just
 * something to read.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    /** Where they came from, for the "no lyrics" explanation. */
    val source: String,
) {
    /**
     * Index of the line playing at [positionMs], or -1 before the first one.
     *
     * Binary search rather than a scan: this is called on every UI tick, and
     * a long sheet is a few hundred lines.
     */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/**
 * Finds lyrics for a track the way a desktop player does: an `.lrc` sitting
 * next to the file first, then whatever the tags carry.
 *
 * Sidecar first on purpose. Embedded tags are usually the unsynced blob a
 * ripper wrote once; an `.lrc` is something a person went and found, and it is
 * the one that can be timed. When both exist the deliberate choice wins.
 *
 * No network: this app has no INTERNET permission and is not about to ask for
 * one to fetch lyrics. What is on the device is what there is.
 */
object LyricsLoader {
    /** Blocking; call on Dispatchers.IO. */
    fun load(
        context: Context,
        trackUri: Uri,
    ): Lyrics? = sidecar(context, trackUri) ?: embedded(context, trackUri)

    /**
     * `song.lrc` beside `song.mp3`.
     *
     * Handles both shapes a track uri comes in: a real `file://` path, where
     * the sibling is a plain file, and a document tree uri, where it has to be
     * found by listing the parent - there is no "swap the extension" operation
     * on an opaque document id.
     */
    private fun sidecar(
        context: Context,
        trackUri: Uri,
    ): Lyrics? =
        runCatching {
            when (trackUri.scheme) {
                "file" -> {
                    val path = trackUri.path ?: return null
                    val lrc = java.io.File(path.substringBeforeLast('.', path) + ".lrc")
                    if (lrc.isFile) parse(lrc.readText(), "an .lrc file next to the track") else null
                }
                "content" -> {
                    val doc = DocumentFile.fromSingleUri(context, trackUri) ?: return null
                    val name = doc.name ?: return null
                    val stem = name.substringBeforeLast('.', name)
                    val sibling =
                        doc.parentFile?.listFiles()?.firstOrNull {
                            it.name.equals("$stem.lrc", ignoreCase = true)
                        } ?: return null
                    context.contentResolver.openInputStream(sibling.uri)?.use {
                        parse(it.readBytes().toString(Charsets.UTF_8), "an .lrc file next to the track")
                    }
                }
                else -> null
            }
        }.getOrNull()

    private fun embedded(
        context: Context,
        trackUri: Uri,
    ): Lyrics? =
        runCatching {
            // try/finally rather than use(): MediaMetadataRetriever only became
            // AutoCloseable in API 29 and this app runs from 26.
            val retriever = android.media.MediaMetadataRetriever()
            val raw =
                try {
                    retriever.setDataSource(context, trackUri)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_WRITER)
                } finally {
                    retriever.release()
                }
            raw?.takeIf { it.isNotBlank() }?.let { parse(it, "the track's own tags") }
        }.getOrNull()

    /**
     * Parses LRC, falling back to plain text.
     *
     * A line can carry SEVERAL timestamps (`[00:12.00][01:04.00]same words`),
     * which is how LRC writes a repeated chorus without repeating it - each
     * one becomes its own line here, because the player wants a sorted list of
     * moments, not a list of texts with moments attached.
     */
    fun parse(
        text: String,
        source: String,
    ): Lyrics? {
        if (text.isBlank()) return null
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            val stamps = TAG.findAll(raw).toList()
            val body = raw.replace(TAG, "").trim()
            if (stamps.isEmpty()) {
                // `[ar:...]`, `[ti:...]` and friends are metadata, not words.
                if (body.isNotBlank() && !METADATA.matches(raw.trim())) plain += LyricLine(-1, body)
                continue
            }
            if (body.isBlank()) continue
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
                // The fractional part is hundredths in LRC's own spec and
                // thousandths in the wild; both appear, so read the width.
                val fractionText = stamp.groupValues[3]
                val fraction = fractionText.toLongOrNull() ?: 0L
                val fractionMs =
                    when (fractionText.length) {
                        0 -> 0L
                        1 -> fraction * 100
                        2 -> fraction * 10
                        else -> fraction
                    }
                timed += LyricLine(minutes * 60_000 + seconds * 1_000 + fractionMs, body)
            }
        }
        return when {
            timed.isNotEmpty() -> Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = source)
            plain.isNotEmpty() -> Lyrics(plain, synced = false, source = source)
            else -> null
        }
    }

    /** `[mm:ss.xx]`, with the fraction optional and of either width. */
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** `[ti:Title]` and the rest of LRC's header tags. */
    private val METADATA = Regex("""^\[[a-zA-Z]+:.*]$""")
}
