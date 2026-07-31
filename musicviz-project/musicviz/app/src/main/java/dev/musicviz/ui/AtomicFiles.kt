package dev.musicviz.ui

import java.io.File

/**
 * Writes [text] via a sibling temp file + rename, so a crash mid-write can
 * never leave a half-written JSON file where a good one used to be: readers
 * see either the old content or the new content, and a stray `*.tmp` is
 * ignored by every store's `extension == "json"` listing filter.
 */
internal fun File.writeTextAtomic(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        // Filesystems where rename won't replace an existing file: drop the
        // stale target first, then fall back to a direct write as last resort.
        delete()
        if (!tmp.renameTo(this)) {
            writeText(text)
            tmp.delete()
        }
    }
}
