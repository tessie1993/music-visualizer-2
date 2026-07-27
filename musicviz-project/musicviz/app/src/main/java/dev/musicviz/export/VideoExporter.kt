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
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Output quality tier - the short side (px) and target video bitrate. */
enum class ExportQuality(val shortSide: Int, val bitRate: Int) {
    HD720(720, 6_000_000),
    FHD1080(1080, 12_000_000),
    UHD4K(2160, 40_000_000),
}

/** Output aspect ratio as width:height. */
enum class ExportRatio(val label: String, val wRatio: Int, val hRatio: Int) {
    R16_9("16:9", 16, 9),
    R9_16("9:16", 9, 16),
    R1_1("1:1", 1, 1),
    R4_5("4:5", 4, 5),
    R4_3("4:3", 4, 3),
    R21_9("21:9", 21, 9),
}

/**
 * A concrete export target: encoder pixel dimensions (always even) plus the
 * chosen bitrate, derived from a quality tier and a ratio. The short side of
 * the frame equals the quality's [ExportQuality.shortSide].
 */
class ExportAspect(val width: Int, val height: Int, val bitRate: Int) {
    companion object {
        fun of(
            quality: ExportQuality,
            ratio: ExportRatio,
        ): ExportAspect {
            val short = quality.shortSide
            val landscape = ratio.wRatio >= ratio.hRatio
            var longSide = (short.toLong() * maxOf(ratio.wRatio, ratio.hRatio) / minOf(ratio.wRatio, ratio.hRatio)).toInt()
            var shortSide = short
            // Hardware AVC encoders top out at 4096 px per dimension on most
            // devices (e.g. 4K x 21:9 would ask for 5376 wide and fail to
            // configure). Clamp the long side and scale the short side to
            // preserve the aspect ratio.
            if (longSide > MAX_AVC_DIM) {
                shortSide = (short.toLong() * MAX_AVC_DIM / longSide).toInt()
                longSide = MAX_AVC_DIM
            }
            val w = if (landscape) longSide else shortSide
            val h = if (landscape) shortSide else longSide
            return ExportAspect(even(w), even(h), quality.bitRate)
        }

        private const val MAX_AVC_DIM = 4096

        private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1
    }
}

/**
 * Offline renderer: draws [Scene] frame-by-frame at exact timestamps into an
 * H.264 encoder using the precomputed [FeatureTimeline] (deterministic - no
 * dropped frames), then muxes the original audio track alongside.
 */
class VideoExporter(private val context: Context) {
    companion object {
        private const val FPS: Int = 60
        private const val TIMEOUT_US: Long = 10_000
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
        sceneParams: SceneParams,
        lfoConfigs: List<dev.musicviz.render.LfoConfig> = emptyList(),
        adsrConfigs: List<dev.musicviz.render.AdsrConfig> = emptyList(),
        requestedFps: Int = FPS,
        destination: Uri? = null,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Uri? =
        withContext(Dispatchers.Default) {
            // If the user picked a destination via the system file picker, write
            // straight into it; otherwise fall back to the app's gallery folder
            // (Movies/MusicViz) via MediaStore.
            if (destination != null) {
                return@withContext exportToDestination(
                    destination,
                    audioUri,
                    timeline,
                    sceneFactory,
                    aspect,
                    sceneParams,
                    lfoConfigs,
                    adsrConfigs,
                    requestedFps,
                    onProgress,
                    isCancelled,
                )
            }
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MusicViz")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
            val outUri =
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
            val pfd =
                resolver.openFileDescriptor(outUri, "w") ?: run {
                    // Remove the just-inserted IS_PENDING row: orphaned pending
                    // entries are invisible and undeletable from gallery apps.
                    runCatching { resolver.delete(outUri, null, null) }
                    return@withContext null
                }
            try {
                pfd.use {
                    encodeInto(
                        it, audioUri, timeline, sceneFactory, aspect,
                        sceneParams, lfoConfigs, adsrConfigs, requestedFps, onProgress, isCancelled,
                    )
                }
                if (isCancelled()) {
                    // A cancelled export is a truncated file with no audio;
                    // remove it instead of publishing it to the gallery.
                    runCatching { resolver.delete(outUri, null, null) }
                    null
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        resolver.update(outUri, done, null, null)
                    }
                    outUri
                }
            } catch (e: Exception) {
                runCatching { resolver.delete(outUri, null, null) }
                throw e
            }
        }

    private suspend fun exportToDestination(
        destination: Uri,
        audioUri: Uri,
        timeline: FeatureTimeline,
        sceneFactory: SceneFactory,
        aspect: ExportAspect,
        sceneParams: SceneParams,
        lfoConfigs: List<dev.musicviz.render.LfoConfig>,
        adsrConfigs: List<dev.musicviz.render.AdsrConfig>,
        requestedFps: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Uri? {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(destination, "w") ?: return null
        return try {
            pfd.use {
                encodeInto(
                    it, audioUri, timeline, sceneFactory, aspect,
                    sceneParams, lfoConfigs, adsrConfigs, requestedFps, onProgress, isCancelled,
                )
            }
            if (isCancelled()) {
                runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                null
            } else {
                destination
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    private fun encodeInto(
        pfd: ParcelFileDescriptor,
        audioUri: Uri,
        timeline: FeatureTimeline,
        sceneFactory: SceneFactory,
        aspect: ExportAspect,
        sceneParams: SceneParams,
        lfoConfigs: List<dev.musicviz.render.LfoConfig>,
        adsrConfigs: List<dev.musicviz.render.AdsrConfig>,
        requestedFps: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        fun makeFormat(
            fps: Int,
            bitRate: Int,
        ): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, aspect.width, aspect.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        // Every native resource is registered in [cleanup] the moment it is
        // created, and the ONE try/finally below covers setup as well as the
        // render loop: a throw or cancel during setup (the audio transcode is
        // the first ~10% and the most likely abort point) must not leak the
        // hardware codec, its input surface, the muxer, or the EGL context -
        // leaked codec instances break later exports and even live playback
        // until process death.
        var encoderRes: MediaCodec? = null
        var surfaceRes: android.view.Surface? = null
        var muxerRes: MediaMuxer? = null
        var aacRes: AudioTranscoder.Result? = null
        var eglRes: EncoderSurface? = null
        var sceneRes: Scene? = null
        var fxRes: FxCompositor? = null
        var flowRes: dev.musicviz.render.fluid.FlowField? = null
        var muxerStarted = false
        try {
            var encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoderRes = encoder
            var fps = requestedFps.coerceIn(24, 60)
            try {
                encoder.configure(makeFormat(fps, aspect.bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // High resolutions/60 fps can exceed a device's encoder limits
                // (notably 4K); retry once at 30 fps and 2/3 bitrate.
                runCatching { encoder.release() }
                encoderRes = null
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoderRes = encoder
                fps = 30
                encoder.configure(makeFormat(30, aspect.bitRate * 2 / 3), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder.createInputSurface()
            surfaceRes = inputSurface
            encoder.start()

            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRes = muxer
            // MP4 cannot carry MP3/Vorbis/FLAC tracks; transcode audio to AAC first.
            val aac = AudioTranscoder(context).transcode(audioUri, 0L, isCancelled) { onProgress(it * 0.1f) }
            aacRes = aac
            val egl = EncoderSurface(inputSurface)
            eglRes = egl
            egl.makeCurrent()

            val scene = sceneFactory.create()
            sceneRes = scene
            scene.init()
            scene.resize(aspect.width, aspect.height)
            GLES30.glViewport(0, 0, aspect.width, aspect.height)
            val isParticle = scene is dev.musicviz.render.scene.ParticleSceneBase
            val isShaderScene = scene is dev.musicviz.render.scene.ShaderScene

            // Build an offscreen FBO + composite program so the export applies the
            // SAME screen-space FX chain (geometry, chroma, vignette, scanlines,
            // grain, glitch, fisheye, strobe, bloom, posterize) the live renderer
            // does. Without this, exports - especially of particle scenes - would
            // omit every FX/shape customization, which are composite-only.
            val fx = FxCompositor(context, aspect.width, aspect.height)
            fxRes = fx
            // FlowField export parity (F7): run the shared field in the export GL
            // context so fluidWarp bends exported frames exactly like the live
            // view. The FLUID scene reuses its own velocity field instead.
            val exportFluidScene = scene as? dev.musicviz.render.fluid.FluidScene
            val flowField =
                if (sceneParams.flowEnabled && exportFluidScene == null) {
                    dev.musicviz.render.fluid.FlowField(context).also {
                        it.create()
                        it.resize(aspect.width, aspect.height)
                    }
                } else {
                    null
                }
            flowRes = flowField
            // Reproduce the live path's per-frame LFO modulation so automations
            // the user set up appear in the render, not just on screen.
            val lfoEngine = dev.musicviz.render.LfoEngine()
            if (lfoConfigs.isNotEmpty()) lfoEngine.configs = lfoConfigs
            val adsrEngine =
                dev.musicviz.render.AdsrEngine().also {
                    if (adsrConfigs.isNotEmpty()) it.configs = adsrConfigs
                }

            var videoTrack = -1
            var audioTrack = -1
            val info = MediaCodec.BufferInfo()
            // Video length is derived from the ACTUAL transcoded audio duration so
            // the export always matches the music exactly. The analysis timeline is
            // only used for per-frame features (featuresAt clamps at its end).
            val exportDurationUs = if (aac.durationUs > 0) aac.durationUs else timeline.durationMs * 1000
            val totalFrames = (exportDurationUs * fps / 1_000_000L).toInt().coerceAtLeast(1)
            val frameDurationNs = 1_000_000_000L / fps

            for (frame in 0 until totalFrames) {
                if (isCancelled()) break
                val timeMs = frame * 1000L / fps
                val features = timeline.featuresAt(timeMs)
                val lfoValues = lfoEngine.tick(1f / fps, features.bpm)
                var p = dev.musicviz.render.LfoEngine.apply(sceneParams, lfoEngine.configs, lfoValues)
                scene.setParams(p)
                scene.update(dev.musicviz.render.scene.applyBandGains(features, p), 1f / fps)
                if (p.flowEnabled && flowField != null && flowField.available) {
                    // Steps into the FlowField's own FBOs, before the scene
                    // target is bound - mirrors the live frame order.
                    flowField.step(dev.musicviz.render.scene.applyBandGains(features, p), 1f / fps, p)
                }
                // Draw the scene into the FX FBO, then composite (with the full
                // FX chain) onto the encoder surface, matching the live path.
                fx.bindSceneTarget()
                if (p.trails && isParticle && frame > 0) {
                    fx.fadeSceneTarget(p.trailLength)
                } else {
                    GLES30.glClearColor(0f, 0f, 0f, 1f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                }
                scene.draw(timeMs / 1000f)
                val flowTex =
                    when {
                        !p.flowEnabled -> 0
                        exportFluidScene != null && exportFluidScene.simAvailable -> exportFluidScene.velocityTexture
                        flowField != null && flowField.available -> flowField.velocityTex
                        else -> 0
                    }
                fx.composite(
                    timeMs / 1000f, features, isParticle, isShaderScene, p,
                    flowTex = flowTex,
                    flowStrength = if (flowTex != 0) p.flowStrength else 0f,
                )
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
            // Encoders return TRY_AGAIN repeatedly while flushing; keep draining
            // until EOS (bounded so a stuck codec cannot hang the export).
            var flushAttempts = 0
            var eosReached = false
            drain@ while (flushAttempts < 600) {
                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioTrack = muxer.addTrack(aac.format)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        writeSample(muxer, videoTrack, encoder.getOutputBuffer(outIndex)!!, info, muxerStarted)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (eos) {
                            eosReached = true
                            break@drain
                        }
                    }
                    else -> flushAttempts++
                }
            }
            if (!eosReached && !isCancelled()) {
                // Don't report a silently truncated export as a clean success.
                android.util.Log.w(
                    "VideoExporter",
                    "Encoder never signalled EOS within the flush window; final frames may be missing",
                )
            }
            // If the encoder never emitted its output format, nothing was
            // muxed: without this check the export would "succeed" with an
            // empty/broken file.
            check(muxerStarted || isCancelled()) { "Video encoder produced no output (encoder/format unsupported?)" }
            if (muxerStarted && !isCancelled() && audioTrack >= 0) {
                writeTranscodedAudio(muxer, audioTrack, aac) { onProgress(0.95f + it * 0.05f) }
            }
        } finally {
            // Cleanup failures (e.g. stopping a muxer after a mid-export error)
            // must never mask the original exception. GL-owned objects go
            // first, while the EGL context is still current.
            runCatching { sceneRes?.release() }
            runCatching { flowRes?.release() }
            runCatching { fxRes?.release() }
            if (muxerStarted) runCatching { muxerRes?.stop() }
            runCatching { muxerRes?.release() }
            runCatching { encoderRes?.stop() }
            runCatching { encoderRes?.release() }
            runCatching { surfaceRes?.release() }
            runCatching { eglRes?.release() }
            aacRes?.release()
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

    /** Streams the pre-transcoded AAC samples from the temp file into the muxer. */
    private fun writeTranscodedAudio(
        muxer: MediaMuxer,
        track: Int,
        aac: AudioTranscoder.Result,
        onProgress: (Float) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        val total = aac.sampleInfos.size.coerceAtLeast(1)
        java.io.RandomAccessFile(aac.file, "r").use { raf ->
            val channel = raf.channel
            var scratch = ByteBuffer.allocate(64 * 1024)
            aac.sampleInfos.forEachIndexed { index, sample ->
                if (scratch.capacity() < sample.size) scratch = ByteBuffer.allocate(sample.size)
                scratch.clear()
                scratch.limit(sample.size)
                var read = 0
                while (read < sample.size) {
                    val n = channel.read(scratch, sample.offset + read)
                    if (n <= 0) break
                    read += n
                }
                scratch.flip()
                // writeSampleData reads [info.offset, info.offset + info.size) of the buffer.
                info.set(0, read, sample.presentationTimeUs, sample.flags)
                muxer.writeSampleData(track, scratch, info)
                if (index % 64 == 0) onProgress(index / total.toFloat())
            }
        }
    }
}
