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
import dev.musicviz.render.fluid.CurlFlowMath
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
            // devices (e.g. 4K x 21:9 would ask for 5040 wide and fail to
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

        /**
         * TRY_AGAIN rounds tolerated in the post-EOS drain before the encoder
         * is declared stalled: x [TIMEOUT_US] = 10 s of no output at all, far
         * past any healthy flush, but enough headroom that a slow 4K encoder
         * is not mistaken for a wedged one.
         */
        private const val FLUSH_ATTEMPT_LIMIT = 1_000

        /** Ripple overlay grid short side - matches the live renderer's. */
        private const val RIPPLE_OVERLAY_RES = 256

        /**
         * Whether the export fades the canvas instead of hard-clearing it.
         * Live twin: VisualizerRenderer's `persists` gate - Curl Flow and the
         * beam persist regardless of the Trails toggle (their looks are
         * DEFINED by canvas echo); particle scenes only when it is on.
         */
        fun canvasPersists(
            isCurlFlow: Boolean,
            isBeam: Boolean,
            trails: Boolean,
            isParticle: Boolean,
        ): Boolean = isCurlFlow || isBeam || (trails && isParticle)

        /**
         * Live twin: VisualizerRenderer's `isBeam` retention. The beam is
         * phosphor - the decay between frames IS the afterglow, and a trace
         * with no persistence is a single-frame wire - so there is a floor
         * under which the glow never drops, with the Trail length slider
         * setting how long it lasts above it.
         */
        fun beamRetention(trailLength: Float): Float = (0.55f + 0.44f * trailLength).coerceIn(0f, 0.99f)

        /**
         * Scans the WHOLE render for effect use. FlowField/RippleSim are
         * allocated once, before the frame loop, but a replayed take can
         * change the gating params mid-render: deciding from the take's end
         * state alone dropped an effect the performance toggled on mid-song
         * and off again before the end from the entire video. Sampling at
         * the loop's own frame timestamps makes the answer exact - allocate
         * iff some rendered frame will ask the service to run.
         */
        fun scanEffectUse(
            paramsAt: ((Long) -> SceneParams)?,
            flat: SceneParams,
            totalFrames: Int,
            fps: Int,
        ): EffectUse {
            if (paramsAt == null) return EffectUse(flat.flowEnabled, flat.rippleOverlayEnabled)
            var flow = false
            var ripple = false
            for (frame in 0 until totalFrames) {
                val p = paramsAt(frame * 1000L / fps)
                flow = flow || p.flowEnabled
                ripple = ripple || p.rippleOverlayEnabled
                if (flow && ripple) break
            }
            return EffectUse(flow, ripple)
        }
    }

    interface SceneFactory {
        fun create(): Scene
    }

    /** Effects at least one rendered frame will gate on. */
    data class EffectUse(
        val flowField: Boolean,
        val rippleOverlay: Boolean,
    )

    /**
     * How an export ended, mirroring [dev.musicviz.export.StudioExporter.Result].
     *
     * Three outcomes, not a nullable Uri. A null told the caller only that no
     * file arrived, which conflated a user cancel with the three ways saving
     * can be refused outright - MediaStore declining the insert, and either
     * output refusing to open for writing (some cloud/SAF providers do). The
     * dialog then showed a bar running to 100% and then the options form
     * again: no file, no message, and nothing to tell the user.
     */
    sealed interface Result {
        data class Saved(
            val uri: Uri,
        ) : Result

        data class Failed(
            val message: String,
        ) : Result

        data object Cancelled : Result
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
        /** Photosensitivity limits, mirroring the live renderer's clamp. */
        safety: dev.musicviz.render.VisualSafety.SafetyConfig =
            dev.musicviz.render.VisualSafety.SafetyConfig.OFF,
        requestedFps: Int = FPS,
        /**
         * Per-frame parameter override: a recorded performance take, sampled
         * at the frame's own timestamp. Null renders [sceneParams] flat, which
         * is what every export did before takes existed.
         *
         * Returns the parameters ONLY - the style a take switches to mid-set
         * is not applied here, because this renderer builds one scene up front
         * and drawing through several would mean creating, swapping and
         * releasing them inside the frame loop. The export dialog says as much
         * where the take is chosen.
         */
        paramsAt: ((Long) -> SceneParams)? = null,
        /**
         * Trim the render to a whole number of bars so the clip loops without
         * a stumble at the seam. Applies to the audio as well as the video -
         * a loop-safe video over full-length audio is not loop-safe.
         */
        loopSafe: Boolean = false,
        destination: Uri? = null,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result =
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
                    safety,
                    requestedFps,
                    paramsAt,
                    loopSafe,
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
                    ?: return@withContext Result.Failed(
                        "Your Videos library would not accept a new file. Check that storage is not full, " +
                            "or render to a folder you choose instead.",
                    )
            val pfd = resolver.openFileDescriptor(outUri, "w")
            if (pfd == null) {
                runCatching { resolver.delete(outUri, null, null) }
                return@withContext Result.Failed("The new file in your Videos library could not be opened for writing.")
            }
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
                        safety,
                        requestedFps,
                        paramsAt,
                        loopSafe,
                        onProgress,
                        isCancelled,
                    )
                }
                if (isCancelled()) {
                    // A cancelled export is a truncated file with no audio;
                    // remove it instead of publishing it to the gallery.
                    runCatching { resolver.delete(outUri, null, null) }
                    Result.Cancelled
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        resolver.update(outUri, done, null, null)
                    }
                    Result.Saved(outUri)
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
        safety: dev.musicviz.render.VisualSafety.SafetyConfig,
        requestedFps: Int,
        paramsAt: ((Long) -> SceneParams)?,
        loopSafe: Boolean,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        val resolver = context.contentResolver
        val pfd =
            resolver.openFileDescriptor(destination, "w")
                ?: return Result.Failed(
                    "The folder you chose would not let the file be written. Some cloud providers refuse " +
                        "this; try your Videos library or a folder on the device.",
                )
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
                    safety,
                    requestedFps,
                    paramsAt,
                    loopSafe,
                    onProgress,
                    isCancelled,
                )
            }
            if (isCancelled()) {
                runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                Result.Cancelled
            } else {
                Result.Saved(destination)
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
        safety: dev.musicviz.render.VisualSafety.SafetyConfig,
        requestedFps: Int,
        paramsAt: ((Long) -> SceneParams)?,
        loopSafe: Boolean,
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
        var audioFeedRef: AudioFeed? = null
        var muxerStarted = false
        var muxerStopped = false
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
            // Milkdrop grades (and mirrors/inverts) in pm_post_frag, so the
            // composite must send it the neutral identity like the live path.
            val isProjectM = scene is dev.musicviz.render.scene.ProjectMScene
            // Curl Flow's look is DEFINED by canvas persistence (live renderer
            // forces it regardless of the trails toggle); a hard-cleared export
            // reads as strobing dots instead of streams.
            val isCurlFlow = scene is dev.musicviz.render.fluid.CurlFlowScene
            // The beam's phosphor afterglow is canvas persistence too - hard-
            // cleared it exports as a thin single-frame wire (live twin:
            // VisualizerRenderer's isBeam gate).
            val isBeam = scene is dev.musicviz.render.scene.BeamScene

            // Build an offscreen FBO + composite program so the export applies the
            // SAME screen-space FX chain (geometry, chroma, vignette, scanlines,
            // grain, glitch, fisheye, strobe, bloom, posterize) the live renderer
            // does. Without this, exports - especially of particle scenes - would
            // omit every FX/shape customization, which are composite-only.
            val fx = FxCompositor(context, aspect.width, aspect.height).also { fxRef = it }

            // Video length is derived from the ACTUAL transcoded audio duration so
            // the export always matches the music exactly. The analysis timeline is
            // only used for per-frame features (featuresAt clamps at its end).
            // Computed before the effect-allocation decisions below, which
            // need to know every frame timestamp the loop will render.
            val sourceDurationUs = if (aac.durationUs > 0) aac.durationUs else timeline.durationMs * 1000
            // Loop-safe: cut on a bar boundary so the last beat runs into the
            // first. Down to the nearest bar, never up - rounding up would end
            // the clip in silence, which is worse than the seam it fixes.
            val exportDurationUs =
                if (loopSafe) {
                    dev.musicviz.analysis.BarTrim
                        .trimToBars(sourceDurationUs, timeline.bpm)
                } else {
                    sourceDurationUs
                }
            val totalFrames = (exportDurationUs * fps / 1_000_000L).toInt().coerceAtLeast(1)
            val frameDurationNs = 1_000_000_000L / fps

            // FlowField export parity (F7): run the shared field in the export GL
            // context so fluidWarp bends exported frames exactly like the live
            // view. The FLUID scene reuses its own velocity field instead.
            // Both services are allocated ONCE, before the frame loop, from
            // params that a replayed take can change mid-render - so the
            // decision scans every frame the take will render. Allocating a
            // field the render never uses costs a few small FBOs; NOT
            // allocating one the take toggles on mid-song (and maybe off
            // again before the end) silently drops the effect from the video.
            val effectUse = scanEffectUse(paramsAt, sceneParams, totalFrames, fps)
            // A field-defined particle style needs the service allocated even
            // with Flow off, or its export would be the one place the style
            // renders as a dead screen.
            val styleNeedsFlowField =
                (scene as? dev.musicviz.render.scene.ParticleSceneBase)?.requiresFlowField == true
            val usesFlowField = effectUse.flowField || styleNeedsFlowField
            val usesRippleOverlay = effectUse.rippleOverlay
            val exportFluidScene = scene as? dev.musicviz.render.fluid.FluidScene
            val flowField =
                if (usesFlowField && exportFluidScene == null) {
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
                if (usesRippleOverlay && exportWaterScene == null) {
                    dev.musicviz.render.fluid.RippleSim(context).also {
                        rippleRef = it
                        it.create()
                        it.applyResolution(RIPPLE_OVERLAY_RES)
                        it.resize(aspect.width, aspect.height)
                    }
                } else {
                    null
                }
            val rippleDrops =
                dev.musicviz.render.fluid
                    .RippleOverlayDrops()
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
            // Section boundaries once (O(n)); per-frame features then carry the
            // progress/section context, so the fluid spawn/catch choreography
            // journeys through the exported video exactly like live playback.
            val sections = timeline.detectSections()

            for (frame in 0 until totalFrames) {
                if (isCancelled()) break
                // Same first line as the live frame: projectM's native render
                // leaves GL state dirty, and the live renderer has undone it at
                // the top of every frame since that bug was found. The export
                // path never did - it happened to survive because the scenes
                // that dirty state also reset it themselves, which is a
                // property of those scenes rather than a guarantee. This is the
                // one path where a corrupt frame is written to a file instead
                // of to a screen someone is looking at.
                dev.musicviz.render.scene.GlUtil
                    .resetFrameState()
                val timeMs = frame * 1000L / fps
                // An exported frame is on screen until the next one, so it has
                // to see the WHOLE span of 60 Hz timeline frames it covers, not
                // just the nearest one: the beat flag is exactly one timeline
                // frame wide, so a 30 fps render (every other frame) used to
                // miss about half the track's beats - no uBeat, no flash/shake
                // and no Beat pulse on those. Spans tile exactly, so at 60 fps
                // this is still one timeline frame and nothing changes.
                val nextTimeMs = (frame + 1) * 1000L / fps
                val features = timeline.progressionAt(timeMs, sections, nextTimeMs - timeMs)
                // Mirror the live modulation order exactly (envelopes first:
                // their offsets can drive LFO rate/depth): the export was
                // silently dropping ALL ADSR routing - including the new
                // Catch pull/Catch radius targets - from rendered video.
                val envValues = adsrEngine.tick(1f / fps, features)
                val (envRate, envDepth) =
                    dev.musicviz.render.AdsrEngine
                        .lfoOffsets(adsrEngine.configs, envValues)
                val lfoValues = lfoEngine.tick(1f / fps, features.bpm, envRate, envDepth, safety)
                // A replayed take supplies the frame's own parameters; the
                // modulators then run on top of them exactly as they do live,
                // so an LFO the user set up still moves during a take render.
                val frameParams = paramsAt?.invoke(timeMs) ?: sceneParams
                var p =
                    dev.musicviz.render.LfoEngine
                        .apply(frameParams, lfoEngine.configs, lfoValues)
                p =
                    dev.musicviz.render.AdsrEngine
                        .apply(p, adsrEngine.configs, envValues)
                // Mirrors VisualizerRenderer's third line: the photosensitivity
                // clamp runs after every modulator, so a rendered clip is as
                // safe as the screen the user approved it from. If these two
                // ever diverge, an export becomes the one place the limits do
                // not apply.
                p =
                    dev.musicviz.render.VisualSafety
                        .apply(p, safety)
                // Adaptive fluid quality is a frame-time sensor, and this loop
                // has no frame times - it drives every scene with a constant
                // dt = 1/fps off the export clock. Left on, a 30 fps render
                // reads as a permanent deficit against PerformanceMonitor's
                // 50 fps target and drops two tiers every 2.5 s until it
                // bottoms out, so the file came out at minimum quality while
                // the screen it was exported from looked fine. The tier the
                // user chose is the tier that renders; see
                // ExportDeterministicQualityTest for the arithmetic.
                p = p.copy(fluidAutoQuality = false)
                scene.setParams(p)
                scene.update(
                    dev.musicviz.render.scene
                        .applyBandGains(features, p),
                    1f / fps,
                )
                // A field-defined particle style runs the service whatever the
                // Flow toggle says, exactly as the live renderer does - the
                // exported clip has to be the style the user approved.
                val sceneNeedsFlow =
                    (scene as? dev.musicviz.render.scene.ParticleSceneBase)?.requiresFlowField == true
                // Two-way coupling's return leg, drained after the update that
                // produced the kicks and before the step that consumes them.
                if (flowField != null && flowField.available && isParticle) {
                    val kicks = (scene as dev.musicviz.render.scene.ParticleSceneBase).flowKicks
                    for (i in 0 until kicks.size) {
                        flowField.queueKick(kicks.x[i], kicks.y[i], kicks.vx[i], kicks.vy[i], kicks.radius[i])
                    }
                    kicks.clear()
                }
                if ((p.flowEnabled || sceneNeedsFlow) && flowField != null && flowField.available) {
                    // Steps into the FlowField's own FBOs, before the scene
                    // target is bound.
                    //
                    // KNOWN DIVERGENCE, deliberate and not yet decided: the live
                    // renderer steps the field BEFORE scene.update, so a kick
                    // the scene queues this frame is consumed by the NEXT
                    // frame's step ("one frame of latency", VisualizerRenderer).
                    // Here the drain above happens after the update that
                    // produced the kicks and before the step that consumes them,
                    // so the coupling closes within a single frame. Both are
                    // self-consistent; they are not the same, and a
                    // field-defined style (Inkflow) therefore renders one frame
                    // of coupling phase away from what the screen showed.
                    // Aligning them changes rendered output, so it wants a
                    // golden-frame comparison rather than a quiet edit.
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
                if ((p.flowEnabled || sceneNeedsFlow) && flowField != null && flowField.available) {
                    if (isParticle && (p.flowAdvectParticles || sceneNeedsFlow)) {
                        flowField.readback(flowField.velocityTex, flowField.flowScale, flowField.aspect)
                        (scene as dev.musicviz.render.scene.ParticleSceneBase).flowGrid = flowField.cpuGrid
                    } else if (isParticle) {
                        (scene as dev.musicviz.render.scene.ParticleSceneBase).flowGrid = null
                    }
                    if (scene is dev.musicviz.render.scene.ShaderScene && p.flowEnabled) {
                        scene.setFlow(flowField.velocityTex, p.flowStrength)
                    }
                }
                // Ripple overlay: advance the heightfield before the scene
                // target is bound (its own FBOs), mirroring the live order.
                val rippleOn = p.rippleOverlayEnabled && rippleOverlay != null && rippleOverlay.available
                if (rippleOn) {
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
                if (canvasPersists(isCurlFlow, isBeam, p.trails, isParticle) && frame > 0) {
                    // Mirror the live trails gate (VisualizerRenderer): Curl
                    // Flow ALWAYS persists - its bare GL_POINTS strobe on a
                    // cleared canvas and `trails` defaults to false - but the
                    // toggle still picks the band, a short OFF_RETENTION echo
                    // versus the remapped Trail length slider. The beam
                    // always persists too (phosphor: the decay IS the
                    // afterglow), on its own floored remap. Same remap in
                    // the plain-fade and the trail-warp branch.
                    val fadeParams =
                        when {
                            isCurlFlow -> p.copy(trailLength = CurlFlowMath.retention(p.trailLength, p.trails))
                            isBeam -> p.copy(trailLength = beamRetention(p.trailLength))
                            else -> p
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
                val rippleTex = if (rippleOn) rippleOverlay.heightTex else 0
                fx.composite(
                    timeSeconds = timeMs / 1000f,
                    // The composite integrates rotation/colour cycle on the
                    // export's own clock, so it must see the export's frame
                    // delta - 1/60 would spin a 30 fps render at half speed.
                    dtSeconds = 1f / fps,
                    features = features,
                    isParticle = isParticle,
                    isShaderScene = isShaderScene,
                    isProjectM = isProjectM,
                    params = p,
                    flowTex = flowTex,
                    flowStrength = if (flowTex != 0) p.flowStrength else 0f,
                    rippleTex = rippleTex,
                    rippleTexelW = if (rippleTex != 0 && rippleOverlay != null) rippleOverlay.texelW else 0f,
                    rippleTexelH = if (rippleTex != 0 && rippleOverlay != null) rippleOverlay.texelH else 0f,
                    rippleStrength = if (rippleTex != 0) p.rippleOverlayStrength.coerceIn(0f, 1f) else 0f,
                    rippleSpecular = if (rippleTex != 0) p.rippleOverlaySpecular.coerceIn(0f, 1f) else 0f,
                    strobeHz =
                        dev.musicviz.render.VisualSafety
                            .strobeHz(safety),
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
                        val buf = checkNotNull(encoder.getOutputBuffer(outIndex)) { "video encoder buffer null (codec error state)" }
                        writeSample(muxer, videoTrack, buf, info, muxerStarted)
                        encoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        break
                    }
                }
                // Feed the audio interleaved with the video it accompanies.
                // Muxed as one block after the frame loop (the old shape),
                // the MP4 was legal but fully non-interleaved - all video,
                // then all audio - which streams badly everywhere the file is
                // most likely to go (Drive preview, chat players, casting):
                // the first second of sound lives at the far end of the file.
                if (muxerStarted && audioTrack >= 0) {
                    val feed =
                        audioFeedRef ?: AudioFeed(muxer, audioTrack, aac, exportDurationUs).also { audioFeedRef = it }
                    feed.writeUpTo(timeMs * 1000L)
                }
                onProgress(0.1f + frame / totalFrames.toFloat() * 0.85f)
            }
            encoder.signalEndOfInputStream()
            // Encoders return TRY_AGAIN repeatedly while flushing; keep draining
            // until EOS (bounded so a stuck codec cannot hang the export).
            var flushAttempts = 0
            var sawEos = false
            drain@ while (flushAttempts < FLUSH_ATTEMPT_LIMIT) {
                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioTrack = muxer.addTrack(aac.format)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buf = checkNotNull(encoder.getOutputBuffer(outIndex)) { "video encoder buffer null (codec error state)" }
                        writeSample(muxer, videoTrack, buf, info, muxerStarted)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (eos) {
                            sawEos = true
                            break@drain
                        }
                    }
                    else -> flushAttempts++
                }
            }
            // If the encoder never emitted its output format, nothing was
            // muxed: without this check the export would "succeed" with an
            // empty/broken file.
            check(muxerStarted || isCancelled()) { "Video encoder produced no output (encoder/format unsupported?)" }
            // An encoder that stalled before EOS used to exit the bounded
            // drain silently, publishing a file with its tail frames missing.
            // An export that cannot finish is a failure, not a shorter video.
            check(sawEos || isCancelled()) { "Video encoder stalled while flushing - export incomplete" }
            if (muxerStarted && !isCancelled() && audioTrack >= 0) {
                // Whatever the frame loop has not fed yet - normally just the
                // last frame's span of samples, trimmed to the same instant as
                // the video: a loop-safe picture over full-length sound still
                // stumbles.
                val feed =
                    audioFeedRef ?: AudioFeed(muxer, audioTrack, aac, exportDurationUs).also { audioFeedRef = it }
                feed.writeUpTo(Long.MAX_VALUE)
                onProgress(1f)
            }
            if (muxerStarted && !isCancelled()) {
                // stop() is where the moov atom is written, so a failure here
                // (a disk that filled at finalize, a track with no samples)
                // means a file no player can open. Done on the success path,
                // where it is allowed to throw: swallowed in the finally, the
                // export returned normally and published that file to the
                // gallery as "Saved".
                muxer.stop()
                muxerStopped = true
            }
        } finally {
            // Cleanup failures (e.g. stopping a muxer after a mid-export error)
            // must never mask the original exception. Refs are null for any
            // resource whose creation was never reached.
            runCatching { sceneRef?.release() }
            runCatching { flowFieldRef?.release() }
            runCatching { rippleRef?.release() }
            runCatching { fxRef?.release() }
            runCatching { audioFeedRef?.close() }
            // Only the abandoned runs - cancelled, or unwinding from an
            // exception - stop the muxer here, where the failure must stay
            // swallowed so it cannot mask the reason the export is unwinding.
            if (muxerStarted && !muxerStopped) runCatching { muxerRef?.stop() }
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

    /**
     * Streams the pre-transcoded AAC samples from the temp file into the
     * muxer, in step with the video: [writeUpTo] is fed inside the frame loop
     * with the frame's own timestamp, then once more with no bound after the
     * final drain. The cursor makes each sample write exactly once.
     */
    private class AudioFeed(
        private val muxer: MediaMuxer,
        private val track: Int,
        private val aac: AudioTranscoder.Result,
        /** Samples at or after this timestamp are dropped (loop-safe trim). */
        private val limitUs: Long,
    ) : java.io.Closeable {
        private val raf = java.io.RandomAccessFile(aac.file, "r")
        private val info = MediaCodec.BufferInfo()
        private var scratch = ByteBuffer.allocate(64 * 1024)
        private var next = 0

        /** Writes every not-yet-written sample with a timestamp before [upToUs]. */
        fun writeUpTo(upToUs: Long) {
            val channel = raf.channel
            while (next < aac.sampleInfos.size) {
                val sample = aac.sampleInfos[next]
                if (sample.presentationTimeUs >= upToUs) return
                if (sample.presentationTimeUs >= limitUs) {
                    // Samples are in timestamp order; past the trim, done for good.
                    next = aac.sampleInfos.size
                    return
                }
                next++
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
            }
        }

        override fun close() {
            runCatching { raf.close() }
        }
    }
}
