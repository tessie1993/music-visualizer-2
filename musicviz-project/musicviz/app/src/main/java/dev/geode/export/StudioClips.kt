package dev.geode.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** One video the Studio can open. */
data class StudioClip(
    val uri: String,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** "1080×1920 · 0:24 · 18 MB", with the unknown parts left out. */
    fun summary(): String =
        buildList {
            if (width > 0 && height > 0) add("$width×$height")
            if (durationMs > 0) add("%d:%02d".format(durationMs / 60_000, (durationMs / 1000) % 60))
            if (sizeBytes > 0) add("%.0f MB".format(sizeBytes / (1024f * 1024f)))
        }.joinToString(" · ")
}

/**
 * Finds the videos this app has rendered.
 *
 * Scoped to `Movies/Geode`, the shelf both exporters write to. Two reasons
 * it is not "every video on the device": these are the ones the Studio exists
 * to finish, and listing the user's whole camera roll would need the media
 * permission, which nothing else in this app asks for. Anything else can still
 * be opened one file at a time through the system picker, which needs no
 * permission at all.
 */
object StudioClips {
    /** Blocking; call on Dispatchers.IO. */
    fun list(context: Context): List<StudioClip> {
        val out = mutableListOf<StudioClip>()
        val projection =
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
            )
        // RELATIVE_PATH only exists from Q; below it the folder is only
        // visible through the legacy DATA column.
        val selection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else {
                "${MediaStore.Video.Media.DATA} LIKE ?"
            }
        val args =
            arrayOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Movies/Geode%" else "%/Movies/Geode/%",
            )
        runCatching {
            context.contentResolver
                .query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    while (c.moveToNext()) {
                        out +=
                            StudioClip(
                                uri =
                                    ContentUris
                                        .withAppendedId(
                                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                            c.getLong(id),
                                        ).toString(),
                                name = c.getString(name) ?: "Clip",
                                durationMs = c.getLong(duration),
                                sizeBytes = c.getLong(size),
                                width = c.getInt(width),
                                height = c.getInt(height),
                            )
                    }
                }
        }
        return out
    }

    /**
     * Describes a video the user picked through the system picker, which
     * MediaStore will not answer questions about.
     */
    fun describe(
        context: Context,
        uri: android.net.Uri,
    ): StudioClip {
        var durationMs = 0L
        var width = 0
        var height = 0
        runCatching {
            // try/finally rather than use(): MediaMetadataRetriever only became
            // AutoCloseable in API 29 and this app runs from 26.
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs =
                    retriever
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                width =
                    retriever
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                height =
                    retriever
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
        }
        val name =
            runCatching {
                androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(context, uri)
                    ?.name
            }.getOrNull() ?: uri.lastPathSegment ?: "Clip"
        return StudioClip(uri.toString(), name, durationMs, 0L, width, height)
    }

    /**
     * Deletes a rendered clip.
     *
     * Renders accumulate at up to 300 MB a minute at 4K, and the app offered no
     * way to remove one — a weekend of experimenting left gigabytes of
     * `geode_<epoch>.mp4` that had to be hunted down in a gallery app.
     *
     * Returns false when the delete did not happen, including the Android 10+
     * case where the file belongs to another app and the system demands a user
     * confirmation this call cannot give. Reporting that honestly is better
     * than a row that vanishes from the list and reappears on the next refresh.
     */
    fun delete(
        context: Context,
        uri: String,
    ): Boolean =
        runCatching {
            context.contentResolver.delete(Uri.parse(uri), null, null) > 0
        }.getOrDefault(false)

    /**
     * Renames a rendered clip, keeping its extension.
     *
     * Outputs are named `geode_<epoch>.mp4`, which tells the user nothing about
     * which of nine renders is the one they wanted. [name] is the display name
     * without an extension; the original's is preserved so the file stays
     * playable.
     *
     * ## Why this reads the name back
     *
     * A bare `DISPLAY_NAME` update reports one row changed and then leaves the
     * file alone — MediaStore applies the rename only while the item is marked
     * pending. Trusting the row count meant the Studio said "renamed" over a
     * file whose name had not moved, which is worse than failing: the user
     * believes the clip they are about to send is the one they named. So the
     * result here is the answer to "is it called that now?", asked of
     * MediaStore, rather than the resolver's opinion of its own write.
     *
     * The pending wrap is the fallback rather than the first move, because a
     * plain update is enough on some versions and an item left pending is
     * invisible to every other app — the narrower the window, the better.
     */
    @Volatile
    internal var lastRenameDiagnostic: String? = null

    @Suppress("ReturnCount")
    fun rename(
        context: Context,
        uri: String,
        name: String,
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        // Path separators would let a rename move the file out of the
        // collection MediaStore expects it in.
        if (trimmed.any { it == '/' || it == '\\' }) return false
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val before = currentName(context, parsed)
        val extension = before?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        val display = if (extension == null) trimmed else "$trimmed.$extension"
        if (display == before) return true
        val log = StringBuilder("rename '$before' -> '$display'")
        val renamed =
            runCatching {
                val resolver = context.contentResolver
                val direct = runCatching { resolver.update(parsed, displayName(display), null, null) }
                log.append("; direct=").append(direct.exceptionOrNull()?.toString() ?: direct.getOrNull())
                // A short probe only: this wait exists to notice the versions
                // where the plain update is enough, not to give a no-op its
                // full hearing before the fallback that actually works runs.
                var after = settledName(context, parsed, display, DIRECT_SETTLE_ATTEMPTS)
                log.append(" now='").append(after).append('\'')
                if (after != display) {
                    renameWhilePending(context, parsed, display, log)
                    after = settledName(context, parsed, display, SETTLE_ATTEMPTS)
                    log.append("; final='").append(after).append('\'')
                }
                var ok = after == display
                if (!ok) {
                    // MediaStore can answer a rename by RE-IDENTIFYING the row:
                    // the file moves, the old _ID dies, and no read through the
                    // original uri ever confirms anything again - observed on
                    // the API 30 emulator as final='null' outliving every
                    // settle window while pend/upd/pub all reported success.
                    // The collection is the surviving truth: the rename
                    // happened exactly when a row carries the new name and
                    // none still carries the old. Callers already refresh
                    // their listing on success, so a new _ID is picked up.
                    val newRow = rowIdByName(context, display)
                    val oldRow = before?.let { rowIdByName(context, it) }
                    log
                        .append("; byName new=")
                        .append(newRow ?: "none")
                        .append(" old=")
                        .append(oldRow ?: "none")
                    ok = newRow != null && oldRow == null
                }
                ok
            }.getOrElse {
                log.append("; threw=").append(it)
                false
            }
        lastRenameDiagnostic = log.toString()
        return renamed
    }

    private fun displayName(display: String): ContentValues = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, display) }

    /**
     * The rename MediaStore actually honours: mark the item pending, rename it,
     * publish it again.
     *
     * The un-pend runs in a `finally` because an item left pending disappears
     * from every gallery on the device — failing to rename is recoverable,
     * losing the clip is not.
     */
    private fun renameWhilePending(
        context: Context,
        uri: Uri,
        display: String,
        log: StringBuilder,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val pending = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 1) }
        val published = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        val p1 = runCatching { resolver.update(uri, pending, null, null) }
        log.append("; pend=").append(p1.exceptionOrNull()?.toString() ?: p1.getOrNull())
        try {
            val p2 = runCatching { resolver.update(uri, displayName(display), null, null) }
            log.append(" upd=").append(p2.exceptionOrNull()?.toString() ?: p2.getOrNull())
        } finally {
            val p3 = runCatching { resolver.update(uri, published, null, null) }
            log.append(" pub=").append(p3.exceptionOrNull()?.toString() ?: p3.getOrNull())
        }
    }

    /**
     * The _ID of the video row named exactly [display], or null.
     *
     * The verdict of last resort for [rename]: a row that was re-identified
     * mid-rename is unreachable through the uri the caller holds, but its
     * file - old name or new - is still one name-equality query away.
     */
    private fun rowIdByName(
        context: Context,
        display: String,
    ): Long? =
        runCatching {
            context.contentResolver
                .query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
                    arrayOf(display),
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()

    private fun currentName(
        context: Context,
        uri: Uri,
    ): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    /**
     * Reads the row's name until it matches [display] or the window closes.
     *
     * MediaStore's rename is eventually consistent: update() can report one
     * row changed while a read a moment later still serves the old name, and
     * during the pending flip the row is hidden and reads as null. Both were
     * observed on the API 30 emulator - the same rename passing one run and
     * failing the next, decided by who won the race - so the verdict waits
     * for the store instead of trusting the first read. Callers run on
     * Dispatchers.IO, where a short poll is cheap, and the window is only
     * paid in full on the failure path.
     *
     * A plain read is not enough on its own: the row can STAY hidden for
     * hundreds of milliseconds after IS_PENDING is set back to 0 (CI's API 30
     * emulator burned a whole 400 ms window reading null after a publish that
     * reported success - `pend=1 upd=1 pub=1; final='null'`), so a poll that
     * only asks the plain query is blind exactly while the answer is being
     * written. Each attempt therefore also asks the pending-inclusive query,
     * which sees the row through the flip; a rename that landed is recognized
     * whichever side of the visibility boundary the store is on.
     */
    private fun settledName(
        context: Context,
        uri: Uri,
        display: String,
        attempts: Int,
    ): String? {
        var name: String? = null
        repeat(attempts) {
            name = currentName(context, uri) ?: pendingVisibleName(context, uri)
            if (name == display) return name
            android.os.SystemClock.sleep(SETTLE_STEP_MS)
        }
        return name
    }

    /**
     * The row's name as the pending-inclusive query reports it, or null.
     *
     * Only meaningful on Q+ (below Q nothing here ever marks a row pending);
     * API 29 spells the request [MediaStore.setIncludePending], 30+ a query-
     * args bundle. Used by [settledName] to keep sight of a row that a plain
     * query hides while its pending flip settles.
     */
    private fun pendingVisibleName(
        context: Context,
        uri: Uri,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        return runCatching {
            val cursor =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val args =
                        android.os.Bundle().apply {
                            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        }
                    context.contentResolver.query(uri, projection, args, null)
                } else {
                    @Suppress("DEPRECATION")
                    context.contentResolver.query(MediaStore.setIncludePending(uri), projection, null, null, null)
                }
            cursor?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** The final verdict's window: a cold CI emulator was seen holding a
     *  published row hidden past 400 ms, so this waits out 1.2 s worst-case.
     *  Success returns early; the full window is only paid to say "failed". */
    private const val SETTLE_ATTEMPTS = 24

    /** The direct-update probe's window: just enough to notice the versions
     *  where the plain update lands, before the pending fallback runs. */
    private const val DIRECT_SETTLE_ATTEMPTS = 6
    private const val SETTLE_STEP_MS = 50L
}
