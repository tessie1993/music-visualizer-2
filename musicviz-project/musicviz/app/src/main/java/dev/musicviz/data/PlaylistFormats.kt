package dev.musicviz.data

/** The playlist file formats this app can read. */
enum class PlaylistFormat { M3U, PLS, XSPF }

/**
 * One entry of a playlist file, as written rather than as resolved.
 *
 * [location] is whatever the exporting player put there - an absolute path from
 * another machine, a relative path, a `file://` uri, or an http url. It is not a
 * uri this device can open; [PlaylistFormats.resolve] is what turns it into one.
 */
data class PlaylistEntry(
    val location: String,
    val title: String = "",
    /** Milliseconds, whatever unit the file used; [PlaylistFormats.UNKNOWN_DURATION] when it did not say. */
    val durationMs: Long = PlaylistFormats.UNKNOWN_DURATION,
)

/** The outcome of reading a playlist file: a value, because failing is normal. */
sealed interface PlaylistParse {
    data class Parsed(
        val format: PlaylistFormat,
        val name: String,
        val entries: List<PlaylistEntry>,
    ) : PlaylistParse

    data class Unreadable(val why: String) : PlaylistParse
}

/**
 * What [PlaylistFormats.resolve] made of a playlist against this device.
 *
 * [missing] and [ambiguous] exist so an import can be reported rather than
 * merely performed: a 200-track playlist that quietly becomes 180 tracks is the
 * failure users notice weeks later, and the caller needs to be able to say which
 * ones and why.
 */
data class PlaylistResolution(
    val uris: List<String>,
    val missing: List<PlaylistEntry>,
    val ambiguous: List<PlaylistEntry>,
)

/**
 * Readers for the playlist files other players write, and the matching that
 * turns their paths into this device's uris.
 *
 * Parsing is pure text in, values out - no `Context`, no streams, no exceptions
 * for a malformed file. Reading and decoding the bytes is the caller's job,
 * which also keeps the .m3u-is-often-Latin-1 problem where the charset is known.
 *
 * The formats agree on almost nothing. PLS numbers its entries and may write
 * them in any order. XSPF measures duration in milliseconds where M3U and PLS
 * use seconds. Only M3U can name the playlist itself. All three are lenient
 * about case and whitespace in practice, so this is too - and where a file is
 * self-contradictory the entries win, because losing a track is worse than
 * losing its metadata.
 *
 * The XSPF reader handles the documented shape of the format and refuses a
 * DOCTYPE outright rather than expanding it: an imported playlist is untrusted
 * input, and entity expansion is the one thing in these formats that can read a
 * file the user did not pick.
 */
object PlaylistFormats {
    /** A duration the file did not state. Not zero, which would display as 0:00. */
    const val UNKNOWN_DURATION = -1L

    /** Enough of the file to see a header in, however long the file is. */
    private const val DETECT_WINDOW = 512

    private const val EXTINF = "#EXTINF:"
    private const val PLAYLIST_DIRECTIVE = "#PLAYLIST:"

    private val docType = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
    private val trackListTag = Regex("<(?:\\w+:)?trackList\\b[^>]*>(.*)</(?:\\w+:)?trackList>", RegexOption.DOT_MATCHES_ALL)
    private val trackTag = Regex("<(?:\\w+:)?track\\b[^>]*>(.*?)</(?:\\w+:)?track>", RegexOption.DOT_MATCHES_ALL)
    private val numericEntity = Regex("&#(\\d+);")

    /**
     * The three `<track>` children this reads, compiled once.
     *
     * Built here rather than per call because [childText] runs three times per
     * track, and a long XSPF is thousands of tracks.
     */
    private val locationTag = childTag("location")
    private val titleTag = childTag("title")
    private val durationTag = childTag("duration")

    /** `<tag>…</tag>`, with an optional namespace prefix and across newlines. */
    private fun childTag(tag: String): Regex = Regex("<(?:\\w+:)?$tag\\b[^>]*>(.*?)</(?:\\w+:)?$tag>", RegexOption.DOT_MATCHES_ALL)

    /** A run of percent-escapes, taken whole so multi-byte UTF-8 decodes. */
    private val percentRun = Regex("(?:%[0-9A-Fa-f]{2})+")

    /**
     * Reads [text] as a playlist, using [fileName] only for its extension and as
     * a fallback name.
     *
     * The format is decided by content first and extension second, because
     * plenty of .m3u files have no `#EXTM3U` header and plenty of playlists
     * arrive from SAF under a name that says nothing.
     */
    fun parse(
        fileName: String,
        text: String,
    ): PlaylistParse {
        // A byte-order mark left on the front of "#EXTM3U" is how an import ends
        // up with one unplayable track named after the header.
        val body = text.removePrefix("\uFEFF")
        return when (formatOf(fileName, body)) {
            null -> PlaylistParse.Unreadable("not an M3U, PLS or XSPF playlist")
            PlaylistFormat.M3U -> parseM3u(fileName, body.lines())
            PlaylistFormat.PLS -> parsePls(fileName, body.lines())
            PlaylistFormat.XSPF -> parseXspf(fileName, body)
        }
    }

    /**
     * Matches [entries] against the library, keyed by [baseName].
     *
     * Matching cannot use the path. The same physical file reaches this app
     * under several uri spellings and the playlist was written elsewhere
     * entirely, so the path identifies a route and the file name is what
     * survives - the same reasoning as `TrackLibrary.identityKey`, which pairs
     * the name with the byte size. A playlist file carries no byte size, so this
     * is that check with its second half missing: when a name matches more than
     * one track the first is taken and the entry is listed in
     * [PlaylistResolution.ambiguous], because dropping it would be worse and
     * choosing silently worse still.
     *
     * [urisByFileName] must be keyed by [baseName] or every lookup misses.
     */
    fun resolve(
        entries: List<PlaylistEntry>,
        urisByFileName: Map<String, List<String>>,
    ): PlaylistResolution {
        val uris = ArrayList<String>(entries.size)
        val missing = ArrayList<PlaylistEntry>()
        val ambiguous = ArrayList<PlaylistEntry>()
        for (entry in entries) {
            val candidates = urisByFileName[baseName(entry.location)].orEmpty()
            if (candidates.isEmpty()) {
                missing += entry
                continue
            }
            uris += candidates.first()
            if (candidates.size > 1) ambiguous += entry
        }
        return PlaylistResolution(uris, missing, ambiguous)
    }

    /**
     * The lower-cased file name [location] ends in - the key both sides of
     * [resolve] have to agree on, which is why it is public.
     *
     * Percent-escapes are decoded and both separators are honoured, since a
     * playlist written on Windows uses backslashes and one written by a
     * browser-based exporter is a `file://` uri.
     */
    fun baseName(location: String): String {
        // Only strip a query and fragment from something that is actually a uri:
        // "track#1.mp3" is a legal file name, and '#' there is not a fragment.
        val path =
            if (location.contains("://")) {
                location.substringBefore('?').substringBefore('#')
            } else {
                location
            }
        return percentDecoded(path).substringAfterLast('/').substringAfterLast('\\').lowercase()
    }

    private fun formatOf(
        fileName: String,
        text: String,
    ): PlaylistFormat? {
        val head = text.take(DETECT_WINDOW).trimStart()
        return when {
            head.startsWith("#EXTM3U", ignoreCase = true) -> PlaylistFormat.M3U
            head.startsWith("<") && head.contains("playlist", ignoreCase = true) -> PlaylistFormat.XSPF
            head.contains("[playlist]", ignoreCase = true) -> PlaylistFormat.PLS
            else -> byExtension(fileName)
        }
    }

    private fun byExtension(fileName: String): PlaylistFormat? =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "m3u", "m3u8" -> PlaylistFormat.M3U
            "pls" -> PlaylistFormat.PLS
            "xspf" -> PlaylistFormat.XSPF
            else -> null
        }

    /**
     * M3U: a location per line, each optionally preceded by its own `#EXTINF`.
     *
     * The metadata is attached to the next location and cleared once used, so a
     * stray `#EXTINF` with no line after it cannot label an unrelated track.
     */
    private fun parseM3u(
        fileName: String,
        lines: List<String>,
    ): PlaylistParse {
        var name = ""
        var title = ""
        var durationMs = UNKNOWN_DURATION
        val entries = ArrayList<PlaylistEntry>()
        for (line in lines.map(String::trim).filter(String::isNotEmpty)) {
            if (!line.startsWith("#")) {
                entries += PlaylistEntry(line, title, durationMs)
                title = ""
                durationMs = UNKNOWN_DURATION
            } else if (line.startsWith(PLAYLIST_DIRECTIVE, ignoreCase = true)) {
                name = line.substring(PLAYLIST_DIRECTIVE.length).trim()
            } else if (line.startsWith(EXTINF, ignoreCase = true)) {
                val info = extInf(line.substring(EXTINF.length))
                title = info.first
                durationMs = info.second
            }
        }
        return PlaylistParse.Parsed(PlaylistFormat.M3U, name.ifBlank { stemOf(fileName) }, entries)
    }

    /** `#EXTINF:<seconds>,<title>`, either half of which may be missing. */
    private fun extInf(rest: String): Pair<String, Long> {
        val comma = rest.indexOf(',')
        val seconds = (if (comma < 0) rest else rest.substring(0, comma)).trim().toLongOrNull()
        val title = if (comma < 0) "" else rest.substring(comma + 1).trim()
        return title to secondsToMs(seconds)
    }

    /**
     * PLS: `File1=`, `Title1=`, `Length1=`, in any order and any case.
     *
     * The number is the order, not the position in the file. `NumberOfEntries`
     * is deliberately ignored - it is routinely stale, and trusting it truncates
     * a playlist that is otherwise intact.
     */
    private fun parsePls(
        fileName: String,
        lines: List<String>,
    ): PlaylistParse {
        val files = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        val lengths = HashMap<Int, Long>()
        for (line in lines) {
            val eq = line.indexOf('=')
            // No '=' leaves the key empty, which has no digits to be an index by,
            // so section headers and Version= fall out with the malformed lines.
            val key = if (eq > 0) line.substring(0, eq).trim().lowercase() else ""
            val index = key.dropWhile { !it.isDigit() }.toIntOrNull() ?: continue
            val value = line.substring(eq + 1).trim()
            when {
                key.startsWith("file") -> files[index] = value
                key.startsWith("title") -> titles[index] = value
                key.startsWith("length") -> lengths[index] = value.toLongOrNull() ?: UNKNOWN_DURATION
            }
        }
        val entries =
            files.keys.sorted().map { i ->
                PlaylistEntry(files.getValue(i), titles[i].orEmpty(), secondsToMs(lengths[i]))
            }
        return PlaylistParse.Parsed(PlaylistFormat.PLS, stemOf(fileName), entries)
    }

    /** XSPF: `<track>` elements inside `<trackList>`, durations already in ms. */
    private fun parseXspf(
        fileName: String,
        text: String,
    ): PlaylistParse {
        val list = trackListTag.find(text)
        return when {
            docType.containsMatchIn(text) ->
                PlaylistParse.Unreadable("refused: a playlist with a DOCTYPE can name files the user did not pick")
            list == null -> PlaylistParse.Unreadable("no <trackList> element")
            else ->
                PlaylistParse.Parsed(
                    PlaylistFormat.XSPF,
                    xspfName(text.substring(0, list.range.first), fileName),
                    trackTag.findAll(list.groupValues[1]).mapNotNull(::xspfTrack).toList(),
                )
        }
    }

    /** The playlist's own `<title>`, which is the one above `<trackList>`. */
    private fun xspfName(
        header: String,
        fileName: String,
    ): String = childText(header, titleTag).ifBlank { stemOf(fileName) }

    /** One `<track>`, or null when it has no location to play. */
    private fun xspfTrack(match: MatchResult): PlaylistEntry? {
        val body = match.groupValues[1]
        val location = childText(body, locationTag)
        val duration = childText(body, durationTag).toLongOrNull()
        return if (location.isEmpty()) {
            null
        } else {
            PlaylistEntry(
                location,
                childText(body, titleTag),
                if (duration != null && duration >= 0) duration else UNKNOWN_DURATION,
            )
        }
    }

    /** Text of the first [tag] in [xml], with its entities decoded. */
    private fun childText(
        xml: String,
        tag: Regex,
    ): String = decodeEntities(tag.find(xml)?.groupValues?.get(1).orEmpty()).trim()

    private fun secondsToMs(seconds: Long?): Long = if (seconds != null && seconds >= 0) seconds * 1000L else UNKNOWN_DURATION

    private fun stemOf(fileName: String): String = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

    private fun percentDecoded(text: String): String =
        percentRun.replace(text) { run ->
            val bytes = run.value.chunked(3).map { escape -> escape.substring(1).toInt(16).toByte() }
            String(bytes.toByteArray(), Charsets.UTF_8)
        }

    /** `&amp;` is expanded last, so `&amp;lt;` stays `&lt;` rather than becoming `<`. */
    private fun decodeEntities(text: String): String =
        numericEntity
            .replace(text) { m ->
                val code = m.groupValues[1].toIntOrNull()
                if (code != null && code in 1..Char.MAX_VALUE.code) code.toChar().toString() else m.value
            }.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
