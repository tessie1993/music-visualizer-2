package dev.geode.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.geode.R
import dev.geode.analysis.AudioFeatures
import dev.geode.render.scene.GlUtil
import dev.geode.render.scene.PcmPulse
import dev.geode.render.scene.PcmSink
import dev.geode.render.scene.Scene
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams
import kotlin.math.abs

internal class WaterScene(
    private val context: Context,
) : Scene,
    PcmSink {
    override val id: String = SceneIds.WATER

    private companion object {
        const val TOUCH_RADIUS = 0.11f

        const val MAX_TOUCH_BACKLOG = 24

        const val INK_GAIN = 0.8f

        const val TIME_WRAP_SECONDS = 628.31853f
    }

    private val sim = RippleSim(context).also { it.inkEnabled = true }
    private val choreography = FluidChoreography()
    private val emitters = FluidEmitters().also { it.choreography = choreography }
    private val monitor = PerformanceMonitor()

    private val audioDrive = FluidAudioDrive()

    private val pcmPulse = PcmPulse()
    private var pcmStrike = 0f

    private var params = SceneParams()
    private var time = 0f
    private var lastDt = 1f / 60f
    private var pendingFeatures: AudioFeatures? = null

    private var lastFeatures: AudioFeatures? = null
    private var featuresAgeSec = 0f
    private var width = 1
    private var height = 1

    private var displayProgram = 0
    private var displayUniforms = GlUtil.UniformCache(0)
    private var displayOk = false

    private var autoDowngrade = 0
    private var lastUserQuality = -1
    private var appliedTier = -1

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)
    private val prevBlendFunc = IntArray(4)

    private val splats = ArrayList<FluidSim.Splat>()

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        quad.forget()
        sim.onShaderError = { onShaderError(it) }
        sim.inkEnabled = true
        sim.create()
        choreography.reset()
        appliedTier = -1
        lastUserQuality = -1
        autoDowngrade = 0
        displayOk = false
        if (!sim.available) {
            onShaderError("Water style unavailable: this GPU can't render half-float buffers")
            return
        }
        displayProgram =
            GlUtil.buildProgramReporting(
                GlUtil.loadShader(context, R.raw.fluid_base_vert),
                GlUtil.loadShader(context, R.raw.water_display_frag),
            ) {
                android.util.Log.w("RippleSim", "water display shader rejected by driver: $it")
                onShaderError("Water display unavailable on this GPU: $it")
            }
        displayUniforms = GlUtil.UniformCache(displayProgram)
        displayOk = displayProgram != 0
        applyQualityTier()
    }

    override fun setParams(params: SceneParams) {
        this.params = params
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        sim.resize(width, height)
    }

    override fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) = pcmPulse.accept(samples, count)

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        time = (time + dt) % TIME_WRAP_SECONDS
        lastDt = dt
        pcmStrike = pcmPulse.tick(dt)
        pendingFeatures = features
        lastFeatures = features
        featuresAgeSec = 0f
    }

    private fun gridResFor(tierIndex: Int): Int =
        when (tierIndex) {
            0 -> 512
            1 -> 448
            2 -> 384
            3 -> 288
            else -> 192
        }

    private fun applyQualityTier() {
        if (!sim.available) return
        val userChanged = params.fluidQuality != lastUserQuality
        if (userChanged) {
            lastUserQuality = params.fluidQuality
            autoDowngrade = 0
            monitor.reset()
        }
        val idx = FluidQuality.effectiveIndex(params.fluidQuality, if (params.fluidAutoQuality) autoDowngrade else 0)
        if (idx == appliedTier) return
        appliedTier = idx
        sim.applyResolution(gridResFor(idx))
    }

    private var idlePhase = 0f
    private var rainAccum = 0f

    private val idleBands = FloatArray(16)
    private val idleWaveform = FloatArray(64)

    private fun idleFeatures(dt: Float): AudioFeatures {
        idlePhase = (idlePhase + dt) % TIME_WRAP_SECONDS
        val t = idlePhase
        val bass = 0.16f + 0.10f * kotlin.math.sin(t * 0.6f)
        val mid = 0.13f + 0.09f * kotlin.math.sin(t * 1.0f + 1.7f)
        val treble = 0.05f + 0.04f * kotlin.math.sin(t * 1.8f + 3.1f)
        for (i in idleBands.indices) idleBands[i] = 0.1f + 0.07f * kotlin.math.sin(t * (0.5f + i * 0.13f))
        return AudioFeatures(
            bands = idleBands,
            waveform = idleWaveform,
            rms = 0.18f,
            bass = bass.coerceAtLeast(0f),
            mid = mid.coerceAtLeast(0f),
            treble = treble.coerceAtLeast(0f),
            beat = false,
        )
    }

    private fun queueIdleRain(dt: Float) {
        rainAccum += dt
        if (rainAccum < 0.45f) return
        rainAccum = 0f
        val x = (kotlin.random.Random.nextFloat() * 2f - 1f) * sim.aspect * 0.85f
        val y = kotlin.random.Random.nextFloat() * 2f - 1f
        val (tr, tg, tb) = FluidHue.rgb(FluidHue.base(params.paletteBase) + 0.12f * kotlin.random.Random.nextFloat(), 0.5f)
        sim.queueDrop(x, y * 0.85f, 0.05f, 0.28f * params.waterRippleStrength.coerceIn(0f, 2f), tr, tg, tb)
    }

    private val touchStrokes = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) {
        if (touchStrokes.size >= MAX_TOUCH_BACKLOG) return
        touchStrokes.add(floatArrayOf(nx, ny, ndx, ndy, dt, strength))
    }

    private fun drainTouchStrokes(
        rippleStrength: Float,
        baseHue: Float,
        p: SceneParams,
    ) {
        while (true) {
            val st = touchStrokes.poll() ?: return
            val (tr, tg, tb) = FluidHue.rgb(baseHue + 0.5f * FluidHue.range(p.hueRange), 1f)
            sim.queueStroke(
                st[0] * sim.aspect,
                st[1],
                st[2] * sim.aspect,
                st[3],
                st[4],
                TOUCH_RADIUS,
                st[5] * rippleStrength.coerceAtLeast(0.2f),
                tr,
                tg,
                tb,
            )
        }
    }

    override fun draw(timeSeconds: Float) {
        if (!sim.available || !displayOk) return
        GlUtil.resetFrameState()
        val p = params
        featuresAgeSec = (featuresAgeSec + lastDt).coerceAtMost(1f)
        val idle = pendingFeatures == null && featuresAgeSec >= 0.25f
        val f =
            audioDrive.scaled(
                pendingFeatures
                    ?: lastFeatures.takeIf { featuresAgeSec < 0.25f }
                    ?: idleFeatures(lastDt),
                p.audioDrive,
            )

        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_RGB, prevBlendFunc, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_RGB, prevBlendFunc, 1)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, prevBlendFunc, 2)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, prevBlendFunc, 3)
        val blendWas = GLES30.glIsEnabled(GLES30.GL_BLEND)

        if (p.fluidAutoQuality) {
            val severity = monitor.onFrame(lastDt)
            if (severity > 0) {
                autoDowngrade += severity
                monitor.reset()
            }
        }
        applyQualityTier()

        sim.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
        sim.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
        sim.inkFlow = p.waterLiquidFlow.coerceIn(0f, 4f)
        sim.inkDissipation = p.waterLiquidFade.coerceIn(0f, 2f)

        choreography.path = p.fluidSpawnPath.coerceIn(0, FluidChoreography.PATH_LABELS.size - 1)
        choreography.spawnCount = p.fluidSpawnPoints.coerceIn(1, FluidChoreography.MAX_SPAWN)
        choreography.catchCount = p.fluidCatchPoints.coerceIn(0, FluidChoreography.MAX_CATCH)
        choreography.progressionAmount = p.fluidSpawnProgress.coerceIn(0f, 1f)
        choreography.speed = FluidChoreography.sceneSpeed(p.speed)

        emitters.beatPattern = p.fluidBeatPattern.coerceIn(0, 3)
        emitters.beatSplats = p.fluidBeatSplats.coerceIn(0, 8)
        emitters.stirrers = p.fluidStirrers.coerceIn(0, 4)
        emitters.stirrerSpeed = p.fluidStirrerSpeed.coerceIn(0f, 2f) * FluidChoreography.sceneSpeed(p.speed)
        emitters.bassPump = p.fluidBassPump
        emitters.sparkle = p.fluidSparkle
        emitters.splatRadius = p.fluidSplatRadius.coerceIn(0.02f, 0.4f)
        emitters.radiusPulse = p.fluidRadiusPulse.coerceIn(0f, 1f)
        emitters.catchSuction = p.fluidCatchPull.coerceIn(0f, 3f)
        emitters.forceScale = p.fluidSplatForce.coerceIn(0f, 3f)
        emitters.beatResponse = p.beatResponse

        val simDt = lastDt.coerceIn(0f, 1f / 30f)
        choreography.tick(f, simDt, sim.aspect)
        val pcmKick = pcmStrike.coerceIn(0f, 1f)
        val rippleStrength = p.waterRippleStrength.coerceIn(0f, 2f)
        val catchRadius = WaterMath.catchWellRadius(p.fluidCatchRadius)
        val baseHue = FluidHue.base(p.paletteBase)
        emitters.tick(f, simDt, sim.aspect, baseHue, FluidHue.range(p.hueRange), splats)
        for (i in splats.indices) {
            val s = splats[i]
            val speed = kotlin.math.sqrt(s.velX * s.velX + s.velY * s.velY) / FluidEmitters.BASE_SPEED
            if (WaterMath.isCatchWell(s.r, s.g, s.b)) {
                val well = WaterMath.catchWellAmplitude(speed, catchRadius, rippleStrength)
                if (abs(well) > 1e-4f) sim.queueDrop(s.curX, s.curY, catchRadius, well)
                continue
            }
            val amp = (0.06f + 0.5f * speed.coerceAtMost(2f)) * rippleStrength * (1f + pcmKick * 0.6f)
            if (amp > 1e-4f) {
                sim.queueDrop(s.curX, s.curY, s.radius * 0.6f, amp, s.r * INK_GAIN, s.g * INK_GAIN, s.b * INK_GAIN)
            }
        }
        if (idle) queueIdleRain(lastDt)
        drainTouchStrokes(rippleStrength, baseHue, p)
        sim.step(simDt)
        pendingFeatures = null

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(displayProgram)
        GLES30.glUniform2f(dLoc("uInvRes"), sim.texelW, sim.texelH)
        GLES30.glUniform1f(dLoc("uAspect"), sim.aspect)
        GLES30.glUniform1f(dLoc("uTime"), time)
        GLES30.glUniform1f(dLoc("uBaseHue"), baseHue)
        GLES30.glUniform1f(dLoc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange))
        GLES30.glUniform1f(dLoc("uDepth"), p.waterDepth.coerceIn(0f, 1f))
        GLES30.glUniform1f(dLoc("uSpecular"), p.waterSpecular.coerceIn(0f, 1f))
        GLES30.glUniform1f(dLoc("uFlowDrift"), p.waterFlow.coerceIn(0f, 1f))
        GLES30.glUniform1f(dLoc("uRefract"), 0.9f)
        GLES30.glUniform1f(dLoc("uTreble"), (f.treble + pcmKick * 0.5f).coerceIn(0f, 2f))
        GLES30.glUniform1f(dLoc("uBrightness"), WaterMath.DISPLAY_BRIGHTNESS)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sim.heightTex)
        GLES30.glUniform1i(dLoc("uHeight"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (sim.inkAvailable) sim.inkTex else sim.heightTex)
        GLES30.glUniform1i(dLoc("uInk"), 1)
        GLES30.glUniform1f(dLoc("uInkAmount"), if (sim.inkAvailable) p.waterLiquid.coerceIn(0f, 1f) else 0f)
        quad.draw()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        if (blendWas) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3])
    }

    private val quad = GlUtil.FullscreenTriangle()

    private fun dLoc(name: String): Int = displayUniforms.loc(name)

    override fun release() {
        sim.release()
        if (displayProgram != 0) GLES30.glDeleteProgram(displayProgram)
        displayProgram = 0
        displayUniforms = GlUtil.UniformCache(0)
        displayOk = false
        quad.release()
        appliedTier = -1
    }
}

internal object WaterMath {
    const val MIN_CATCH_RADIUS = 0.03f
    const val MAX_CATCH_RADIUS = 0.3f

    const val REF_CATCH_RADIUS = 0.12f

    private const val MIN_SPREAD = 0.4f
    private const val MAX_SPREAD = 2.5f

    fun isCatchWell(
        r: Float,
        g: Float,
        b: Float,
    ): Boolean = maxOf(r, g, b) <= 0f

    fun catchWellRadius(catchRadius: Float): Float = catchRadius.coerceIn(MIN_CATCH_RADIUS, MAX_CATCH_RADIUS)

    fun catchWellAmplitude(
        speed: Float,
        catchRadius: Float,
        rippleStrength: Float,
    ): Float {
        val r = catchWellRadius(catchRadius)
        val spread = (REF_CATCH_RADIUS / r).coerceIn(MIN_SPREAD, MAX_SPREAD)
        return -(0.06f + 0.5f * speed.coerceIn(0f, 2f)) * spread * rippleStrength.coerceIn(0f, 2f)
    }

    const val DISPLAY_BRIGHTNESS = 1f

    fun effectiveBrightness(
        brightness: Float,
        intensity: Float,
    ): Float = DISPLAY_BRIGHTNESS * brightness * intensity
}
