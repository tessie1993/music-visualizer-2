package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.export.VideoExporter
import dev.musicviz.render.fluid.CurlFlowMath
import dev.musicviz.render.scene.BurstScene
import dev.musicviz.render.scene.FountainScene
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.NebulaScene
import dev.musicviz.render.scene.OrbitScene
import dev.musicviz.render.scene.PMBridge
import dev.musicviz.render.scene.ParticleSceneBase
import dev.musicviz.render.scene.PcmChunk
import dev.musicviz.render.scene.ProjectMScene
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.render.scene.ShaderScene
import dev.musicviz.render.scene.SwarmScene
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.pow

/**
 * Multi-scene GL ES 3.0 renderer with an offscreen pipeline: the active scene
 * renders into FBO A; during a transition the outgoing scene renders into
 * FBO B and a compositor shader blends them (Cut/Fade/Melt). Trails work by
 * fading FBO A instead of clearing it. Scene switching, params and shader
 * edits are queued from other threads and applied on the GL thread; all GL
 * resources are (re)created in [onSurfaceCreated].
 */
class VisualizerRenderer(
    private val context: Context,
) : GLSurfaceView.Renderer {
    companion object {
        /** Fragment-shader scenes: id -> raw resource. Order = UI order. */
        val SHADER_SCENES: Map<String, Int> =
            linkedMapOf(
                SceneIds.JULIA to R.raw.julia_frag,
                SceneIds.TUNNEL to R.raw.tunnel_frag,
                SceneIds.MANDEL to R.raw.mandel_frag,
                SceneIds.KALEIDO to R.raw.kaleido_frag,
                SceneIds.PLASMA to R.raw.plasma_frag,
                SceneIds.BARS to R.raw.bars_frag,
                SceneIds.RING to R.raw.ring_frag,
                SceneIds.SCOPE to R.raw.scope_frag,
                SceneIds.LISS to R.raw.liss_frag,
                SceneIds.WARP to R.raw.warp_frag,
                SceneIds.GRID to R.raw.grid_frag,
                SceneIds.VORONOI to R.raw.voronoi_frag,
                SceneIds.METABALLS to R.raw.metaballs_frag,
                SceneIds.RIPPLES to R.raw.ripples_frag,
                SceneIds.STARFIELD to R.raw.starfield_frag,
                SceneIds.WAVES to R.raw.waves_frag,
                SceneIds.HEXGRID to R.raw.hexgrid_frag,
                SceneIds.SPIRAL to R.raw.spiral_frag,
                SceneIds.AURORA to R.raw.aurora_frag,
                SceneIds.SOLAR to R.raw.solar_frag,
            )
        val PARTICLE_SCENES: List<String> =
            listOf(SceneIds.NEBULA, SceneIds.BURSTS, SceneIds.SWARM, SceneIds.FOUNTAIN, SceneIds.ORBITS)
    }

    @Volatile
    var features: AudioFeatures = AudioFeatures.empty()

    @Volatile
    var requestedSceneId: String = SceneIds.NEBULA

    @Volatile
    var sceneParams: SceneParams = SceneParams.DEFAULT

    /** Smoothed params actually shown; fades toward [sceneParams] over paramFadeSec. */
    private var displayedParams: SceneParams = SceneParams.DEFAULT

    // One-shot preset morph: glides displayedParams over the given seconds,
    // then expires (3 time constants ~ 95% settled) so later slider tweaks
    // respond at the user's own paramFadeSec again.
    @Volatile
    private var morphFadeSec = 0f

    @Volatile
    private var morphRemainSec = 0f

    /** Called on preset apply; safe from any thread (floats, worst case one late frame). */
    fun beginParamMorph(seconds: Float) {
        if (seconds <= 0f) return
        morphFadeSec = seconds
        morphRemainSec = seconds * 3f
    }

    /** Assignable LFO modulation, evaluated per frame after smoothing. */
    val lfoEngine = LfoEngine()

    /** Beat-triggered ADSR envelope, applied after the LFOs. */
    val adsrEngine = AdsrEngine()

    /** Final params of the current frame (after fade + LFO), for the composite FX pass. */
    private var lastFinalParams: SceneParams = SceneParams.DEFAULT

    /** Composite-pass rotation angle. Rotation is a SPEED in every scene
     *  (`rotationAngle += p.rotation * dt`), so the pass that rotates the
     *  fluid family has to integrate its own angle rather than feed the raw
     *  slider through as a static offset. */
    private var postRotationAngle = 0f

    /** Composite-pass colour-cycle phase, integrated like ShaderScene's, so
     *  the hue uploaded below is `colorShift + cyclePhase` exactly as every
     *  self-grading scene computes it. */
    private var postCyclePhase = 0f

    /** Composite-pass beat envelope (1 on a beat, decaying), the source of the
     *  "Beat pulse" swell for the scenes that don't pulse themselves. The
     *  shader's own `uBeat` is a per-frame boolean, so a pulse driven from it
     *  would be a single-frame pop; this is the same decaying envelope
     *  ShaderScene/ParticleSceneBase keep. */
    private var postBeatPulse = 0f

    /** Exponential lerp between param sets; toggles and choices snap to target. */
    private fun lerpParams(
        from: SceneParams,
        to: SceneParams,
        k: Float,
    ): SceneParams {
        fun f(
            a: Float,
            b: Float,
        ) = a + (b - a) * k
        return to.copy(
            speed = f(from.speed, to.speed),
            zoom = f(from.zoom, to.zoom),
            rotation = f(from.rotation, to.rotation),
            endlessZoomSpeed = f(from.endlessZoomSpeed, to.endlessZoomSpeed),
            sway = f(from.sway, to.sway),
            pulse = f(from.pulse, to.pulse),
            driftX = f(from.driftX, to.driftX),
            driftY = f(from.driftY, to.driftY),
            shake = f(from.shake, to.shake),
            audioDrive = f(from.audioDrive, to.audioDrive),
            beatResponse = f(from.beatResponse, to.beatResponse),
            turbulence = f(from.turbulence, to.turbulence),
            density = f(from.density, to.density),
            trailLength = f(from.trailLength, to.trailLength),
            warp = f(from.warp, to.warp),
            ripple = f(from.ripple, to.ripple),
            morph = f(from.morph, to.morph),
            pixelate = f(from.pixelate, to.pixelate),
            posterize = f(from.posterize, to.posterize),
            particleSize = f(from.particleSize, to.particleSize),
            tile = f(from.tile, to.tile),
            twist = f(from.twist, to.twist),
            paletteMix = f(from.paletteMix, to.paletteMix),
            colorShift = f(from.colorShift, to.colorShift),
            hueRange = f(from.hueRange, to.hueRange),
            saturation = f(from.saturation, to.saturation),
            brightness = f(from.brightness, to.brightness),
            contrast = f(from.contrast, to.contrast),
            gamma = f(from.gamma, to.gamma),
            cycleSpeed = f(from.cycleSpeed, to.cycleSpeed),
            intensity = f(from.intensity, to.intensity),
            bloom = f(from.bloom, to.bloom),
            temperature = f(from.temperature, to.temperature),
            bassGain = f(from.bassGain, to.bassGain),
            midGain = f(from.midGain, to.midGain),
            trebGain = f(from.trebGain, to.trebGain),
            flash = f(from.flash, to.flash),
            chromaAb = f(from.chromaAb, to.chromaAb),
            vignette = f(from.vignette, to.vignette),
            scanlines = f(from.scanlines, to.scanlines),
            grain = f(from.grain, to.grain),
            glitch = f(from.glitch, to.glitch),
            fisheye = f(from.fisheye, to.fisheye),
            strobe = f(from.strobe, to.strobe),
            fluidPressure = f(from.fluidPressure, to.fluidPressure),
            fluidCurl = f(from.fluidCurl, to.fluidCurl),
            fluidVelocityDissipation = f(from.fluidVelocityDissipation, to.fluidVelocityDissipation),
            fluidDensityDissipation = f(from.fluidDensityDissipation, to.fluidDensityDissipation),
            fluidChromaticAging = f(from.fluidChromaticAging, to.fluidChromaticAging),
            fluidSplatRadius = f(from.fluidSplatRadius, to.fluidSplatRadius),
            fluidSplatForce = f(from.fluidSplatForce, to.fluidSplatForce),
            fluidStirrerSpeed = f(from.fluidStirrerSpeed, to.fluidStirrerSpeed),
            fluidPaletteCycleSpeed = f(from.fluidPaletteCycleSpeed, to.fluidPaletteCycleSpeed),
            fluidParticleDrag = f(from.fluidParticleDrag, to.fluidParticleDrag),
            fluidParticleBrightness = f(from.fluidParticleBrightness, to.fluidParticleBrightness),
            fluidBloomIntensity = f(from.fluidBloomIntensity, to.fluidBloomIntensity),
            fluidBloomThreshold = f(from.fluidBloomThreshold, to.fluidBloomThreshold),
            fluidSunraysWeight = f(from.fluidSunraysWeight, to.fluidSunraysWeight),
            fluidCurlAudio = f(from.fluidCurlAudio, to.fluidCurlAudio),
            fluidBloomAudio = f(from.fluidBloomAudio, to.fluidBloomAudio),
            fluidFadeAudio = f(from.fluidFadeAudio, to.fluidFadeAudio),
            fluidRadiusPulse = f(from.fluidRadiusPulse, to.fluidRadiusPulse),
            fluidSpawnProgress = f(from.fluidSpawnProgress, to.fluidSpawnProgress),
            fluidCatchPull = f(from.fluidCatchPull, to.fluidCatchPull),
            fluidCatchRadius = f(from.fluidCatchRadius, to.fluidCatchRadius),
            fluidParticleLife = f(from.fluidParticleLife, to.fluidParticleLife),
            flowStrength = f(from.flowStrength, to.flowStrength),
            flowForce = f(from.flowForce, to.flowForce),
            flowCurl = f(from.flowCurl, to.flowCurl),
            waterWaveSpeed = f(from.waterWaveSpeed, to.waterWaveSpeed),
            waterDamping = f(from.waterDamping, to.waterDamping),
            waterRippleStrength = f(from.waterRippleStrength, to.waterRippleStrength),
            waterDepth = f(from.waterDepth, to.waterDepth),
            waterSpecular = f(from.waterSpecular, to.waterSpecular),
            waterFlow = f(from.waterFlow, to.waterFlow),
            rippleOverlayStrength = f(from.rippleOverlayStrength, to.rippleOverlayStrength),
            rippleOverlaySpecular = f(from.rippleOverlaySpecular, to.rippleOverlaySpecular),
        )
    }

    private fun gainAdjusted(
        f: dev.musicviz.analysis.AudioFeatures,
        p: SceneParams,
    ): dev.musicviz.analysis.AudioFeatures =
        dev.musicviz.render.scene
            .applyBandGains(f, p)

    @Volatile
    var transitionStyle: TransitionStyle = TransitionStyle.FADE

    @Volatile
    var transitionDurationMs: Long = 1200

    @Volatile
    var onShaderError: (String?) -> Unit = {}

    /** Queue (not a single slot) so rapid edits to different scenes all land. */
    private val pendingCustomShaders = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    /** Retained across EGL context loss so scenes can be restored on recreation. */
    private var lastMilkPreset: String? = null
    private val activeCustomShaders = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** User fluid force/dye injection sources (extension points), retained
     *  across context loss like custom scene shaders. */
    @Volatile
    private var fluidForceSrc: String? = null

    @Volatile
    private var fluidDyeSrc: String? = null

    @Volatile
    private var fluidInjectionDirty = false

    /** Installs user force/dye GLSL for the FLUID scene (null = built-in). */
    fun submitFluidInjectionShaders(
        force: String?,
        dye: String?,
    ) {
        fluidForceSrc = force
        fluidDyeSrc = dye
        fluidInjectionDirty = true
    }

    /** F7 FlowField service + the 1x1 zero texture bound when it's off. */
    private var flowField: dev.musicviz.render.fluid.FlowField? = null
    private var zeroTex = 0

    /** F2 ripple overlay: renderer-owned heightfield refracting ANY style. */
    private var rippleOverlay: dev.musicviz.render.fluid.RippleSim? = null
    private val rippleDrops =
        dev.musicviz.render.fluid
            .RippleOverlayDrops()

    /** Overlay grid short side: fixed budget tier (WaterScene tier 4-ish);
     *  the overlay rides on top of a full scene, so it stays cheap. */
    private val rippleOverlayRes = 256

    /** Fresh mono PCM for projectM; set by the UI wiring. */
    @Volatile
    var pcmProvider: () -> PcmChunk? = { null }

    val milkdropAvailable: Boolean get() = PMBridge.available

    private val scenes = LinkedHashMap<String, Scene>()
    private var activeScene: Scene? = null
    private var outgoingScene: Scene? = null

    /** Frozen at transition start: the outgoing scene keeps the look it had
     *  when the switch happened. Feeding it the live (morphing) params made
     *  snapped fields - palette choice, toggles - jump on the OLD scene
     *  during its fade-out, one of the preset-switch flash sources. */
    private var outgoingParams: SceneParams? = null
    private var transitionStartMs = 0L
    private var width = 1
    private var height = 1
    private var renderWidth = 1
    private var renderHeight = 1
    private var lastFrameMs = 0L
    private var timeSeconds = 0f
    private var fadeProgram = 0
    private var trailWarpProgram = 0
    private var trailFbo = 0
    private var trailTex = 0
    private var compositeProgram = 0

    /** Uniform locations cached per program link; ~30 glGetUniformLocation
     *  calls per frame are measurable driver overhead on mobile GPUs. */
    private val compositeLocs = HashMap<String, Int>()
    private val fadeLocs = HashMap<String, Int>()
    private val trailLocs = HashMap<String, Int>()

    private fun cLoc(name: String): Int = compositeLocs.getOrPut(name) { GLES30.glGetUniformLocation(compositeProgram, name) }

    private var quadVao = 0
    private var fboA = TargetFbo()
    private var fboB = TargetFbo()

    private class TargetFbo {
        var fbo = 0
        var tex = 0
        var w = 0
        var h = 0

        fun ensure(
            width: Int,
            height: Int,
        ) {
            if (fbo != 0 && w == width && h == height) return
            release()
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null,
            )
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                tex,
                0,
            )
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            w = width
            h = height
        }

        fun release() {
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            fbo = 0
            tex = 0
            w = 0
            h = 0
        }
    }

    fun availableSceneIds(): List<String> =
        buildList {
            addAll(PARTICLE_SCENES)
            addAll(SHADER_SCENES.keys)
            if (PMBridge.available) add(SceneIds.MILKDROP)
            add(SceneIds.FLUID)
            add(SceneIds.CURLFLOW)
            add(SceneIds.WATER)
        }

    fun submitShader(
        sceneId: String,
        fragmentSrc: String,
    ) {
        pendingCustomShaders.add(sceneId to fragmentSrc)
        activeCustomShaders[sceneId] = fragmentSrc
    }

    fun shaderSourceFor(sceneId: String): String? = SHADER_SCENES[sceneId]?.let { loadRaw(it) }

    /** The user-edited fragment source for [sceneId], or null if unedited. */
    fun customShaderFor(sceneId: String): String? = activeCustomShaders[sceneId]

    fun loadMilkPreset(path: String) {
        lastMilkPreset = path
        (scenes[SceneIds.MILKDROP] as? ProjectMScene)?.queuePreset(path)
    }

    /** Re-queues the currently loaded preset so newly added textures apply. */
    fun reloadCurrentMilkPreset() {
        (scenes[SceneIds.MILKDROP] as? ProjectMScene)?.reloadCurrent()
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        scenes.values.forEach { it.release() }
        scenes.clear()
        fboA.release()
        fboB.release()
        // Trail buffer names belong to the OLD context; without this reset
        // ensureTrailBuffer() keeps blitting into a dead framebuffer after
        // the app resumes (trail warp renders black until a resize).
        trailFbo = 0
        trailTex = 0
        trailW = 0
        trailH = 0
        val particleShaders = particleShaderSources(context)
        scenes[SceneIds.NEBULA] = NebulaScene(particleShaders)
        scenes[SceneIds.BURSTS] = BurstScene(particleShaders)
        scenes[SceneIds.SWARM] = SwarmScene(particleShaders)
        scenes[SceneIds.FOUNTAIN] = FountainScene(particleShaders)
        scenes[SceneIds.ORBITS] = OrbitScene(particleShaders)
        val quadVert = loadRaw(R.raw.quad_vert)
        for ((id, res) in SHADER_SCENES) {
            scenes[id] = ShaderScene(id, quadVert, loadRaw(res)) { onShaderError(it) }
        }
        scenes[SceneIds.FLUID] =
            dev.musicviz.render.fluid.FluidScene(context).also { fluid ->
                fluid.onShaderError = { onShaderError(it) }
            }
        // Was listed in availableSceneIds but never constructed - selecting
        // Curl Flow silently did nothing (the "style not working" bug).
        scenes[SceneIds.CURLFLOW] =
            dev.musicviz.render.fluid
                .CurlFlowScene(context)
        scenes[SceneIds.WATER] =
            dev.musicviz.render.fluid.WaterScene(context).also { water ->
                water.onShaderError = { onShaderError(it) }
            }
        if (PMBridge.available) {
            scenes[SceneIds.MILKDROP] =
                ProjectMScene(
                    postVertexSrc = loadRaw(R.raw.fade_vert),
                    postFragmentSrc = loadRaw(R.raw.pm_post_frag),
                    sharedTextureDir = File(context.filesDir, "milk/textures").absolutePath,
                    pcmProvider = { pcmProvider() },
                    onError = { onShaderError(it) },
                )
        }
        scenes.values.forEach { it.init() }
        // Restore state that would otherwise be lost when the EGL context is
        // destroyed while backgrounded: re-apply the current params to every
        // scene, re-push any edited custom shaders, and re-queue the last
        // milkdrop preset so the visualizer resumes exactly where it was.
        scenes.values.forEach { it.setParams(sceneParams) }
        for ((sceneId, src) in activeCustomShaders) {
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        lastMilkPreset?.let { (scenes[SceneIds.MILKDROP] as? ProjectMScene)?.queuePreset(it) }
        // Re-apply user fluid injection shaders lost with the old context.
        if (fluidForceSrc != null || fluidDyeSrc != null) fluidInjectionDirty = true
        activeScene = scenes[requestedSceneId] ?: scenes[SceneIds.NEBULA]
        outgoingScene = null
        outgoingParams = null

        // FlowField service (F7) + the always-valid zero flow texture.
        flowField?.release()
        flowField =
            dev.musicviz.render.fluid
                .FlowField(context)
                .also { it.create() }
        // Ripple overlay service (F2): handles from a lost EGL context are
        // dead names, so it is released and rebuilt here like the FlowField.
        rippleOverlay?.release()
        rippleOverlay =
            dev.musicviz.render.fluid
                .RippleSim(context)
                .also {
                    it.create()
                    it.applyResolution(rippleOverlayRes)
                }
        rippleDrops.reset()
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        zeroTex = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, zeroTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val zero =
            java.nio.ByteBuffer
                .allocateDirect(4)
                .apply { put(byteArrayOf(0, 0, 0, 0)).position(0) }
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            1,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            zero,
        )

        fadeProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.fade_frag))
        trailWarpProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.trail_warp_frag))
        compositeProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.composite_frag))
        compositeLocs.clear()
        fadeLocs.clear()
        trailLocs.clear()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        quadVao = ids[0]
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        lastFrameMs = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        GLES30.glViewport(0, 0, width, height)
        // Render scenes into supersampled FBOs (1.4x per axis ~= 2x the pixels),
        // then downsample with LINEAR filtering at composite time. This is the
        // fix for visible pixelation/aliasing when the Zoom customization
        // magnifies the image: the extra source resolution gives the zoom more
        // detail to sample instead of blowing up screen-resolution texels.
        // Capped so 4K-class displays don't exceed sane FBO sizes.
        val ss = supersampleFactor(width, height)
        renderWidth = (width * ss).toInt()
        renderHeight = (height * ss).toInt()
        scenes.values.forEach { it.resize(renderWidth, renderHeight) }
        flowField?.resize(renderWidth, renderHeight)
        rippleOverlay?.resize(renderWidth, renderHeight)
        fboA.ensure(renderWidth, renderHeight)
        fboB.ensure(renderWidth, renderHeight)
    }

    /** 1.4x supersample on typical screens, easing to 1.0x on very large ones. */
    private fun supersampleFactor(
        width: Int,
        height: Int,
    ): Float {
        val longest = maxOf(width, height)
        return when {
            longest >= 2200 -> 1.0f
            longest >= 1600 -> 1.25f
            else -> 1.4f
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // State contract: undo anything the previous frame's scenes (native
        // projectM especially) left dirty before any pass runs.
        GlUtil.resetFrameState()
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
        lastFrameMs = now
        timeSeconds += dt

        while (true) {
            val (sceneId, src) = pendingCustomShaders.poll() ?: break
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        val requested = scenes[requestedSceneId]
        var sceneJustSwitched = false
        if (requested != null && requested !== activeScene) {
            if (transitionStyle != TransitionStyle.CUT && activeScene != null) {
                outgoingScene = activeScene
                outgoingParams = lastFinalParams
                transitionStartMs = now
            }
            activeScene = requested
            sceneJustSwitched = true
        }
        val scene = activeScene ?: return
        // Settings fade: exponentially approach the target params so preset
        // and slider changes glide instead of jumping. Toggles/choices snap.
        // A transient preset morph can lengthen the fade without ever being
        // written into (and persisted with) the params themselves.
        val morph =
            if (morphRemainSec > 0f) {
                morphRemainSec -= dt
                morphFadeSec
            } else {
                0f
            }
        val fade = maxOf(sceneParams.paramFadeSec, morph)
        displayedParams =
            if (fade <= 0.01f) {
                sceneParams
            } else {
                val k = (dt / fade).coerceIn(0f, 1f)
                lerpParams(displayedParams, sceneParams, k)
            }
        // Envelopes first: their outputs can drive LFO rate/depth, so the
        // offsets must exist before the LFOs tick.
        val envValues = adsrEngine.tick(dt, features)
        val (envRate, envDepth) = AdsrEngine.lfoOffsets(adsrEngine.configs, envValues)
        val lfoValues = lfoEngine.tick(dt, features.bpm, envRate, envDepth)
        var p = LfoEngine.apply(displayedParams, lfoEngine.configs, lfoValues)
        p = AdsrEngine.apply(p, adsrEngine.configs, envValues)
        lastFinalParams = p
        postRotationAngle = CompositeGrade.integrateRotation(postRotationAngle, p.rotation, dt)
        postCyclePhase = CompositeGrade.integrateCyclePhase(postCyclePhase, p.cycleSpeed, dt, p.colorCycle)
        postBeatPulse = CompositeGrade.integrateBeatPulse(postBeatPulse, features.beatImpulse, dt)
        if (fluidInjectionDirty) {
            fluidInjectionDirty = false
            (scenes[SceneIds.FLUID] as? dev.musicviz.render.fluid.FluidScene)
                ?.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
        }
        // F7 FlowField: advance the shared velocity field (its own tiny FBOs)
        // before any scene target is bound. When the FLUID scene is active
        // its own field is reused instead - never both (one source of truth).
        val ff = flowField
        val fluidActive = scene is dev.musicviz.render.fluid.FluidScene
        if (p.flowEnabled && ff != null && ff.available && !fluidActive) {
            ff.step(gainAdjusted(features, p), dt, p)
        }
        // F2 ripple overlay: advance the shared heightfield (its own tiny
        // FBOs) before any scene target is bound. When the WATER scene is
        // active its own sim already refracts the display - never both (one
        // source of truth, no double-applied refraction), mirroring the
        // FLUID/FlowField exclusivity above.
        val ripple = rippleOverlay
        val waterActive = scene is dev.musicviz.render.fluid.WaterScene
        val rippleOverlayOn =
            p.rippleOverlayEnabled && ripple != null && ripple.available && !waterActive
        if (rippleOverlayOn && ripple != null) {
            ripple.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
            ripple.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
            rippleDrops.tick(gainAdjusted(features, p), ripple.aspect) { x, y, radius, amp ->
                ripple.queueDrop(x, y, radius, amp)
            }
            ripple.step(dt)
        }
        fboA.ensure(renderWidth, renderHeight)
        fboB.ensure(renderWidth, renderHeight)

        var progress = 1f
        val outgoing = outgoingScene
        if (outgoing != null) {
            progress = ((now - transitionStartMs).toFloat() / transitionDurationMs).coerceIn(0f, 1f)
            if (progress >= 1f) {
                outgoingScene = null
                outgoingParams = null
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB.fbo)
                GLES30.glViewport(0, 0, renderWidth, renderHeight)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                val op = outgoingParams ?: p
                outgoing.setParams(op)
                outgoing.update(gainAdjusted(features, op), dt)
                outgoing.draw(timeSeconds)
            }
        }

        // Active scene renders into FBO A (fade instead of clear for trails).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        // Curl Flow always persists: it draws bare GL_POINTS, which need canvas
        // echo to read as streams rather than strobing dots, and SceneParams
        // .trails defaults to FALSE - so gating its persistence on the toggle
        // alone made the style strobe the moment it was selected from Styles
        // (only the one built-in "Streams" preset sets trails = true).
        // The toggle stays live all the same: CurlFlowMath.retention gives
        // Trails OFF a short, fixed echo (OFF_RETENTION, motion blur) and
        // Trails ON the whole Trail length slider remapped onto its long
        // streaming band. Every other scene keeps the plain toggle gate.
        val isCurl = scene is dev.musicviz.render.fluid.CurlFlowScene
        val persists = isCurl || (p.trails && scene is ParticleSceneBase)
        if (persists && !sceneJustSwitched) {
            val keep = if (isCurl) CurlFlowMath.retention(p.trailLength, p.trails) else p.trailLength
            if (p.trailZoom != 0f || p.trailWarp > 0f) {
                drawTrailWarp(p, keep, timeSeconds, dt)
            } else {
                // Retention^(dt*60): same look as the old per-frame constant
                // at 60 Hz, but trail length no longer halves on 120 Hz panels.
                drawFadeQuad(1f - (keep * 0.97f).pow(dt * 60f))
            }
        } else {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        // FlowField consumers on the active scene: CPU grid for particle
        // scenes (16x16 readback), uFlow sampler for shader scenes.
        if (p.flowEnabled && ff != null) {
            if (scene is ParticleSceneBase && p.flowAdvectParticles && ff.available) {
                ff.readback(ff.velocityTex, ff.flowScale, ff.aspect)
                scene.flowGrid = ff.cpuGrid
            } else if (scene is ParticleSceneBase) {
                scene.flowGrid = null
            }
            if (scene is ShaderScene) {
                scene.setFlow(if (ff.available) ff.velocityTex else zeroTex, p.flowStrength)
            }
        } else {
            (scene as? ParticleSceneBase)?.flowGrid = null
            (scene as? ShaderScene)?.setFlow(zeroTex, 0f)
        }
        scene.setParams(p)
        scene.update(gainAdjusted(features, p), dt)
        scene.draw(timeSeconds)

        // Composite to screen.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(compositeProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboA.tex)
        GLES30.glUniform1i(cLoc("uTexA"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboB.tex)
        GLES30.glUniform1i(cLoc("uTexB"), 1)
        // fluidWarp: bend the scene fetch through the flow field. The FLUID
        // scene contributes its own velocity texture; anything else uses the
        // FlowField service; a 1x1 zero texture keeps the sampler valid off.
        var flowTex = zeroTex
        var flowStrength = 0f
        if (p.flowEnabled) {
            val fluidScene = scene as? dev.musicviz.render.fluid.FluidScene
            if (fluidScene != null && fluidScene.simAvailable) {
                flowTex = fluidScene.velocityTexture
                flowStrength = p.flowStrength
            } else if (ff != null && ff.available) {
                flowTex = ff.velocityTex
                flowStrength = p.flowStrength
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flowTex)
        GLES30.glUniform1i(cLoc("uFlow"), 2)
        GLES30.glUniform1f(cLoc("uFlowStrength"), flowStrength)
        // F2 ripple overlay: refraction + glint over any style. The zero
        // texture keeps the sampler valid when the overlay is off or WATER
        // is active (whose own display already refracts - see step site).
        var rippleTex = zeroTex
        var rippleTexelW = 0f
        var rippleTexelH = 0f
        var rippleStrength = 0f
        var rippleSpecular = 0f
        if (rippleOverlayOn && ripple != null) {
            rippleTex = ripple.heightTex
            rippleTexelW = ripple.texelW
            rippleTexelH = ripple.texelH
            rippleStrength = p.rippleOverlayStrength.coerceIn(0f, 1f)
            rippleSpecular = p.rippleOverlaySpecular.coerceIn(0f, 1f)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rippleTex)
        GLES30.glUniform1i(cLoc("uRipple"), 3)
        GLES30.glUniform2f(cLoc("uRippleTexel"), rippleTexelW, rippleTexelH)
        GLES30.glUniform1f(cLoc("uRippleStrength"), rippleStrength)
        GLES30.glUniform1f(cLoc("uRippleSpecular"), rippleSpecular)
        GLES30.glUniform1f(cLoc("uProgress"), progress)
        val style = if (outgoingScene != null) transitionStyle else TransitionStyle.CUT
        GLES30.glUniform1i(cLoc("uStyle"), style.ordinal)
        val fx = lastFinalParams
        GLES30.glUniform1f(cLoc("uTime"), timeSeconds)
        GLES30.glUniform1f(cLoc("uBeat"), features.beatImpulse)
        GLES30.glUniform1f(cLoc("uChroma"), fx.chromaAb)
        GLES30.glUniform1f(cLoc("uVignette"), fx.vignette)
        GLES30.glUniform1f(cLoc("uScanline"), fx.scanlines)
        GLES30.glUniform1f(cLoc("uGrain"), fx.grain)
        GLES30.glUniform1f(cLoc("uGlitch"), fx.glitch)
        GLES30.glUniform1f(cLoc("uFisheye"), fx.fisheye)
        GLES30.glUniform1f(cLoc("uStrobe"), fx.strobe)
        // Universal geometric + color effects for scenes whose own pipeline
        // can't honor them (particles, milkdrop, the fluid family). Every
        // uPost* below is uploaded RAW; which groups actually run is decided
        // per TEXTURE by uGateA/uGateB (see the gate upload after this block),
        // because composite_frag routes the outgoing texture through the same
        // postFx() and a transition can cross scene families. Shader scenes
        // already apply all of these in-shader via view()/grade(), so their
        // gate is off - otherwise warp/ripple/kaleido/pixelate/tile/twist/
        // bloom/posterize would each apply TWICE (double warp strength, double
        // kaleido segmentation, double-quantized posterize, over-bloom).
        GLES30.glUniform1f(cLoc("uPostWarp"), fx.warp)
        GLES30.glUniform1f(cLoc("uPostRipple"), fx.ripple)
        GLES30.glUniform1f(cLoc("uPostSymmetry"), fx.symmetry.toFloat())
        GLES30.glUniform1f(cLoc("uPostKaleido"), if (fx.kaleidoscope) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostPixelate"), fx.pixelate)
        GLES30.glUniform1f(cLoc("uPostTile"), fx.tile)
        GLES30.glUniform1f(cLoc("uPostTwist"), fx.twist)
        GLES30.glUniform1f(cLoc("uPostBloom"), fx.bloom)
        GLES30.glUniform1f(cLoc("uPostPosterize"), fx.posterize)
        GLES30.glUniform1f(cLoc("uPostDriftX"), fx.driftX)
        GLES30.glUniform1f(cLoc("uPostDriftY"), fx.driftY)
        GLES30.glUniform1f(cLoc("uPostSway"), fx.sway)
        GLES30.glUniform1f(cLoc("uPostShake"), fx.shake)
        GLES30.glUniform1f(cLoc("uPostFlash"), fx.flash)
        GLES30.glUniform1f(cLoc("uPostTemp"), fx.temperature)
        GLES30.glUniform1f(cLoc("uPostSolarize"), if (fx.solarize) 1f else 0f)
        // Mirror/invert: shader scenes AND the milkdrop post pass handle these
        // themselves. Everything else needs them here - particle scenes (whose
        // fragment shader explicitly defers invert to this pass) and the fluid
        // family, which was previously excluded and so had no mirror/invert at
        // all. (Gate component y.)
        GLES30.glUniform1f(cLoc("uPostMirror"), if (fx.mirror) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostInvert"), if (fx.invert) 1f else 0f)
        // Universal grading + zoom/rotation (gate component z), a SMALLER set
        // of scenes than the geometry group: ShaderScene (view()/grade()), the
        // particle pipeline (particle_vert's uZoom/uRotation, particle_frag's
        // uSat/uBright/uContrast/uGamma) and the milkdrop post pass all apply
        // these in their OWN pass, so the gate is off for them - otherwise
        // every one of them would be zoomed twice and graded twice (squared
        // brightness, doubled contrast). Only scenes that grade nothing
        // themselves - the fluid family (Fluid, Curl Flow, Water) - are graded
        // here, which is what made Zoom/Rotation/Saturation/Brightness/
        // Contrast/Gamma/Hue/Intensity dead on those styles. The gate switches
        // the shader block off wholesale, so the off case is an exact no-op.
        GLES30.glUniform1f(cLoc("uPostZoom"), fx.zoom)
        GLES30.glUniform1f(cLoc("uPostRotation"), postRotationAngle)
        GLES30.glUniform1f(cLoc("uPostSat"), fx.saturation)
        GLES30.glUniform1f(cLoc("uPostBright"), CompositeGrade.brightness(fx.brightness, fx.intensity))
        GLES30.glUniform1f(cLoc("uPostContrast"), fx.contrast)
        GLES30.glUniform1f(cLoc("uPostGamma"), fx.gamma)
        GLES30.glUniform1f(cLoc("uPostHue"), fx.colorShift + postCyclePhase)
        // "Beat pulse": gate component w, a DIFFERENT set from the grade on
        // purpose. Only two scene families read SceneParams.pulse themselves -
        // ShaderScene (uPulse, folded into view()'s zoom) and the particle
        // pipeline (a uSize swell). ProjectMScene is in the grading exclusion
        // set but NOT this one: the milkdrop post pass grades and zooms, yet
        // nothing in it or in pm_post_frag reads pulse, so before this upload
        // the slider was inert on MilkDrop exactly as on the fluid family.
        GLES30.glUniform1f(cLoc("uPostPulse"), CompositeGrade.pulseAmount(fx.pulse, postBeatPulse))
        // One gate per texture. uTexA is the ACTIVE scene, uTexB the OUTGOING
        // one, and they can belong to different families for the whole length
        // of a cross-family transition: gating both from the active scene
        // graded the outgoing julia frame a second time on a julia -> fluid
        // fade (white, over-zoomed flash) and dropped the outgoing fluid grade
        // on the reverse. Outside a transition uTexB is unread (uStyle = CUT),
        // so it simply carries the active gate.
        val gateA = CompositeGrade.gateFor(compositeFamily(activeScene))
        val gateB = CompositeGrade.gateFor(compositeFamily(outgoingScene ?: activeScene))
        GLES30.glUniform4fv(cLoc("uGateA"), 1, gateA.toVec4(), 0)
        GLES30.glUniform4fv(cLoc("uGateB"), 1, gateB.toVec4(), 0)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Which composite-pass gate a scene falls under. The fluid family (Fluid,
     * Curl Flow, Water) is the `else` branch: it has no pass of its own, so
     * the composite owns every group for it.
     */
    private fun compositeFamily(scene: Scene?): CompositeGrade.SceneFamily =
        when (scene) {
            is ShaderScene -> CompositeGrade.SceneFamily.SHADER
            is ParticleSceneBase -> CompositeGrade.SceneFamily.PARTICLE
            is ProjectMScene -> CompositeGrade.SceneFamily.MILKDROP
            else -> CompositeGrade.SceneFamily.FLUID
        }

    /**
     * Feedback-trail warp: copies the persisted frame aside, then redraws it
     * into the scene FBO slightly zoomed/warped and decayed (blend off - the
     * resample is the new base). Falls back to the plain fade when the trail
     * buffer can't be sized.
     *
     * [retention] is the caller's frame-retention factor, normally
     * `p.trailLength` but remapped for styles with their own persistence band
     * (see CurlFlowMath), so the warp path decays at the same rate as the plain
     * fade path.
     */
    private fun drawTrailWarp(
        p: SceneParams,
        retention: Float,
        timeSeconds: Float,
        dt: Float,
    ) {
        ensureTrailBuffer()
        if (trailFbo == 0) {
            drawFadeQuad(1f - (retention * 0.97f).pow(dt * 60f))
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fboA.fbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, trailFbo)
        GLES30.glBlitFramebuffer(
            0,
            0,
            renderWidth,
            renderHeight,
            0,
            0,
            renderWidth,
            renderHeight,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(trailWarpProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex)

        fun tLoc(n: String) = trailLocs.getOrPut(n) { GLES30.glGetUniformLocation(trailWarpProgram, n) }
        GLES30.glUniform1i(tLoc("uPrev"), 0)
        // [retention], NOT p.trailLength: styles with their own persistence
        // band (Curl Flow) hand in a remapped value, and reading the raw
        // slider here made the warp path decay faster than the fade path -
        // Curl Flow's streams broke into strobing dots the moment Trail zoom
        // or Trail warp went non-zero.
        GLES30.glUniform1f(tLoc("uDecay"), CurlFlowMath.warpDecay(retention, dt))
        GLES30.glUniform1f(tLoc("uZoom"), p.trailZoom)
        GLES30.glUniform1f(tLoc("uWarp"), p.trailWarp)
        GLES30.glUniform1f(tLoc("uTime"), timeSeconds)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun ensureTrailBuffer() {
        if (trailTex != 0 && trailW == renderWidth && trailH == renderHeight) return
        if (trailTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(trailTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(trailFbo), 0)
            trailTex = 0
            trailFbo = 0
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        trailTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            renderWidth,
            renderHeight,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glGenFramebuffers(1, ids, 0)
        trailFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, trailTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteTextures(1, intArrayOf(trailTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(trailFbo), 0)
            trailTex = 0
            trailFbo = 0
        }
        trailW = renderWidth
        trailH = renderHeight
    }

    private var trailW = 0
    private var trailH = 0

    private fun drawFadeQuad(alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(fadeProgram)
        GLES30.glUniform1f(
            fadeLocs.getOrPut("uFadeAlpha") { GLES30.glGetUniformLocation(fadeProgram, "uFadeAlpha") },
            alpha.coerceIn(0.02f, 1f),
        )
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun loadRaw(resId: Int): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }

    /**
     * Builds fresh scene instances for the export GL context. Never reuses
     * live-context objects: GL handles are not shareable across contexts.
     */
    fun exportSceneFactory(sceneId: String): VideoExporter.SceneFactory =
        object : VideoExporter.SceneFactory {
            override fun create(): Scene {
                val exportParams = sceneParams
                val particleShaders = particleShaderSources(context)
                val quadVert = loadRaw(R.raw.quad_vert)
                val scene: Scene =
                    when {
                        sceneId == SceneIds.FLUID ->
                            dev.musicviz.render.fluid.FluidScene(context).also {
                                it.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
                            }
                        sceneId == SceneIds.CURLFLOW ->
                            dev.musicviz.render.fluid
                                .CurlFlowScene(context)
                        sceneId == SceneIds.WATER ->
                            dev.musicviz.render.fluid
                                .WaterScene(context)
                        sceneId == SceneIds.MILKDROP && PMBridge.available ->
                            ProjectMScene(
                                postVertexSrc = loadRaw(R.raw.fade_vert),
                                postFragmentSrc = loadRaw(R.raw.pm_post_frag),
                                sharedTextureDir = File(context.filesDir, "milk/textures").absolutePath,
                                // null -> the scene feeds itself from the export
                                // timeline's per-frame waveform in update().
                                pcmProvider = { null },
                            ).also { pm ->
                                // Without this the export renders projectM's
                                // default idle preset instead of what's on
                                // screen.
                                lastMilkPreset?.let { pm.queuePreset(it) }
                            }
                        sceneId == SceneIds.BURSTS -> BurstScene(particleShaders)
                        sceneId == SceneIds.SWARM -> SwarmScene(particleShaders)
                        sceneId == SceneIds.FOUNTAIN -> FountainScene(particleShaders)
                        sceneId == SceneIds.ORBITS -> OrbitScene(particleShaders)
                        SHADER_SCENES.containsKey(sceneId) ->
                            ShaderScene(sceneId, quadVert, activeCustomShaders[sceneId] ?: loadRaw(SHADER_SCENES.getValue(sceneId)))
                        else -> NebulaScene(particleShaders)
                    }
                scene.setParams(exportParams)
                return scene
            }
        }

    private fun particleShaderSources(context: Context): ParticleSceneBase.ShaderSources =
        ParticleSceneBase.ShaderSources(loadRaw(R.raw.particle_vert), loadRaw(R.raw.particle_frag))
}
