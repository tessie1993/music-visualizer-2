package dev.musicviz.data

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Replaces a file's contents in one step, so a reader never sees half of it.
 *
 * Most stores here keep a document - the play history, a playlist, a palette,
 * a take, an imported texture - in a file they rewrite whole. Written
 * directly, that is a window in which the file has been truncated to zero:
 * the app being killed inside it (or the device losing power) leaves invalid
 * JSON or a half-copied image behind. Because every store parses inside
 * `runCatching { … }.getOrDefault(emptyList())`, the damage is SILENT - the
 * user simply finds their history, playlists, palettes, textures or takes
 * empty, and the next write makes that emptiness permanent.
 *
 * The fix is the usual one: write a sibling temp file, get the bytes onto the
 * disk, then rename it over the target. Rename within a directory is atomic
 * on the local filesystem, so the target only ever holds a whole document -
 * either the old one or the new one - and a write that cannot complete leaves
 * the previous one exactly where it was. Losing one write is recoverable;
 * losing the document is not.
 *
 * Kept free of Android so the headless suite can exercise it, including the
 * case that matters and is otherwise unreachable: an interrupted write
 * leaving the previous contents intact.
 */
object AtomicWrite {
    /**
     * Suffix for the in-progress copy.
     *
     * Public because [TextureStore] and [TakeStore] find their documents by
     * listing a directory, and an in-progress copy must never be mistaken for
     * a saved item. It is appended to the WHOLE name rather than replacing
     * the extension (`chill.json` -> `chill.json.tmp`) precisely so that the
     * `extension == "json"` and image-extension filters those listings
     * already use exclude it without needing to know this constant at all.
     */
    const val TEMP_SUFFIX = ".tmp"

    /** Suffix [quarantine] moves unparseable content to. Excluded from listings for the same reason as [TEMP_SUFFIX]. */
    const val CORRUPT_SUFFIX = ".corrupt"

    /**
     * Writes [text] to [file], atomically. Returns false and leaves any
     * existing file untouched when it could not be completed.
     */
    fun text(
        file: File,
        text: String,
    ): Boolean = stream(file) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }

    /**
     * [text], for content that is produced rather than held in a string -
     * copying a picked image in, say. [body] is handed the temp file's
     * stream; the rename only happens once it has returned and the bytes are
     * on the disk, so a copy that dies part-way leaves the previous file
     * whole instead of a truncated image the picture loader will reject.
     */
    fun stream(
        file: File,
        body: (OutputStream) -> Unit,
    ): Boolean {
        val parent = file.parentFile
        // A missing parent is not an error to swallow silently: a store whose
        // directory was removed under it should still be able to save.
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        val temp = File(file.absolutePath + TEMP_SUFFIX)
        val ok =
            runCatching {
                FileOutputStream(temp).use { out ->
                    body(out)
                    out.flush()
                    // Without this the rename can reach the disk before the
                    // bytes do, and a power loss then leaves an atomically
                    // renamed EMPTY file - which defeats the point of the
                    // temp file entirely.
                    out.fd.sync()
                }
                temp.renameTo(file)
            }.getOrDefault(false)
        // Clearing up matters as much as the write: a temp file left behind
        // by a crash is stale content that the next write must be free to
        // overwrite, and it must never accumulate in a listed directory.
        if (!ok) runCatching { temp.delete() }
        return ok
    }

    /**
     * Moves content that read but did not parse out of the way, keeping the
     * bytes recoverable.
     *
     * A store that treats unparseable content as "nothing saved yet" writes
     * its next document straight over it, which is the same data loss the
     * temp file above prevents - only slower. Renaming instead lets the store
     * start fresh rather than failing forever, without destroying what it
     * could not read. Returns whether the file was moved.
     */
    fun quarantine(file: File): Boolean = runCatching { file.renameTo(File(file.absolutePath + CORRUPT_SUFFIX)) }.getOrDefault(false)
}
