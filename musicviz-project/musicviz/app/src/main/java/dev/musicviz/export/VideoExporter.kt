package dev.musicviz.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES30
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.render.scene.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Export target aspect: landscape 16:9 or vertical 9:16, both 1080p. */
enum class ExportAspect(val width: Int, val height: Int) {
    LANDSCAPE(1920, 1080),
    PORTRAIT(1080, 1920),
}

/**
 * Offline renderer: draws [Scene] frame-by-frame at exact timestamps into an
 * H.264 encoder using the precomputed [FeatureTimeline] (deterministic - no
 * dropped frames), then muxes the original audio track alongside.
 */
class VideoExporter(private val context: Context) {
    companion object {
        private const val FPS: Int = 60
        private const val BIT_RATE: Int = 12_000_000
        private const val TIMEOUT_US: Long = 10_000
        private const val DRAIN_TIMEOUT_NS: Long = 5_000_000_000L
    }

    interface SceneFactory {
        fun create(): Scene
    }

    suspend fun export(
        audioUri: Uri,
        timeline: FeatureTimeline,
        sceneFactory: SceneFactory,
        aspect: ExportAspect,
        fileName: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Uri? =
        withContext(Dispatchers.Default) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
            val outUri =
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
            val pfd = resolver.openFileDescriptor(outUri, "w") ?: return@withContext null
            try {
                pfd.use { encodeInto(it, audioUri, timeline, sceneFactory, aspect, onProgress, isCancelled) }
                if (isCancelled()) {
                    // Don't leave a truncated video in the library.
                    resolver.delete(outUri, null, null)
                    null
                } else {
                    outUri
                }
            } catch (e: Exception) {
                resolver.delete(outUri, null, null)
                throw e
            }
        }

    private fun encodeInto(
        pfd: ParcelFileDescriptor,
        audioUri: Uri,
        timeline: FeatureTimeline,
        sceneFactory: SceneFactory,
        aspect: ExportAspect,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, aspect.width, aspect.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // MP4 cannot carry MP3/Vorbis/FLAC tracks; transcode audio to AAC first.
        val aac = AudioTranscoder(context).transcode(audioUri, timeline.durationMs) { onProgress(it * 0.1f) }
        val egl = EncoderSurface(inputSurface)
        egl.makeCurrent()

        val scene = sceneFactory.create()
        scene.init()
        scene.resize(aspect.width, aspect.height)
        GLES30.glViewport(0, 0, aspect.width, aspect.height)

        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val totalFrames = (timeline.durationMs * FPS / 1000).toInt().coerceAtLeast(1)
        val frameDurationNs = 1_000_000_000L / FPS

        try {
            for (frame in 0 until totalFrames) {
                if (isCancelled()) break
                val timeMs = frame * 1000L / FPS
                val features = timeline.featuresAt(timeMs)
                scene.update(features, 1f / FPS)
                GLES30.glClearColor(0.02f, 0.01f, 0.05f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                scene.draw(timeMs / 1000f)
                egl.setPresentationTimeNs(frame * frameDurationNs)
                egl.swapBuffers()

                // Drain encoder without blocking the render loop for long.
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioTrack = muxer.addTrack(aac.format)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        writeSample(muxer, videoTrack, encoder.getOutputBuffer(outIndex)!!, info, muxerStarted)
                        encoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        break
                    }
                }
                onProgress(0.1f + frame / totalFrames.toFloat() * 0.85f)
            }
            encoder.signalEndOfInputStream()
            // Drain until EOS: the encoder may take longer than one timeout to
            // flush its tail, so TRY_AGAIN_LATER must not end the loop early.
            val drainDeadline = System.nanoTime() + DRAIN_TIMEOUT_NS
            while (System.nanoTime() < drainDeadline) {
                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    audioTrack = muxer.addTrack(aac.format)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    writeSample(muxer, videoTrack, encoder.getOutputBuffer(outIndex)!!, info, muxerStarted)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (eos) break
                }
            }
            if (muxerStarted && !isCancelled() && audioTrack >= 0) {
                writeTranscodedAudio(muxer, audioTrack, aac) { onProgress(0.95f + it * 0.05f) }
            }
        } finally {
            scene.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
            encoder.stop()
            encoder.release()
            egl.release()
        }
    }

    private fun writeSample(
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        started: Boolean,
    ) {
        if (!started || track < 0 || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        muxer.writeSampleData(track, buffer, info)
    }

    /** Writes the pre-transcoded AAC samples into the muxer. */
    private fun writeTranscodedAudio(
        muxer: MediaMuxer,
        track: Int,
        aac: AudioTranscoder.Result,
        onProgress: (Float) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.wrap(aac.data)
        val total = aac.sampleInfos.size.coerceAtLeast(1)
        aac.sampleInfos.forEachIndexed { index, sample ->
            info.set(sample.offset, sample.size, sample.presentationTimeUs, sample.flags)
            muxer.writeSampleData(track, buffer, info)
            if (index % 64 == 0) onProgress(index / total.toFloat())
        }
    }
}
