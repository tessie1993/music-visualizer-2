package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.export.VideoExporter
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

/**
 * Multi-scene GL ES 3.0 renderer with an offscreen pipeline: the active scene
 * renders into FBO A; during a transition the outgoing scene renders into
 * FBO B and a compositor shader blends them (Cut/Fade/Melt). Trails work by
 * fading FBO A instead of clearing it. Scene switching, params and shader
 * edits are queued from other threads and applied on the GL thread; all GL
 * resources are (re)created in [onSurfaceCreated].
 */
class VisualizerRenderer(private val context: Context) : GLSurfaceView.Renderer {
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

    /** Assignable LFO modulation, evaluated per frame after smoothing. */
    val lfoEngine = LfoEngine()

    /** Beat-triggered ADSR envelope, applied after the LFOs. */
    val adsrEngine = AdsrEngine()

    /** Final params of the current frame (after fade + LFO), for the composite FX pass. */
    private var lastFinalParams: SceneParams = SceneParams.DEFAULT

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
            flowStrength = f(from.flowStrength, to.flowStrength),
            flowForce = f(from.flowForce, to.flowForce),
            flowCurl = f(from.flowCurl, to.flowCurl),
        )
    }

    private fun gainAdjusted(
        f: dev.musicviz.analysis.AudioFeatures,
        p: SceneParams,
    ): dev.musicviz.analysis.AudioFeatures = dev.musicviz.render.scene.applyBandGains(f, p)

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

    /** Fresh mono PCM for projectM; set by the UI wiring. */
    @Volatile
    var pcmProvider: () -> PcmChunk? = { null }

    val milkdropAvailable: Boolean get() = PMBridge.available

    private val scenes = LinkedHashMap<String, Scene>()
    private var activeScene: Scene? = null
    private var outgoingScene: Scene? = null
    private var transitionStartMs = 0L
    private var width = 1
    private var height = 1
    private var renderWidth = 1
    private var renderHeight = 1
    private var lastFrameMs = 0L
    private var timeSeconds = 0f
    private var fadeProgram = 0
    private var compositeProgram = 0

    /** Uniform locations cached per program link; ~30 glGetUniformLocation
     *  calls per frame are measurable driver overhead on mobile GPUs. */
    private val compositeLocs = HashMap<String, Int>()

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
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
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

    // Milk preset requests are queued and applied on the GL thread: the
    // `scenes` map is cleared/repopulated by onSurfaceCreated on the GL
    // thread, so reading it from the UI thread races surface recreation
    // (a load could land on a just-released stale ProjectMScene).
    private val milkLock = Any()
    private var pendingMilkLoad: String? = null
    private var pendingMilkReload = false

    fun loadMilkPreset(path: String) {
        lastMilkPreset = path
        synchronized(milkLock) { pendingMilkLoad = path }
    }

    /** Re-queues the currently loaded preset so newly added textures apply. */
    fun reloadCurrentMilkPreset() {
        synchronized(milkLock) { pendingMilkReload = true }
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        scenes.values.forEach { it.release() }
        scenes.clear()
        fboA.release()
        fboB.release()
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

        // FlowField service (F7) + the always-valid zero flow texture.
        flowField?.release()
        flowField = dev.musicviz.render.fluid.FlowField(context).also { it.create() }
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        zeroTex = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, zeroTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val zero = java.nio.ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(0, 0, 0, 0)).position(0) }
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, zero,
        )

        fadeProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.fade_frag))
        compositeProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.composite_frag))
        compositeLocs.clear()
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
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
        lastFrameMs = now
        timeSeconds += dt

        while (true) {
            val (sceneId, src) = pendingCustomShaders.poll() ?: break
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        val (milkLoad, milkReload) =
            synchronized(milkLock) {
                val r = pendingMilkLoad to pendingMilkReload
                pendingMilkLoad = null
                pendingMilkReload = false
                r
            }
        milkLoad?.let { (scenes[SceneIds.MILKDROP] as? ProjectMScene)?.queuePreset(it) }
        if (milkReload) (scenes[SceneIds.MILKDROP] as? ProjectMScene)?.reloadCurrent()
        val requested = scenes[requestedSceneId]
        var sceneJustSwitched = false
        if (requested != null && requested !== activeScene) {
            if (transitionStyle != TransitionStyle.CUT && activeScene != null) {
                outgoingScene = activeScene
                transitionStartMs = now
            }
            activeScene = requested
            sceneJustSwitched = true
        }
        val scene = activeScene ?: return
        // Settings fade: exponentially approach the target params so preset
        // and slider changes glide instead of jumping. Toggles/choices snap.
        val fade = sceneParams.paramFadeSec
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
        fboA.ensure(renderWidth, renderHeight)
        fboB.ensure(renderWidth, renderHeight)

        var progress = 1f
        val outgoing = outgoingScene
        if (outgoing != null) {
            progress = ((now - transitionStartMs).toFloat() / transitionDurationMs).coerceIn(0f, 1f)
            if (progress >= 1f) {
                outgoingScene = null
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB.fbo)
                GLES30.glViewport(0, 0, renderWidth, renderHeight)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                outgoing.setParams(p)
                outgoing.update(gainAdjusted(features, p), dt)
                outgoing.draw(timeSeconds)
            }
        }

        // Active scene renders into FBO A (fade instead of clear for trails).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        if (p.trails && scene is ParticleSceneBase && !sceneJustSwitched) {
            drawFadeQuad(1f - p.trailLength * 0.97f)
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
        GLES30.glUniform1f(cLoc("uProgress"), progress)
        val style = if (outgoingScene != null) transitionStyle else TransitionStyle.CUT
        GLES30.glUniform1i(cLoc("uStyle"), style.ordinal)
        val fx = lastFinalParams
        GLES30.glUniform1f(cLoc("uTime"), timeSeconds)
        GLES30.glUniform1f(cLoc("uBeat"), if (features.beat) 1f else 0f)
        GLES30.glUniform1f(cLoc("uChroma"), fx.chromaAb)
        GLES30.glUniform1f(cLoc("uVignette"), fx.vignette)
        GLES30.glUniform1f(cLoc("uScanline"), fx.scanlines)
        GLES30.glUniform1f(cLoc("uGrain"), fx.grain)
        GLES30.glUniform1f(cLoc("uGlitch"), fx.glitch)
        GLES30.glUniform1f(cLoc("uFisheye"), fx.fisheye)
        GLES30.glUniform1f(cLoc("uStrobe"), fx.strobe)
        // Universal geometric + color effects for scenes whose own pipeline
        // can't honor them (particles, milkdrop). Shader scenes already apply
        // ALL of these in-shader via view()/grade(), so for them we pass
        // neutral values - otherwise warp/ripple/kaleido/pixelate/tile/twist/
        // bloom/posterize would each apply TWICE (double warp strength, double
        // kaleido segmentation, double-quantized posterize, over-bloom).
        val applyGeo = activeScene !is ShaderScene

        fun geoF(v: Float) = if (applyGeo) v else 0f
        GLES30.glUniform1f(cLoc("uPostWarp"), geoF(fx.warp))
        GLES30.glUniform1f(cLoc("uPostRipple"), geoF(fx.ripple))
        GLES30.glUniform1f(cLoc("uPostSymmetry"), fx.symmetry.toFloat())
        GLES30.glUniform1f(
            cLoc("uPostKaleido"),
            if (applyGeo && fx.kaleidoscope) 1f else 0f,
        )
        GLES30.glUniform1f(cLoc("uPostPixelate"), geoF(fx.pixelate))
        GLES30.glUniform1f(cLoc("uPostTile"), geoF(fx.tile))
        GLES30.glUniform1f(cLoc("uPostTwist"), geoF(fx.twist))
        GLES30.glUniform1f(cLoc("uPostBloom"), geoF(fx.bloom))
        GLES30.glUniform1f(cLoc("uPostPosterize"), geoF(fx.posterize))
        GLES30.glUniform1f(cLoc("uPostDriftX"), geoF(fx.driftX))
        GLES30.glUniform1f(cLoc("uPostDriftY"), geoF(fx.driftY))
        GLES30.glUniform1f(cLoc("uPostSway"), geoF(fx.sway))
        GLES30.glUniform1f(cLoc("uPostShake"), geoF(fx.shake))
        GLES30.glUniform1f(cLoc("uPostFlash"), geoF(fx.flash))
        GLES30.glUniform1f(cLoc("uPostTemp"), geoF(fx.temperature))
        GLES30.glUniform1f(
            cLoc("uPostSolarize"),
            if (applyGeo && fx.solarize) 1f else 0f,
        )
        // Mirror/invert: shader scenes AND the milkdrop post pass handle these
        // themselves; only particle scenes need them here.
        val isParticle = activeScene is ParticleSceneBase
        GLES30.glUniform1f(cLoc("uPostMirror"), if (isParticle && fx.mirror) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostInvert"), if (isParticle && fx.invert) 1f else 0f)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun drawFadeQuad(alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(fadeProgram)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fadeProgram, "uFadeAlpha"), alpha.coerceIn(0.02f, 1f))
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun loadRaw(resId: Int): String = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }

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
