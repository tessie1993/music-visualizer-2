package dev.musicviz.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Renders a [ClipEdit] to a new file and puts it where the gallery can see it.
 *
 * Built on Media3's Transformer rather than on this app's own [VideoExporter]
 * because the two are answering different questions. The visualizer exporter
 * renders frames that do not exist yet, from a scene and an analysis timeline;
 * this one re-encodes frames that already exist. Transformer also knows how to
 * NOT re-encode - an edit that only trims can be done by rewriting the
 * container, which is both faster and lossless, and hand-rolling that on top
 * of MediaCodec would be a second muxer to maintain.
 *
 * Output lands in `Movies/MusicViz`, the same shelf the visualizer exports use,
 * so the Studio's clip list and the gallery agree about what exists.
 */
@UnstableApi
class StudioExporter(
    private val context: Context,
) {
    /** How an export ended. */
    sealed interface Result {
        data class Saved(
            val uri: Uri,
            val durationMs: Long,
        ) : Result

        data class Failed(
            val message: String,
        ) : Result

        data object Cancelled : Result
    }

    @Volatile
    private var transformer: Transformer? = null

    @Volatile
    private var cancelled = false

    /**
     * Runs the export, reporting 0..1 through [onProgress].
     *
     * Transformer wants to be driven from a Looper thread and calls back on
     * the one it was built on, so the whole thing is bridged onto the main
     * dispatcher and turned back into a suspend function - a coroutine is what
     * every caller in this app already speaks.
     */
    suspend fun export(
        source: Uri,
        sourceDurationMs: Long,
        edit: ClipEdit,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): Result {
        cancelled = false
        val scratch = File(context.cacheDir, "studio-${System.currentTimeMillis()}.mp4")
        // The scratch file is deleted on the way out however this ends,
        // including the way the user actually cancels: cancelling the export
        // cancels this coroutine's job, so the withContext calls below throw
        // CancellationException and unwind past every ordinary delete. A
        // cancelled 1080p export left ~150 MB in cacheDir, once per attempt.
        try {
            val outcome =
                withContext(Dispatchers.Main) {
                    runTransformer(source, edit, scratch, sourceDurationMs, onProgress)
                }
            if (outcome != null) return outcome
            if (cancelled) return Result.Cancelled
            val published =
                withContext(Dispatchers.IO) { publish(scratch, displayName) }
            return published
                ?.let { Result.Saved(it, edit.outputMs(sourceDurationMs)) }
                ?: Result.Failed("The finished file could not be saved to Movies/MusicViz.")
        } finally {
            scratch.delete()
        }
    }

    /** Returns null on success, or the failure/cancellation that ended it. */
    private suspend fun runTransformer(
        source: Uri,
        edit: ClipEdit,
        output: File,
        sourceDurationMs: Long,
        onProgress: (Float) -> Unit,
    ): Result? =
        suspendCancellableCoroutine { continuation ->
            val item =
                MediaItem
                    .Builder()
                    .setUri(source)
                    .setClippingConfiguration(edit.clipping())
                    .build()
            val edited =
                EditedMediaItem
                    .Builder(item)
                    .setRemoveAudio(edit.mute)
                    .setEffects(Effects(emptyList(), edit.videoEffects()))
                    .apply { edit.speedProvider()?.let { setSpeed(it) } }
                    .build()
            val built =
                Transformer
                    .Builder(context)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                transformer = null
                                continuation.resumeOnce(null)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                transformer = null
                                continuation.resumeOnce(
                                    if (cancelled) {
                                        Result.Cancelled
                                    } else {
                                        Result.Failed(describe(exportException))
                                    },
                                )
                            }
                        },
                    ).build()
            transformer = built
            continuation.invokeOnCancellation {
                cancelled = true
                runCatching { built.cancel() }
            }
            runCatching { built.start(edited, output.absolutePath) }
                .onFailure {
                    transformer = null
                    continuation.resumeOnce(Result.Failed(it.message ?: "The export could not be started."))
                    return@suspendCancellableCoroutine
                }
            // Transformer's own progress is only available once it has probed
            // the input, and is UNAVAILABLE for the container-rewrite fast
            // path. Falling back to "we are running" beats a bar frozen at 0%.
            val holder = ProgressHolder()
            val scope = kotlinx.coroutines.CoroutineScope(continuation.context)
            scope.launch {
                while (continuation.isActive) {
                    val state = runCatching { built.getProgress(holder) }.getOrNull()
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(PROGRESS_POLL_MS)
                }
            }
            if (sourceDurationMs <= 0L) onProgress(0f)
        }

    /** Stops a running export; the suspended call returns [Result.Cancelled]. */
    fun cancel() {
        cancelled = true
        runCatching { transformer?.cancel() }
    }

    /**
     * Moves the finished file into `Movies/MusicViz`.
     *
     * IS_PENDING while the bytes are being written, so the gallery never shows
     * a half-copied file - the same protocol [VideoExporter] uses, and the
     * reason both write through MediaStore rather than to a path.
     */
    private fun publish(
        file: File,
        displayName: String,
    ): Uri? =
        runCatching {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MusicViz")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
            val uri =
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }.getOrNull()

    /**
     * A sentence rather than an enum name.
     *
     * The two failures a person actually hits are "this device's encoder will
     * not do that size" and "the source file is something Media3 cannot read",
     * and both are actionable if they are said out loud.
     */
    private fun describe(exception: ExportException): String =
        when (exception.errorCode) {
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ->
                "This device's video encoder would not accept that output — try a smaller size or a " +
                    "different aspect ratio."
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            -> "That clip could not be read — the file may have moved, or be in a format this device cannot decode."
            ExportException.ERROR_CODE_IO_NO_PERMISSION -> "MusicViz does not have permission to read that file."
            else -> exception.message ?: "The export failed."
        }

    private companion object {
        const val PROGRESS_POLL_MS = 250L

        /**
         * Transformer can report completion and an error for the same run when
         * a cancel races the muxer's last write; the continuation must only be
         * resumed once.
         */
        fun CancellableContinuation<Result?>.resumeOnce(value: Result?) {
            if (isActive) resume(value)
        }
    }
}
