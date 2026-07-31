package dev.musicviz.ui

import java.io.File
import java.io.FileOutputStream

/**
 * Replaces a file's contents in one step, so a reader never sees half of it.
 *
 * Every store here keeps a document — the library, a playlist, a preset, the
 * palettes, the play history — in a single JSON file that it rewrites whole.
 * Written directly, that is a window in which the file is truncated: the app
 * being killed mid-write (or the device losing power) leaves invalid JSON, and
 * because the stores parse inside `runCatching` the damage is silent. The user
 * simply finds their library or their presets empty.
 *
 * The fix is the usual one: write a sibling temp file, get it on disk, then
 * rename over the target. Rename within a directory is atomic, so the target
 * only ever holds a complete document — either the old one or the new one.
 *
 * Kept free of Android so the headless suite can exercise it, including the
 * case that matters and is otherwise unreachable: an interrupted write leaving
 * the previous contents intact.
 */
object AtomicWrite {
    /** Suffix for the in-progress copy. Public so callers can skip these when listing a directory. */
    const val TEMP_SUFFIX = ".tmp"

    /**
     * Writes [text] to [file], atomically. Returns false and leaves any
     * existing file untouched if it could not be completed.
     */
    fun text(
        file: File,
        text: String,
    ): Boolean {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        val temp = File(file.absolutePath + TEMP_SUFFIX)
        return try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // Without this the rename can land before the bytes do, and a
                // power loss then leaves an atomically-renamed empty file -
                // which defeats the point of the temp file entirely.
                out.fd.sync()
            }
            if (temp.renameTo(file)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (_: Exception) {
            // Includes the interrupted case. The target still holds whatever
            // it held before, which is the whole guarantee.
            temp.delete()
            false
        }
    }
}
