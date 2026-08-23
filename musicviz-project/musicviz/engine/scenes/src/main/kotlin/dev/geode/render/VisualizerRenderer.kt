package dev.geode.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.geode.analysis.AudioFeatures
import dev.geode.engine.scenes.R
import dev.geode.render.fluid.CurlFlowMath
import dev.geode.render.fluid.CurlFlowScene
import dev.geode.render.fluid.FluidScene
import dev.geode.render.fluid.WaterScene
import dev.geode.render.scene.BeamScene
import dev.geode.render.scene.GlUtil
import dev.geode.render.scene.MilkdropScene
import dev.geode.render.scene.PcmChunk
import dev.geode.render.scene.PcmSink
import dev.geode.render.scene.Scene
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams
import dev.geode.render.scene.ShaderScene
import dev.geode.render.scene.applyBandGains
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VisualizerRenderer(
    private val context: Context,
) : GLSurfaceView.Renderer {
    companion object {
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

    /**
     * Vestibular accessibility only. The flash clamp is not routed through here — it is
     * unconditional inside [VisualSafety] and cannot be switched off from anywhere.
     */
    @Volatile
    var reducedMotion: Boolean = false

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
    var transitionId: String = TransitionStyle.FADE.name.lowercase()

    @Volatile
    var onShaderError: (String?) -> Unit = {}

    var onMilkPresetLoaded: (String) -> Unit = {}

    @Volatile
    var pcmProvider: () -> PcmChunk? = { null }

    val lfoEngine = LfoEngine()

    val adsrEngine = AdsrEngine()

    private val registry =
        SceneRegistry(
            context,
            object : SceneRegistry.Host {
                override fun onShaderError(message: String?) = this@VisualizerRenderer.onShaderError(message)

                override fun onMilkPresetLoaded(path: String) = this@VisualizerRenderer.onMilkPresetLoaded(path)
            },
        )

    private val overlays = OverlayEffects(context)
    private val trailPass = TrailPass()
    private val compositePass = CompositePass(context)
    private val compositeInputs = CompositePass.Inputs()

    private val flashBudget = FlashBudget()

    private val fboA = RenderTarget("sceneA")
    private val fboB = RenderTarget("sceneB")

    private var quadVao = 0

    private var displayedParams: SceneParams = SceneParams.DEFAULT
    private var lastFinalParams: SceneParams = SceneParams.DEFAULT

    @Volatile
    private var morphFadeSec = 0f

    @Volatile
    private var morphRemainSec = 0f

    private val envRateOffsets = FloatArray(3)
    private val envDepthOffsets = FloatArray(3)

    private var postRotationAngle = 0f
    private var postCyclePhase = 0f
    private var postBeatPulse = 0f

    private var activeScene: Scene? = null
    private var outgoingScene: Scene? = null
    private var layerScene: Scene? = null
    private var outgoingParams: SceneParams? = null
    private var transitionStartMs = 0L
    private var sceneJustSwitched = false

    private var width = 1
    private var height = 1
    private var renderWidth = 1
    private var renderHeight = 1
    private var lastFrameMs = 0L
    private var frameNowMs = 0L
    private var timeSeconds = 0f

    private var framePcm: PcmChunk? = null
    private var framePcmDrained = false

    val milkdropAvailable: Boolean get() = registry.milkdropAvailable

    fun availableSceneIds(): List<String> = registry.availableSceneIds()

    fun submitShader(
        sceneId: String,
        fragmentSrc: String,
    ) = registry.submitShader(sceneId, fragmentSrc)

    fun customShaderFor(sceneId: String): String? = registry.customShaderFor(sceneId)

    fun submitFluidInjectionShaders(
        force: String?,
        dye: String?,
    ) = registry.submitFluidInjectionShaders(force, dye)

    fun loadMilkPreset(path: String) = registry.loadMilkPreset(path)

    fun reloadCurrentMilkPreset() = registry.reloadCurrentMilkPreset()

    fun warmTransition(id: String) = compositePass.warmTransition(id)

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) = overlays.queueTouchStroke(nx, ny, ndx, ndy, dt, strength)

    fun beginParamMorph(seconds: Float) {
        if (seconds <= 0f) return
        morphFadeSec = seconds
        morphRemainSec = seconds * 3f
    }

    /** Always budgeted — the same call the offscreen path makes, so preview and export agree. */
    private fun flashGain(
        fx: SceneParams,
        hit: Float,
    ): Float = flashBudget.gainFor(timeSeconds, VisualSafety.flashImpulse(fx.flash, hit))

    private fun gainAdjusted(
        f: AudioFeatures,
        p: SceneParams,
    ): AudioFeatures = applyBandGains(f, p)

    private fun deliverPcm(sink: PcmSink) {
        if (!framePcmDrained) {
            framePcmDrained = true
            framePcm = pcmProvider()
        }
        val chunk = framePcm ?: return
        if (chunk.count > 0) sink.acceptPcm(chunk.data, chunk.count)
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        registry.onSurfaceCreated(renderWidth, renderHeight)
        fboA.release()
        fboB.release()
        compositePass.releaseStaleTextures()
        activeScene = registry.sceneFor(requestedSceneId) ?: registry.sceneFor(SceneIds.DEFAULT)
        outgoingScene = null
        outgoingParams = null

        overlays.recreate()

        val fadeVert = GlUtil.loadShader(context, R.raw.fade_vert)
        trailPass.create(context, fadeVert)
        compositePass.create(fadeVert)
        registry.createPaletteLut()

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
        registry.resize(renderWidth, renderHeight, width, height)
        overlays.resize(renderWidth, renderHeight)
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
        val dt = beginFrame()
        val scene = resolveActiveScene() ?: return
        val p = resolveParams(dt)
        registry.applyPendingFluidInjection()
        resolveLayerScene()
        stepOverlays(scene, p, dt)
        if (!ensureTargets()) return
        val progress = drawSecondaryTargets(p, dt)
        drawSceneTarget(scene, p, dt)
        composite(scene, p, progress)
    }

    private fun beginFrame(): Float {
        framePcmDrained = false
        framePcm = null
        GlUtil.resetFrameState()
        val now = SystemClock.elapsedRealtime()
        frameNowMs = now
        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
        lastFrameMs = now
        timeSeconds = (timeSeconds + dt) % TIME_WRAP_SEC
        registry.drainPendingShaders()
        return dt
    }

    private fun resolveActiveScene(): Scene? {
        registry.sceneParams = sceneParams
        val requested = registry.sceneFor(requestedSceneId)
        sceneJustSwitched = false
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
        return activeScene
    }

    private fun resolveParams(dt: Float): SceneParams {
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
        val lfoValues = lfoEngine.tick(dt, features, envRateOffsets, envDepthOffsets)
        var p = LfoEngine.apply(displayedParams, lfoEngine.configs, lfoValues)
        p = AdsrEngine.apply(p, adsrEngine.configs, envValues)
        p = VisualSafety.apply(p, reducedMotion)
        lastFinalParams = p
        postRotationAngle = CompositeGrade.integrateRotation(postRotationAngle, p.rotation, dt)
        postCyclePhase = CompositeGrade.integrateCyclePhase(postCyclePhase, p.cycleSpeed, dt, p.colorCycle)
        postBeatPulse = CompositeGrade.integrateBeatPulse(postBeatPulse, LiveSignal.hit(features), dt)
        return p
    }

    private fun resolveLayerScene() {
        layerScene =
            if (outgoingScene != null) {
                null
            } else {
                layerSceneId
                    ?.takeIf { it != requestedSceneId }
                    ?.let { registry.sceneFor(it) }
                    ?.takeIf { it !== activeScene }
            }
    }

    private var rippleOverlayOn = false
    private var smearing = false

    private fun stepOverlays(
        scene: Scene,
        p: SceneParams,
        dt: Float,
    ) {
        if (overlays.wantsFlow(p, fluidActive = scene is FluidScene)) {
            overlays.stepFlow(gainAdjusted(features, p), dt, p)
        }
        smearing = overlays.smearing(frameNowMs)
        overlays.drainTouchStrokes(scene)
        rippleOverlayOn = overlays.rippleOverlayActive(p, smearing, waterActive = scene is WaterScene)
        if (rippleOverlayOn) overlays.stepRippleOverlay(gainAdjusted(features, p), p, dt)
    }

    private fun ensureTargets(): Boolean {
        if (!fboA.ensure(renderWidth, renderHeight)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return false
        }
        if (!fboB.ensure(renderWidth, renderHeight)) {
            layerScene = null
            outgoingScene = null
            outgoingParams = null
        }
        return true
    }

    private fun drawSecondaryTargets(
        p: SceneParams,
        dt: Float,
    ): Float {
        var progress = 1f
        val layer = layerScene
        if (layer != null) {
            bindSecondaryTarget()
            wireFlowConsumers(layer, p)
            layer.setParams(p)
            (layer as? PcmSink)?.let { deliverPcm(it) }
            layer.update(gainAdjusted(features, p), dt)
            layer.draw(timeSeconds)
        }
        val outgoing = outgoingScene
        if (outgoing != null) {
            progress = ((frameNowMs - transitionStartMs).toFloat() / transitionDurationMs).coerceIn(0f, 1f)
            if (progress >= 1f) {
                outgoingScene = null
                outgoingParams = null
            } else {
                bindSecondaryTarget()
                val op = outgoingParams ?: p
                outgoing.setParams(op)
                (outgoing as? PcmSink)?.let { deliverPcm(it) }
                outgoing.update(gainAdjusted(features, op), dt)
                outgoing.draw(timeSeconds)
            }
        }
        return progress
    }

    private fun bindSecondaryTarget() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun drawSceneTarget(
        scene: Scene,
        p: SceneParams,
        dt: Float,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        val isCurl = scene is CurlFlowScene
        val isBeam = scene is BeamScene
        val persists = isCurl || isBeam
        if (persists && !sceneJustSwitched) {
            val keep =
                when {
                    isCurl -> CurlFlowMath.retention(p.trailLength, p.trails)
                    isBeam -> (0.55f + 0.44f * p.trailLength).coerceIn(0f, 0.99f)
                    else -> p.trailLength
                }
            trailPass.apply(p, keep, timeSeconds, dt, fboA, quadVao, renderWidth, renderHeight)
        } else {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        wireFlowConsumers(scene, p)
        scene.setParams(p)
        (scene as? PcmSink)?.let { deliverPcm(it) }
        scene.update(gainAdjusted(features, p), dt)
        scene.draw(timeSeconds)
    }

    private fun composite(
        scene: Scene,
        p: SceneParams,
        progress: Float,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)

        val fx = lastFinalParams
        val inputs = compositeInputs
        inputs.texA = fboA.tex
        inputs.texB = fboB.tex
        resolveFlowUniforms(scene, p, inputs)
        resolveRippleUniforms(p, inputs)
        inputs.progress = progress
        inputs.layerMix = VisualSafety.layerMix(layerMix, layerBlend)
        inputs.blendOrdinal = layerBlend.ordinal
        inputs.hasLayer = layerScene != null
        inputs.hasOutgoing = outgoingScene != null
        inputs.transitionStyle = transitionStyle
        inputs.transitionId = transitionId
        inputs.ratio = renderWidth.toFloat() / renderHeight.toFloat()
        inputs.timeSeconds = timeSeconds
        // uBeat is the live transient, not a tracked beat: the FX that ride it (flash, shake,
        // glitch, strobe duty) then land on what was actually played this frame.
        val hit = LiveSignal.hit(features)
        inputs.hitImpulse = hit
        inputs.flash = fx.flash * flashGain(fx, hit)
        inputs.strobeHz = VisualSafety.strobeHz()
        inputs.postRotationAngle = postRotationAngle
        inputs.postCyclePhase = postCyclePhase
        inputs.postBeatPulse = postBeatPulse
        inputs.quadVao = quadVao
        inputs.fx = fx
        inputs.gateA = CompositeGrade.gateFor(compositeFamily(activeScene)).toVec4()
        inputs.gateB =
            CompositeGrade.gateFor(compositeFamily(layerScene ?: outgoingScene ?: activeScene)).toVec4()
        compositePass.draw(inputs)
    }

    private fun resolveFlowUniforms(
        scene: Scene,
        p: SceneParams,
        inputs: CompositePass.Inputs,
    ) {
        var flowTex = compositePass.zeroTex
        var flowStrength = 0f
        if (p.flowEnabled) {
            val fluidScene = scene as? FluidScene
            val ff = overlays.flow
            if (fluidScene != null && fluidScene.simAvailable) {
                flowTex = fluidScene.velocityTexture
                flowStrength = p.flowStrength
            } else if (ff != null && ff.available) {
                flowTex = ff.velocityTex
                flowStrength = p.flowStrength
            }
        }
        inputs.flowTex = flowTex
        inputs.flowStrength = flowStrength
    }

    private fun resolveRippleUniforms(
        p: SceneParams,
        inputs: CompositePass.Inputs,
    ) {
        var rippleTex = compositePass.zeroTex
        var rippleTexelW = 0f
        var rippleTexelH = 0f
        var rippleStrength = 0f
        var rippleSpecular = 0f
        val ripple = overlays.ripple
        if (rippleOverlayOn && ripple != null) {
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
        inputs.rippleTex = rippleTex
        inputs.rippleTexelW = rippleTexelW
        inputs.rippleTexelH = rippleTexelH
        inputs.rippleStrength = rippleStrength
        inputs.rippleSpecular = rippleSpecular
    }

    private fun wireFlowConsumers(
        target: Scene,
        p: SceneParams,
    ) {
        if (target !is ShaderScene) return
        val ff = overlays.flow
        if (p.flowEnabled && ff != null) {
            target.setFlow(if (ff.available) ff.velocityTex else compositePass.zeroTex, p.flowStrength)
        } else {
            target.setFlow(compositePass.zeroTex, 0f)
        }
    }

    private fun compositeFamily(scene: Scene?): CompositeGrade.SceneFamily =
        when (scene) {
            is ShaderScene -> CompositeGrade.SceneFamily.SHADER
            is MilkdropScene -> CompositeGrade.SceneFamily.MILKDROP
            else -> CompositeGrade.SceneFamily.FLUID
        }

    fun exportSceneFactory(sceneId: String): SceneFactory =
        object : SceneFactory {
            override fun create(): Scene = registry.exportScene(sceneId, sceneParams)
        }
}

private const val TOUCH_MIN_OVERLAY_STRENGTH = 0.35f
