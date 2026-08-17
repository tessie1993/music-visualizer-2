package dev.geode.playback

import androidx.media3.common.PlaybackException

/**
 * What to tell the user when a track will not play, and what to do next.
 *
 * The app had no answer to either question: nothing in main source implemented
 * `onPlayerError`, so a deleted file, an ejected card or a revoked SAF grant
 * stopped playback with no message, no skip and no explanation. Stale uris are
 * routine — SAF grants expire, cards are removed, files are reorganised — so
 * this is an ordinary Tuesday for a music player, not an exotic failure.
 *
 * Pure and Android-free apart from the error code, so the decision is unit
 * tested rather than reasoned about.
 */
object PlaybackErrors {
    /** What the player should do about a failure. */
    sealed interface Action {
        /** Move to the next item; the queue may still be good. */
        data object SkipToNext : Action

        /** Nothing follows this item, so there is nowhere to skip to. */
        data object StopEndOfQueue : Action

        /** Too many in a row failed — the source itself is gone. */
        data object StopSourceUnavailable : Action
    }

    /**
     * Stops a bad *source* from burning the whole queue.
     *
     * One missing file is a missing file; four in a row means the card is out,
     * the folder moved, or the grant is gone, and skipping onward would race
     * through a thousand tracks flashing a notice per track and land the user
     * at the end of their queue with nothing played. At that point stopping and
     * saying so is the useful behaviour.
     */
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    /**
     * @param consecutiveFailures how many tracks in a row have failed,
     *   including this one — so the first failure is 1.
     */
    fun decide(
        consecutiveFailures: Int,
        hasNext: Boolean,
    ): Action =
        when {
            // Checked before the end-of-queue case: "your storage is gone" is
            // the more useful thing to say, and it stays true whether or not
            // the dead track happened to be the last one.
            consecutiveFailures >= MAX_CONSECUTIVE_FAILURES -> Action.StopSourceUnavailable
            !hasNext -> Action.StopEndOfQueue
            else -> Action.SkipToNext
        }

    /**
     * A sentence for the user, naming the track when it is known.
     *
     * Written as consequences rather than causes: "this file is missing" tells
     * someone what to do, `ERROR_CODE_IO_FILE_NOT_FOUND` does not. The raw code
     * is never shown — the export dialog's habit of publishing
     * "ClassName: message" straight to the user is the thing this avoids.
     */
    fun describe(
        errorCode: Int,
        trackTitle: String?,
        action: Action,
    ): String {
        val subject = trackTitle?.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "This track"
        val cause =
            when (errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "$subject is missing. It may have been moved, renamed or deleted."

                PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    "Geode can no longer read $subject. Re-add the folder to restore access."

                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                ->
                    "$subject could not be decoded. This device may not support its format."

                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                ->
                    "$subject could not be read. The file may be damaged or the storage unavailable."

                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                ->
                    "$subject is not a file Geode can open — its contents do not match its type."

                else -> "$subject could not be played."
            }
        val consequence =
            when (action) {
                Action.SkipToNext -> " Skipping to the next track."
                Action.StopEndOfQueue -> " Nothing else is queued, so playback stopped."
                Action.StopSourceUnavailable ->
                    " Several tracks in a row are unavailable, so playback stopped — " +
                        "the storage or the folder permission is probably gone."
            }
        return cause + consequence
    }
}
