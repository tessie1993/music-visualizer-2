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
enum class ExportQuality(
    val shortSide: Int,
    val bitRate: Int,
) {
    HD720(720, 6_000_000),
    FHD1080(1080, 12_000_000),
    UHD4K(2160, 40_000_000),
}

/** Output aspect ratio as width:height. */
enum class ExportRatio(
    val label: String,
    val wRatio: Int,
    val hRatio: Int,
) {
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
class ExportAspect(
    val width: Int,
    val height: Int,
    val bitRate: Int,
) {
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
class VideoExporter(
    private val context: Context,
) {
    companion object {
        private const val FPS: Int = 60
        private const val TIMEOUT_US: Long = 10_000

        /** Ripple overlay grid short side - matches the live renderer's. */
        private const val RIPPLE_OVERLAY_RES = 256
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
            val pfd = resolver.openFileDescriptor(outUri, "w") ?: return@withContext null
            try {
                pfd.use {
                    encodeInto(
                        it,
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
                    it,
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
        // Every resource below must be released even when SETUP throws - a
        // cancelled audio transcode, a source with no audio track, or a failed
        // shader/EGL init used to leak the started encoder, its input surface
        // and the muxer (repeated attempts exhaust hardware codec instances).
        var encoderRef: MediaCodec? = null
        var inputSurfaceRef: android.view.Surface? = null
        var muxerRef: MediaMuxer? = null
        var aacRef: AudioTranscoder.Result? = null
        var eglRef: EncoderSurface? = null
        var sceneRef: Scene? = null
        var fxRef: FxCompositor? = null
        var flowFieldRef: dev.musicviz.render.fluid.FlowField? = null
        var rippleRef: dev.musicviz.render.fluid.RippleSim? = null
        var muxerStarted = false
        try {
            var encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoderRef = it }
            var fps = requestedFps.coerceIn(24, 60)
            try {
                encoder.configure(makeFormat(fps, aspect.bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // High resolutions/60 fps can exceed a device's encoder limits
                // (notably 4K); retry once at 30 fps and 2/3 bitrate.
                runCatching { encoder.release() }
                encoderRef = null
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoderRef = it }
                fps = 30
                encoder.configure(makeFormat(30, aspect.bitRate * 2 / 3), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder.createInputSurface().also { inputSurfaceRef = it }
            encoder.start()

            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerRef = it }
            // MP4 cannot carry MP3/Vorbis/FLAC tracks; transcode audio to AAC first.
            val aac =
                AudioTranscoder(context)
                    .transcode(audioUri, 0L, isCancelled) { onProgress(it * 0.1f) }
                    .also { aacRef = it }
            val egl = EncoderSurface(inputSurface).also { eglRef = it }
            egl.makeCurrent()

            val scene = sceneFactory.create().also { sceneRef = it }
            scene.init()
            scene.resize(aspect.width, aspect.height)
            GLES30.glViewport(0, 0, aspect.width, aspect.height)
            val isParticle = scene is dev.musicviz.render.scene.ParticleSceneBase
            val isShaderScene = scene is dev.musicviz.render.scene.ShaderScene
            // Curl Flow's look is DEFINED by canvas persistence (live renderer
            // forces it regardless of the trails toggle); a hard-cleared export
            // reads as strobing dots instead of streams.
            val isCurlFlow = scene is dev.musicviz.render.fluid.CurlFlowScene

            // Build an offscreen FBO + composite program so the export applies the
            // SAME screen-space FX chain (geometry, chroma, vignette, scanlines,
            // grain, glitch, fisheye, strobe, bloom, posterize) the live renderer
            // does. Without this, exports - especially of particle scenes - would
            // omit every FX/shape customization, which are composite-only.
            val fx = FxCompositor(context, aspect.width, aspect.height).also { fxRef = it }
            // FlowField export parity (F7): run the shared field in the export GL
            // context so fluidWarp bends exported frames exactly like the live
            // view. The FLUID scene reuses its own velocity field instead.
            val exportFluidScene = scene as? dev.musicviz.render.fluid.FluidScene
            val flowField =
                if (sceneParams.flowEnabled && exportFluidScene == null) {
                    dev.musicviz.render.fluid.FlowField(context).also {
                        flowFieldRef = it
                        it.create()
                        it.resize(aspect.width, aspect.height)
                    }
                } else {
                    null
                }
            // Ripple overlay export parity (F2): run a fresh RippleSim in the
            // export GL context so the refraction + glint land in exported
            // frames exactly like the live view. When the export scene IS
            // water, its own sim already refracts - the overlay stays off
            // (matches the live renderer's exclusivity guard).
            val exportWaterScene = scene as? dev.musicviz.render.fluid.WaterScene
            val rippleOverlay =
                if (sceneParams.rippleOverlayEnabled && exportWaterScene == null) {
                    dev.musicviz.render.fluid.RippleSim(context).also {
                        rippleRef = it
                        it.create()
                        it.applyResolution(RIPPLE_OVERLAY_RES)
                        it.resize(aspect.width, aspect.height)
                    }
                } else {
                    null
                }
            val rippleDrops = dev.musicviz.render.fluid.RippleOverlayDrops()
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
            // Section boundaries once (O(n)); per-frame features then carry the
            // progress/section context, so the fluid spawn/catch choreography
            // journeys through the exported video exactly like live playback.
            val sections = timeline.detectSections()

            for (frame in 0 until totalFrames) {
                if (isCancelled()) break
                val timeMs = frame * 1000L / fps
                val features = timeline.progressionAt(timeMs, sections)
                // Mirror the live modulation order exactly (envelopes first:
                // their offsets can drive LFO rate/depth): the export was
                // silently dropping ALL ADSR routing - including the new
                // Catch pull/Catch radius targets - from rendered video.
                val envValues = adsrEngine.tick(1f / fps, features)
                val (envRate, envDepth) =
                    dev.musicviz.render.AdsrEngine
                        .lfoOffsets(adsrEngine.configs, envValues)
                val lfoValues = lfoEngine.tick(1f / fps, features.bpm, envRate, envDepth)
                var p =
                    dev.musicviz.render.LfoEngine
                        .apply(sceneParams, lfoEngine.configs, lfoValues)
                p =
                    dev.musicviz.render.AdsrEngine
                        .apply(p, adsrEngine.configs, envValues)
                scene.setParams(p)
                scene.update(
                    dev.musicviz.render.scene
                        .applyBandGains(features, p),
                    1f / fps,
                )
                if (p.flowEnabled && flowField != null && flowField.available) {
                    // Steps into the FlowField's own FBOs, before the scene
                    // target is bound - mirrors the live frame order.
                    flowField.step(
                        dev.musicviz.render.scene
                            .applyBandGains(features, p),
                        1f / fps,
                        p,
                    )
                }
                // FlowField consumers, mirroring the live renderer: CPU grid
                // for particle scenes ("Particles ride the field"), uFlow
                // sampler for shader scenes. Without this, exported particles
                // ignored the field and shader-scene flow distortion was 0.
                if (p.flowEnabled && flowField != null && flowField.available) {
                    if (isParticle && p.flowAdvectParticles) {
                        flowField.readback(flowField.velocityTex, flowField.flowScale, flowField.aspect)
                        (scene as dev.musicviz.render.scene.ParticleSceneBase).flowGrid = flowField.cpuGrid
                    } else if (isParticle) {
                        (scene as dev.musicviz.render.scene.ParticleSceneBase).flowGrid = null
                    }
                    if (scene is dev.musicviz.render.scene.ShaderScene) {
                        scene.setFlow(flowField.velocityTex, p.flowStrength)
                    }
                }
                // Ripple overlay: advance the heightfield before the scene
                // target is bound (its own FBOs), mirroring the live order.
                val rippleOn = p.rippleOverlayEnabled && rippleOverlay != null && rippleOverlay.available
                if (rippleOn && rippleOverlay != null) {
                    rippleOverlay.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
                    rippleOverlay.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
                    rippleDrops.tick(
                        dev.musicviz.render.scene
                            .applyBandGains(features, p),
                        rippleOverlay.aspect,
                    ) { x, y, radius, amp -> rippleOverlay.queueDrop(x, y, radius, amp) }
                    rippleOverlay.step(1f / fps)
                }
                // Draw the scene into the FX FBO, then composite (with the full
                // FX chain) onto the encoder surface, matching the live path.
                fx.bindSceneTarget()
                if (((p.trails && isParticle) || isCurlFlow) && frame > 0) {
                    // Mirror the live curlPersist rule: keep >= 0.85 - but only
                    // in the plain-fade branch; the live path passes the raw
                    // trailLength to the trail-warp pass.
                    val fadeParams =
                        if (isCurlFlow && p.trailZoom == 0f && p.trailWarp <= 0f) {
                            p.copy(trailLength = p.trailLength.coerceAtLeast(0.85f))
                        } else {
                            p
                        }
                    fx.fadeSceneTargetWarp(fadeParams, fx.sceneFbo, fx.width, fx.height, timeMs / 1000f, 1f / fps)
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
                val rippleTex = if (rippleOn && rippleOverlay != null) rippleOverlay.heightTex else 0
                fx.composite(
                    timeMs / 1000f,
                    features,
                    isParticle,
                    isShaderScene,
                    p,
                    flowTex = flowTex,
                    flowStrength = if (flowTex != 0) p.flowStrength else 0f,
                    rippleTex = rippleTex,
                    rippleTexelW = if (rippleTex != 0 && rippleOverlay != null) rippleOverlay.texelW else 0f,
                    rippleTexelH = if (rippleTex != 0 && rippleOverlay != null) rippleOverlay.texelH else 0f,
                    rippleStrength = if (rippleTex != 0) p.rippleOverlayStrength.coerceIn(0f, 1f) else 0f,
                    rippleSpecular = if (rippleTex != 0) p.rippleOverlaySpecular.coerceIn(0f, 1f) else 0f,
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
            drain@ while (flushAttempts < 200) {
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
                        if (eos) break@drain
                    }
                    else -> flushAttempts++
                }
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
            // must never mask the original exception. Refs are null for any
            // resource whose creation was never reached.
            runCatching { sceneRef?.release() }
            runCatching { flowFieldRef?.release() }
            runCatching { rippleRef?.release() }
            runCatching { fxRef?.release() }
            if (muxerStarted) runCatching { muxerRef?.stop() }
            runCatching { muxerRef?.release() }
            runCatching { encoderRef?.stop() }
            runCatching { encoderRef?.release() }
            runCatching { inputSurfaceRef?.release() }
            runCatching { eglRef?.release() }
            aacRef?.release()
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
