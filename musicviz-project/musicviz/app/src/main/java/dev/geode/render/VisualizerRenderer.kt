package dev.geode.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.geode.R
import dev.geode.analysis.AudioFeatures
import dev.geode.export.VideoExporter
import dev.geode.render.fluid.CurlFlowMath
import dev.geode.render.scene.AcidScene
import dev.geode.render.scene.BeamScene
import dev.geode.render.scene.CymaticsScene
import dev.geode.render.scene.GlUtil
import dev.geode.render.scene.HyperspaceScene
import dev.geode.render.scene.LifeScene
import dev.geode.render.scene.MilkdropEngine
import dev.geode.render.scene.MilkdropScene
import dev.geode.render.scene.MycoScene
import dev.geode.render.scene.PcmChunk
import dev.geode.render.scene.PcmSink
import dev.geode.render.scene.Scene
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams
import dev.geode.render.scene.ShaderScene
import dev.geode.render.scene.SilkScene
import dev.geode.render.scene.VisualStyleCatalog
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.pow

private typealias CompositeProgram = GlUtil.UniformCache

class VisualizerRenderer(
    private val context: Context,
) : GLSurfaceView.Renderer {
    companion object {
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
                SceneIds.WINTER to R.raw.winter_frag,
                SceneIds.LAVA to R.raw.lava_frag,
            )

        private const val TOUCH_RADIUS = 0.11f

        private const val MAX_TOUCH_BACKLOG = 24

        private const val TOUCH_LINGER_MS = 2_500L

        private const val TOUCH_MIN_OVERLAY_STRENGTH = 0.35f

        private const val TIME_WRAP_SEC = 7100f

        const val STYLE_LAYER = 6

        val NOT_FADED: Map<String, String> =
            mapOf(
                "paramFadeSec" to "the fade's own time constant: gliding it would make every fade chase a moving target",
                "paletteBaseOverride" to "UNSET_OVERRIDE (-1) is a sentinel: a glide through it flickers between set and unset",
                "paletteRangeOverride" to "UNSET_OVERRIDE sentinel, as paletteBaseOverride",
                "palette2BaseOverride" to "UNSET_OVERRIDE sentinel, as paletteBaseOverride",
                "palette2RangeOverride" to "UNSET_OVERRIDE sentinel, as paletteBaseOverride",
            )

        private val LERPED_FLOATS: Array<java.lang.reflect.Field> =
            SceneParams::class.java
                .declaredFields
                .filter { it.type == Float::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filterNot { it.name in NOT_FADED }
                .onEach { it.isAccessible = true }
                .toTypedArray()

        internal fun lerpParams(
            from: SceneParams,
            to: SceneParams,
            k: Float,
        ): SceneParams {
            val out = to.copy()
            for (field in LERPED_FLOATS) {
                val a = field.getFloat(from)
                field.setFloat(out, a + (field.getFloat(to) - a) * k)
            }
            return out
        }
    }

    @Volatile
    var features: AudioFeatures = AudioFeatures.empty()

    @Volatile
    var requestedSceneId: String = SceneIds.DEFAULT

    @Volatile
    var sceneParams: SceneParams = SceneParams.DEFAULT

    @Volatile
    var safety: VisualSafety.SafetyConfig = VisualSafety.SafetyConfig.OFF

    private fun flashGain(
        fx: SceneParams,
        beatImpulse: Float,
    ): Float {
        val gain = flashBudget.gainFor(timeSeconds, VisualSafety.flashImpulse(fx.flash, beatImpulse))
        return if (safety.enabled) gain else 1f
    }

    private var displayedParams: SceneParams = SceneParams.DEFAULT

    @Volatile
    private var morphFadeSec = 0f

    @Volatile
    private var morphRemainSec = 0f

    fun beginParamMorph(seconds: Float) {
        if (seconds <= 0f) return
        morphFadeSec = seconds
        morphRemainSec = seconds * 3f
    }

    val lfoEngine = LfoEngine()

    val adsrEngine = AdsrEngine()

    private val envRateOffsets = FloatArray(3)
    private val envDepthOffsets = FloatArray(3)

    private var lastFinalParams: SceneParams = SceneParams.DEFAULT

    private var postRotationAngle = 0f

    private var postCyclePhase = 0f

    private var postBeatPulse = 0f

    private fun gainAdjusted(
        f: dev.geode.analysis.AudioFeatures,
        p: SceneParams,
    ): dev.geode.analysis.AudioFeatures =
        dev.geode.render.scene
            .applyBandGains(f, p)

    @Volatile
    var layerSceneId: String? = null

    @Volatile
    var layerMix: Float = 0.5f

    @Volatile
    var layerBlend: BlendMode = BlendMode.SCREEN

    @Volatile
    var transitionStyle: TransitionStyle = TransitionStyle.FADE

    @Volatile
    var transitionDurationMs: Long = 1200

    @Volatile
    var onShaderError: (String?) -> Unit = {}

    var onMilkPresetLoaded: (String) -> Unit = {}

    private val pendingCustomShaders = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    @Volatile
    private var lastMilkPreset: String? = null

    @Volatile
    private var milkdropScene: MilkdropScene? = null
    private val activeCustomShaders = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var fluidForceSrc: String? = null

    @Volatile
    private var fluidDyeSrc: String? = null

    @Volatile
    private var fluidInjectionDirty = false

    fun submitFluidInjectionShaders(
        force: String?,
        dye: String?,
    ) {
        fluidForceSrc = force
        fluidDyeSrc = dye
        fluidInjectionDirty = true
    }

    private var flowField: dev.geode.render.fluid.FlowField? = null
    private var zeroTex = 0

    private var rippleOverlay: dev.geode.render.fluid.RippleSim? = null
    private val rippleDrops =
        dev.geode.render.fluid
            .RippleOverlayDrops()

    private val rippleOverlayRes = 256

    private class TouchStroke(
        val nx: Float,
        val ny: Float,
        val ndx: Float,
        val ndy: Float,
        val dt: Float,
        val strength: Float,
    )

    private val touchStrokes = java.util.concurrent.ConcurrentLinkedQueue<TouchStroke>()

    @Volatile
    private var lastTouchMs = 0L

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) {
        if (strength <= 0f) return
        if (touchStrokes.size >= MAX_TOUCH_BACKLOG) return
        lastTouchMs = SystemClock.elapsedRealtime()
        touchStrokes.add(TouchStroke(nx, ny, ndx, ndy, dt, strength))
    }

    @Volatile
    var pcmProvider: () -> PcmChunk? = { null }

    private var framePcm: PcmChunk? = null
    private var framePcmDrained = false

    private fun deliverPcm(sink: PcmSink) {
        if (!framePcmDrained) {
            framePcmDrained = true
            framePcm = pcmProvider()
        }
        val chunk = framePcm ?: return
        if (chunk.count > 0) sink.acceptPcm(chunk.data, chunk.count)
    }

    val milkdropAvailable: Boolean get() = MilkdropEngine.available

    private val scenes = LinkedHashMap<String, Scene>()
    private var activeScene: Scene? = null
    private var outgoingScene: Scene? = null

    private var layerScene: Scene? = null

    private var outgoingParams: SceneParams? = null
    private var transitionStartMs = 0L
    private var width = 1
    private var height = 1
    private var renderWidth = 1
    private var renderHeight = 1
    private var lastFrameMs = 0L

    private var timeSeconds = 0f

    private val flashBudget = FlashBudget()
    private var fadeProgram = 0
    private var trailWarpProgram = 0

    private val trail = RenderTarget("trail")
    private var compositeProgram = CompositeProgram(0)

    private var baseCompositeProgram = CompositeProgram(0)

    @Volatile
    var transitionId: String = TransitionStyle.FADE.name.lowercase()

    private val transitionPrograms = LinkedHashMap<String, CompositeProgram>()

    private var activeTransition: TransitionCatalog.Def? = null

    private var uploadedTransitionFor: CompositeProgram? = null
    private var uploadedTransitionDef: TransitionCatalog.Def? = null

    private var compositeSource: String = ""
    private var fadeUniforms = GlUtil.UniformCache(0)
    private var trailUniforms = GlUtil.UniformCache(0)

    private fun cLoc(name: String): Int = compositeProgram.loc(name)

    private val maxTransitionPrograms = 4

    private fun transitionProgram(id: String): CompositeProgram {
        if (TransitionCatalog.builtIn(id) != null) return baseCompositeProgram
        transitionPrograms[id]?.let {
            transitionPrograms.remove(id)
            transitionPrograms[id] = it
            return it
        }
        val def = TransitionCatalog.definition(context, id) ?: return baseCompositeProgram
        val program =
            runCatching {
                CompositeProgram(
                    GlUtil.buildProgram(
                        GlUtil.loadShader(context, R.raw.fade_vert),
                        TransitionCatalog.spliceInto(compositeSource, def),
                    ),
                )
            }.getOrElse {
                android.util.Log.w("Transitions", "\"$id\" failed to link: ${it.message}")
                return baseCompositeProgram
            }
        while (transitionPrograms.size >= maxTransitionPrograms) {
            val oldest = transitionPrograms.keys.first()
            transitionPrograms.remove(oldest)?.let { p -> GLES30.glDeleteProgram(p.program) }
        }
        transitionPrograms[id] = program
        return program
    }

    fun warmTransition(id: String) {
        if (compositeSource.isNotEmpty()) transitionProgram(id)
    }

    private var quadVao = 0

    private var noiseTex = 0

    private var paletteLutTex = 0

    private var buildableIds: Set<String> = emptySet()
    private val fboA = RenderTarget("sceneA")
    private val fboB = RenderTarget("sceneB")

    fun availableSceneIds(): List<String> =
        buildList {
            addAll(VisualStyleCatalog.silkIds)
            addAll(VisualStyleCatalog.lifeIds)
            addAll(VisualStyleCatalog.mycoIds)
            addAll(VisualStyleCatalog.acidIds)
            addAll(SHADER_SCENES.keys)
            if (MilkdropEngine.available) add(SceneIds.MILKDROP)
            add(SceneIds.FLUID)
            add(SceneIds.CURLFLOW)
            add(SceneIds.WATER)
            addAll(VisualStyleCatalog.cymaticsIds)
            add(SceneIds.BEAM)
            addAll(VisualStyleCatalog.hyperspaceIds)
        }

    private fun createScene(
        id: String,
        quadVert: String,
        export: Boolean = false,
    ): Scene {
        return SHADER_SCENES[id]?.let { res ->
            val frag = if (export) activeCustomShaders[id] ?: GlUtil.loadShader(context, res) else GlUtil.loadShader(context, res)
            ShaderScene(
                id,
                quadVert,
                frag,
                onError = { onShaderError(it) },
                onUserSourceCompiled = { compiled -> activeCustomShaders[id] = compiled },
            )
        }
            ?: VisualStyleCatalog.cymatics(id)?.let { style ->
                CymaticsScene(context, style).also { plate ->
                    plate.onShaderError = { onShaderError(it) }
                }
            }
            ?: VisualStyleCatalog.silk(id)?.let { style ->
                SilkScene(context, style).also { scene ->
                    scene.onShaderError = { onShaderError(it) }
                }
            }
            ?: VisualStyleCatalog.life(id)?.let { style ->
                LifeScene(context, style).also { scene ->
                    scene.onShaderError = { onShaderError(it) }
                }
            }
            ?: VisualStyleCatalog.acid(id)?.let { style ->
                AcidScene(context, style).also { scene ->
                    scene.onShaderError = { onShaderError(it) }
                }
            }
            ?: VisualStyleCatalog.myco(id)?.let { style ->
                MycoScene(context, style).also { scene ->
                    scene.onShaderError = { onShaderError(it) }
                }
            }
            ?: VisualStyleCatalog.hyperspace(id)?.let { style ->
                HyperspaceScene(context, style).also { hyper ->
                    hyper.onShaderError = { onShaderError(it) }
                }
            }
            ?: when (id) {
                SceneIds.FLUID ->
                    dev.geode.render.fluid.FluidScene(context).also { fluid ->
                        fluid.onShaderError = { onShaderError(it) }
                    }
                SceneIds.CURLFLOW ->
                    dev.geode.render.fluid.CurlFlowScene(context).also { curl ->
                        curl.onShaderError = { onShaderError(it) }
                    }
                SceneIds.WATER ->
                    dev.geode.render.fluid.WaterScene(context).also { water ->
                        water.onShaderError = { onShaderError(it) }
                    }
                SceneIds.BEAM ->
                    BeamScene(context).also { beam ->
                        beam.onShaderError = { onShaderError(it) }
                    }
                SceneIds.MILKDROP ->
                    MilkdropScene(
                        postVertexSrc = GlUtil.loadShader(context, R.raw.fade_vert),
                        postFragmentSrc = GlUtil.loadShader(context, R.raw.pm_post_frag),
                        sharedTextureDir = File(context.filesDir, "milk/textures").absolutePath,
                        onError = { onShaderError(it) },
                        onPresetLoaded = { onMilkPresetLoaded(it) },
                    )
                else -> error("availableSceneIds offers \"$id\" but createScene cannot build it")
            }
    }

    private fun sceneFor(id: String): Scene? {
        scenes[id]?.let { return it }
        if (id !in buildableIds) return null
        return buildScene(id)
    }

    private fun buildScene(id: String): Scene {
        val scene = createScene(id, GlUtil.loadShader(context, R.raw.quad_vert))
        wireScene(scene)
        scene.init()
        scene.setParams(sceneParams)
        scene.resize(renderWidth, renderHeight)
        activeCustomShaders[id]?.let { (scene as? ShaderScene)?.setFragmentSource(it) }
        if (scene is MilkdropScene) {
            milkdropScene = scene
            scene.setWindowSize(width, height)
            lastMilkPreset?.let { scene.queuePreset(it) }
        }
        if (scene is dev.geode.render.fluid.FluidScene && (fluidForceSrc != null || fluidDyeSrc != null)) {
            scene.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
        }
        scenes[id] = scene
        return scene
    }

    private fun wireScene(scene: Scene) {
        if (scene is ShaderScene && paletteLutTex != 0) {
            scene.setPaletteLut(paletteLutTex)
        }
    }

    fun submitShader(
        sceneId: String,
        fragmentSrc: String,
    ) {
        pendingCustomShaders.add(sceneId to fragmentSrc)
    }

    fun customShaderFor(sceneId: String): String? = activeCustomShaders[sceneId]

    fun loadMilkPreset(path: String) {
        lastMilkPreset = path
        milkdropScene?.queuePreset(path)
    }

    fun reloadCurrentMilkPreset() {
        milkdropScene?.reloadCurrent()
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        milkdropScene = null
        scenes.values.forEach { it.release() }
        scenes.clear()
        fboA.release()
        fboB.release()
        trail.forget()
        if (noiseTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(noiseTex), 0)
            noiseTex = 0
        }
        if (paletteLutTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(paletteLutTex), 0)
            paletteLutTex = 0
        }
        buildableIds = availableSceneIds().toSet()
        if (fluidForceSrc != null || fluidDyeSrc != null) fluidInjectionDirty = true
        activeScene = sceneFor(requestedSceneId) ?: sceneFor(SceneIds.DEFAULT)
        outgoingScene = null
        outgoingParams = null

        flowField?.release()
        flowField =
            dev.geode.render.fluid
                .FlowField(context)
                .also { it.create() }
        rippleOverlay?.release()
        rippleOverlay =
            dev.geode.render.fluid
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

        noiseTex = BlueNoise.createTexture(context)
        paletteLutTex = CyclicPalettes.createTexture(context)
        scenes.values.filterIsInstance<ShaderScene>().forEach { it.setPaletteLut(paletteLutTex) }
        val fadeVert = GlUtil.loadShader(context, R.raw.fade_vert)
        fadeProgram = GlUtil.buildProgram(fadeVert, GlUtil.loadShader(context, R.raw.fade_frag))
        trailWarpProgram = GlUtil.buildProgram(fadeVert, GlUtil.loadShader(context, R.raw.trail_warp_frag))
        compositeSource = GlUtil.loadShader(context, R.raw.composite_frag)
        baseCompositeProgram = CompositeProgram(GlUtil.buildProgram(fadeVert, compositeSource))
        compositeProgram = baseCompositeProgram
        transitionPrograms.clear()
        activeTransition = null
        uploadedTransitionFor = null
        uploadedTransitionDef = null
        fadeUniforms = GlUtil.UniformCache(fadeProgram)
        trailUniforms = GlUtil.UniformCache(trailWarpProgram)
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
        val ss = supersampleFactor(width, height)
        renderWidth = (width * ss).toInt()
        renderHeight = (height * ss).toInt()
        scenes.values.forEach { it.resize(renderWidth, renderHeight) }
        milkdropScene?.setWindowSize(width, height)
        flowField?.resize(renderWidth, renderHeight)
        rippleOverlay?.resize(renderWidth, renderHeight)
        fboA.ensure(renderWidth, renderHeight)
        fboB.ensure(renderWidth, renderHeight)
    }

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
        framePcmDrained = false
        framePcm = null
        GlUtil.resetFrameState()
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
        lastFrameMs = now
        timeSeconds = (timeSeconds + dt) % TIME_WRAP_SEC

        while (true) {
            val (sceneId, src) = pendingCustomShaders.poll() ?: break
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        val requested = sceneFor(requestedSceneId)
        var sceneJustSwitched = false
        if (requested != null && requested !== activeScene) {
            val afterBuildMs = SystemClock.elapsedRealtime()
            lastFrameMs = afterBuildMs
            val cuts = TransitionCatalog.builtIn(transitionId) == TransitionStyle.CUT
            if (!cuts && activeScene != null) {
                outgoingScene = activeScene
                outgoingParams = lastFinalParams
                transitionStartMs = afterBuildMs
            }
            activeScene = requested
            sceneJustSwitched = true
        }
        val scene = activeScene ?: return
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
        val envValues = adsrEngine.tick(dt, features)
        AdsrEngine.lfoOffsets(adsrEngine.configs, envValues, envRateOffsets, envDepthOffsets)
        val lfoValues = lfoEngine.tick(dt, features.bpm, envRateOffsets, envDepthOffsets, safety)
        var p = LfoEngine.apply(displayedParams, lfoEngine.configs, lfoValues)
        p = AdsrEngine.apply(p, adsrEngine.configs, envValues)
        p = VisualSafety.apply(p, safety)
        lastFinalParams = p
        postRotationAngle = CompositeGrade.integrateRotation(postRotationAngle, p.rotation, dt)
        postCyclePhase = CompositeGrade.integrateCyclePhase(postCyclePhase, p.cycleSpeed, dt, p.colorCycle)
        postBeatPulse = CompositeGrade.integrateBeatPulse(postBeatPulse, features.motionImpulse, dt)
        if (fluidInjectionDirty) {
            (scenes[SceneIds.FLUID] as? dev.geode.render.fluid.FluidScene)?.let { fluid ->
                fluidInjectionDirty = false
                fluid.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
            }
        }
        val ff = flowField
        val fluidActive = scene is dev.geode.render.fluid.FluidScene
        layerScene =
            if (outgoingScene != null) {
                null
            } else {
                layerSceneId
                    ?.takeIf { it != requestedSceneId }
                    ?.let { sceneFor(it) }
                    ?.takeIf { it !== activeScene }
            }
        val wantsFlow = p.flowEnabled && !fluidActive
        if (wantsFlow && ff != null && ff.available) {
            ff.step(gainAdjusted(features, p), dt, p)
        }
        val ripple = rippleOverlay
        val waterActive = scene is dev.geode.render.fluid.WaterScene
        val smearing = now - lastTouchMs < TOUCH_LINGER_MS
        drainTouchStrokes(scene, ripple)
        val rippleOverlayOn =
            (p.rippleOverlayEnabled || smearing) && ripple != null && ripple.available && !waterActive
        if (rippleOverlayOn) {
            ripple.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
            ripple.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
            rippleDrops.tick(gainAdjusted(features, p), ripple.aspect) { x, y, radius, amp ->
                ripple.queueDrop(x, y, radius, amp)
            }
            ripple.step(dt)
        }
        if (!fboA.ensure(renderWidth, renderHeight)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        if (!fboB.ensure(renderWidth, renderHeight)) {
            layerScene = null
            outgoingScene = null
            outgoingParams = null
        }

        var progress = 1f
        val outgoing = outgoingScene
        val layer = layerScene
        if (layer != null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB.fbo)
            GLES30.glViewport(0, 0, renderWidth, renderHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            wireFlowConsumers(layer, ff, p)
            layer.setParams(p)
            (layer as? PcmSink)?.let { deliverPcm(it) }
            layer.update(gainAdjusted(features, p), dt)
            layer.draw(timeSeconds)
        }
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
                (outgoing as? PcmSink)?.let { deliverPcm(it) }
                outgoing.update(gainAdjusted(features, op), dt)
                outgoing.draw(timeSeconds)
            }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        val isCurl = scene is dev.geode.render.fluid.CurlFlowScene
        val isBeam = scene is BeamScene
        val persists = isCurl || isBeam
        if (persists && !sceneJustSwitched) {
            val keep =
                when {
                    isCurl -> CurlFlowMath.retention(p.trailLength, p.trails)
                    isBeam -> (0.55f + 0.44f * p.trailLength).coerceIn(0f, 0.99f)
                    else -> p.trailLength
                }
            if (p.trailZoom != 0f || p.trailWarp > 0f) {
                drawTrailWarp(p, keep, timeSeconds, dt)
            } else {
                drawFadeQuad(1f - (keep * 0.97f).pow(dt * 60f))
            }
        } else {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        wireFlowConsumers(scene, ff, p)
        scene.setParams(p)
        (scene as? PcmSink)?.let { deliverPcm(it) }
        scene.update(gainAdjusted(features, p), dt)
        scene.draw(timeSeconds)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        compositeProgram = transitionProgram(transitionId)
        activeTransition = TransitionCatalog.definition(context, transitionId)
        GLES30.glUseProgram(compositeProgram.program)
        val transition = activeTransition
        if (transition != null &&
            (compositeProgram !== uploadedTransitionFor || transition !== uploadedTransitionDef)
        ) {
            TransitionCatalog.uploadParams(compositeProgram.program, transition)
            uploadedTransitionFor = compositeProgram
            uploadedTransitionDef = transition
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboA.tex)
        GLES30.glUniform1i(cLoc("uTexA"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboB.tex)
        GLES30.glUniform1i(cLoc("uTexB"), 1)
        var flowTex = zeroTex
        var flowStrength = 0f
        if (p.flowEnabled) {
            val fluidScene = scene as? dev.geode.render.fluid.FluidScene
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
        var rippleTex = zeroTex
        var rippleTexelW = 0f
        var rippleTexelH = 0f
        var rippleStrength = 0f
        var rippleSpecular = 0f
        if (rippleOverlayOn) {
            rippleTex = ripple.heightTex
            rippleTexelW = ripple.texelW
            rippleTexelH = ripple.texelH
            rippleStrength =
                if (smearing) {
                    maxOf(p.rippleOverlayStrength, TOUCH_MIN_OVERLAY_STRENGTH).coerceIn(0f, 1f)
                } else {
                    p.rippleOverlayStrength.coerceIn(0f, 1f)
                }
            rippleSpecular = p.rippleOverlaySpecular.coerceIn(0f, 1f)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rippleTex)
        GLES30.glUniform1i(cLoc("uRipple"), 3)
        GLES30.glUniform2f(cLoc("uRippleTexel"), rippleTexelW, rippleTexelH)
        GLES30.glUniform1f(cLoc("uRippleStrength"), rippleStrength)
        GLES30.glUniform1f(cLoc("uRippleSpecular"), rippleSpecular)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glUniform1i(cLoc("uNoise"), 4)
        GLES30.glUniform1f(cLoc("uDither"), if (noiseTex != 0) BlueNoise.DITHER_AMOUNT else 0f)
        GLES30.glUniform1f(cLoc("uProgress"), progress)
        GLES30.glUniform1f(cLoc("uLayerMix"), VisualSafety.layerMix(layerMix, layerBlend, safety))
        GLES30.glUniform1i(cLoc("uBlendMode"), layerBlend.ordinal)
        val styleValue =
            when {
                layerScene != null -> STYLE_LAYER
                outgoingScene == null -> TransitionStyle.CUT.ordinal
                activeTransition != null -> TransitionCatalog.STYLE_LIBRARY
                else -> transitionStyle.ordinal
            }
        GLES30.glUniform1i(cLoc("uStyle"), styleValue)
        GLES30.glUniform1f(cLoc("uRatio"), renderWidth.toFloat() / renderHeight.toFloat())
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
        GLES30.glUniform1f(cLoc("uStrobeHz"), VisualSafety.strobeHz(safety))
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
        GLES30.glUniform1f(cLoc("uPostFlash"), fx.flash * flashGain(fx, features.beatImpulse))
        GLES30.glUniform1f(cLoc("uPostTemp"), fx.temperature)
        GLES30.glUniform1f(cLoc("uPostSolarize"), if (fx.solarize) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostMirror"), if (fx.mirror) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostInvert"), if (fx.invert) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostZoom"), fx.zoom)
        GLES30.glUniform1f(cLoc("uPostRotation"), postRotationAngle)
        GLES30.glUniform1f(cLoc("uPostSat"), fx.saturation)
        GLES30.glUniform1f(cLoc("uPostBright"), CompositeGrade.brightness(fx.brightness, fx.intensity))
        GLES30.glUniform1f(cLoc("uPostContrast"), fx.contrast)
        GLES30.glUniform1f(cLoc("uPostGamma"), fx.gamma)
        GLES30.glUniform1f(cLoc("uPostHue"), fx.colorShift + postCyclePhase)
        GLES30.glUniform1f(cLoc("uPostPulse"), CompositeGrade.pulseAmount(fx.pulse, postBeatPulse))
        val gateA = CompositeGrade.gateFor(compositeFamily(activeScene))
        val gateB =
            CompositeGrade.gateFor(compositeFamily(layerScene ?: outgoingScene ?: activeScene))
        GLES30.glUniform4fv(cLoc("uGateA"), 1, gateA.toVec4(), 0)
        GLES30.glUniform4fv(cLoc("uGateB"), 1, gateB.toVec4(), 0)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun drainTouchStrokes(
        scene: Scene,
        ripple: dev.geode.render.fluid.RippleSim?,
    ) {
        val water = scene as? dev.geode.render.fluid.WaterScene
        val hyper = scene as? HyperspaceScene
        val aspect = ripple?.aspect ?: 1f
        while (true) {
            val st = touchStrokes.poll() ?: return
            if (hyper != null) {
                hyper.queueTouchStroke(
                    st.nx * 2f - 1f,
                    1f - st.ny * 2f,
                    st.ndx * 2f,
                    -st.ndy * 2f,
                    st.strength,
                )
            } else if (water != null) {
                water.queueTouchStroke(
                    st.nx * 2f - 1f,
                    1f - st.ny * 2f,
                    st.ndx * 2f,
                    -st.ndy * 2f,
                    st.dt,
                    st.strength,
                )
            } else if (ripple != null && ripple.available) {
                ripple.queueStroke(
                    (st.nx * 2f - 1f) * aspect,
                    1f - st.ny * 2f,
                    st.ndx * 2f * aspect,
                    -st.ndy * 2f,
                    st.dt,
                    TOUCH_RADIUS,
                    st.strength,
                )
            }
        }
    }

    private fun wireFlowConsumers(
        target: Scene,
        ff: dev.geode.render.fluid.FlowField?,
        p: SceneParams,
    ) {
        if (target !is ShaderScene) return
        if (p.flowEnabled && ff != null) {
            target.setFlow(if (ff.available) ff.velocityTex else zeroTex, p.flowStrength)
        } else {
            target.setFlow(zeroTex, 0f)
        }
    }

    private fun compositeFamily(scene: Scene?): CompositeGrade.SceneFamily =
        when (scene) {
            is ShaderScene -> CompositeGrade.SceneFamily.SHADER
            is MilkdropScene -> CompositeGrade.SceneFamily.MILKDROP
            else -> CompositeGrade.SceneFamily.FLUID
        }

    private fun drawTrailWarp(
        p: SceneParams,
        retention: Float,
        timeSeconds: Float,
        dt: Float,
    ) {
        if (!trail.ensure(renderWidth, renderHeight)) {
            drawFadeQuad(1f - (retention * 0.97f).pow(dt * 60f))
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fboA.fbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, trail.fbo)
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
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trail.tex)

        fun tLoc(n: String) = trailUniforms.loc(n)
        GLES30.glUniform1i(tLoc("uPrev"), 0)
        GLES30.glUniform1f(tLoc("uDecay"), CurlFlowMath.warpDecay(retention, dt))
        GLES30.glUniform1f(tLoc("uZoom"), p.trailZoom)
        GLES30.glUniform1f(tLoc("uWarp"), p.trailWarp)
        GLES30.glUniform1f(tLoc("uTime"), timeSeconds)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun drawFadeQuad(alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(fadeProgram)
        GLES30.glUniform1f(fadeUniforms.loc("uFadeAlpha"), alpha.coerceIn(0.02f, 1f))
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun exportSceneFactory(sceneId: String): VideoExporter.SceneFactory =
        object : VideoExporter.SceneFactory {
            override fun create(): Scene {
                val quadVert = GlUtil.loadShader(context, R.raw.quad_vert)
                val scene = createScene(sceneId, quadVert, export = true)
                (scene as? dev.geode.render.fluid.FluidScene)?.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
                (scene as? MilkdropScene)?.let { pm ->
                    lastMilkPreset?.let { pm.queuePreset(it) }
                }
                scene.setParams(sceneParams)
                return scene
            }
        }
}
