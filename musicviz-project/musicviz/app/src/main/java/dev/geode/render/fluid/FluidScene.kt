package dev.geode.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.geode.analysis.AudioFeatures
import dev.geode.render.scene.GlUtil
import dev.geode.render.scene.PcmPulse
import dev.geode.render.scene.PcmSink
import dev.geode.render.scene.Scene
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams

internal class FluidScene(
    context: Context,
) : Scene,
    PcmSink {
    override val id: String = SceneIds.FLUID

    private companion object {
        const val TIME_WRAP_SECONDS = 7100f

        const val IDLE_WRAP_SECONDS = 628.31853f
    }

    private val sim = FluidSim(context)
    private val look = FluidLook(context)
    private val particles = FluidParticles(context)
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

    private val spawnPack = FloatArray(FluidChoreography.MAX_SPAWN * 4)
    private val catchPack = FloatArray(FluidChoreography.MAX_CATCH * 4)

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)
    private val prevBlendFunc = IntArray(4)

    private val splats = ArrayList<FluidSim.Splat>()

    private var autoDowngrade = 0
    private var lastUserQuality = -1
    private var appliedTier = -1
    private var appliedParticleSide = 0

    var onShaderError: (String?) -> Unit = {}

    val velocityTexture: Int get() = sim.velocityTex
    val simAvailable: Boolean get() = sim.available

    fun setInjectionShaders(
        forceSrc: String?,
        dyeSrc: String?,
    ) = sim.setInjectionShaders(forceSrc, dyeSrc)

    override fun init() {
        sim.onShaderError = { onShaderError(it) }
        sim.create()
        choreography.reset()
        if (sim.available) {
            look.create(sim.texFormats)
            appliedTier = -1
            appliedParticleSide = 0
            applyQualityTier()
        } else {
            onShaderError("Fluid style unavailable: this GPU can't render half-float buffers")
        }
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
        if (sim.resize(width, height)) particles.invalidateSeed()
        look.resize(width, height)
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

    private fun applyQualityTier() {
        if (!sim.available) return
        val userChanged = params.fluidQuality != lastUserQuality
        if (userChanged) {
            lastUserQuality = params.fluidQuality
            autoDowngrade = 0
            monitor.reset()
        }
        val idx = FluidQuality.effectiveIndex(params.fluidQuality, if (params.fluidAutoQuality) autoDowngrade else 0)
        if (idx == appliedTier && appliedParticleSide != 0) return
        val tier = FluidQuality.tier(idx)
        appliedTier = idx
        sim.applyResolution(tier.simRes, tier.dyeRes)
        val recreateParticles =
            appliedParticleSide == 0 || (userChanged && appliedParticleSide != tier.particleSide)
        if (recreateParticles) {
            appliedParticleSide = tier.particleSide
            particles.create(tier.particleSide * tier.particleSide, sim.texFormats)
        }
    }

    private var idlePhase = 0f
    private var diagFrames = 0
    private var dyeProbed = false

    private val idleBands = FloatArray(16)
    private val idleWaveform = FloatArray(64)

    private fun idleFeatures(dt: Float): AudioFeatures {
        idlePhase = (idlePhase + dt) % IDLE_WRAP_SECONDS
        val t = idlePhase
        val bass = 0.18f + 0.12f * kotlin.math.sin(t * 0.7f)
        val mid = 0.15f + 0.10f * kotlin.math.sin(t * 1.1f + 1.7f)
        val treble = 0.05f + 0.04f * kotlin.math.sin(t * 1.9f + 3.1f)
        for (i in idleBands.indices) idleBands[i] = 0.1f + 0.08f * kotlin.math.sin(t * (0.5f + i * 0.13f))
        return AudioFeatures(
            bands = idleBands,
            waveform = idleWaveform,
            rms = 0.2f,
            bass = bass.coerceAtLeast(0f),
            mid = mid.coerceAtLeast(0f),
            treble = treble.coerceAtLeast(0f),
            beat = false,
        )
    }

    override fun draw(timeSeconds: Float) {
        if (!sim.available) return
        GlUtil.resetFrameState()
        val p = params
        featuresAgeSec = (featuresAgeSec + lastDt).coerceAtMost(1f)
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

        val energy = f.rms.coerceIn(0f, 1f)
        val pcmKick = pcmStrike.coerceIn(0f, 1f)
        sim.pressureIterations = p.fluidIterations.coerceIn(8, 40)
        sim.pressureDamp = p.fluidPressure.coerceIn(0f, 1f)
        sim.velocityDissipation = p.fluidVelocityDissipation.coerceIn(0f, 4f)
        sim.curlStrength = p.fluidCurl.coerceIn(0f, 50f) * (1f + p.fluidCurlAudio * f.mid + pcmKick * 0.5f)
        sim.densityDissipation =
            p.fluidDensityDissipation.coerceIn(0f, 4f) *
            (1f + p.fluidFadeAudio * (1f - energy))
        sim.chromaticAging = p.fluidChromaticAging.coerceIn(0f, 1f)
        sim.audioBass = f.bass
        sim.audioMid = f.mid
        sim.audioTreble = f.treble
        sim.audioEnergy = energy
        sim.audioBeat = f.motionImpulse
        sim.timeSeconds = time

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
        emitters.beatResponse = p.beatResponse
        emitters.paletteCycleSpeed = FluidHue.paletteCycleSpeed(p.fluidPaletteCycleSpeed)
        emitters.forceScale = p.fluidSplatForce.coerceIn(0f, 3f) * (1f + pcmKick * 0.5f)
        val simDt = lastDt.coerceIn(0f, 1f / 30f)
        val hueBase = FluidHue.base(p.paletteBase)
        val hueSpan = FluidHue.span(p.hueRange, p.paletteRange)
        choreography.tick(f, simDt, sim.aspect)
        emitters.tick(f, simDt, sim.aspect, hueBase, hueSpan, splats)
        for (i in splats.indices) sim.queueSplat(splats[i])
        sim.step(simDt)
        if (diagFrames < 3) {
            val err = GLES30.glGetError()
            if (err != GLES30.GL_NO_ERROR) {
                android.util.Log.w("FluidSim", "glError after step frame $diagFrames: 0x${Integer.toHexString(err)}")
            }
            diagFrames++
            if (diagFrames == 3) android.util.Log.i("FluidSim", "first frames stepped clean (no GL errors)")
        }
        if (particles.available && p.fluidParticlesEnabled) {
            particles.drag = p.fluidParticleDrag.coerceIn(0.02f, 1f)
            particles.life = p.fluidParticleLife.coerceIn(1f, 20f)
            choreography.packSpawns(spawnPack)
            choreography.packCatches(
                catchPack,
                pull = p.fluidCatchPull.coerceIn(0f, 3f),
                captureRadius = p.fluidCatchRadius.coerceIn(0.03f, 0.3f),
            )
            particles.setChoreography(spawnPack, choreography.spawnCount, catchPack, choreography.catchCount)
            particles.step(simDt, sim.velocityTex, sim.aspect, sim.flowScale, timeSeconds = time)
        }
        look.bloomIntensity =
            p.fluidBloomIntensity.coerceIn(0.1f, 2f) * (0.6f + p.fluidBloomAudio * energy)
        look.bloomThreshold = p.fluidBloomThreshold.coerceIn(0f, 1f)
        look.sunraysWeight = p.fluidSunraysWeight.coerceIn(0.3f, 1f)
        if (p.fluidDyeEnabled) look.process(sim.dyeTex, p.fluidBloom, p.fluidSunrays)
        pendingFeatures = null
        if (!dyeProbed && time > 1.5f) {
            dyeProbed = true
            android.util.Log.i("FluidSim", "dye liveness: ${sim.probeDyeMax()}")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        if (p.fluidDyeEnabled) {
            if (look.available) {
                look.drawDisplay(
                    dyeTex = sim.dyeTex,
                    shadingOn = p.fluidShading,
                    bloomOn = p.fluidBloom,
                    sunraysOn = p.fluidSunrays,
                    viewportW = prevViewport[2].coerceAtLeast(1),
                    viewportH = prevViewport[3].coerceAtLeast(1),
                )
            } else {
                sim.drawDisplay()
            }
        }
        if (particles.available && p.fluidParticlesEnabled) {
            val dpiScale = (prevViewport[3].coerceAtLeast(1) / 1080f).coerceIn(0.75f, 2.5f)
            particles.draw(
                aspect = sim.aspect,
                pointScale = (1.5f * p.particleSize.coerceIn(0.2f, 3f)) * dpiScale,
                hueBase = hueBase,
                hueSpan = hueSpan,
                brightness =
                    0.55f * p.fluidParticleBrightness.coerceIn(0f, 2f) *
                        (0.3f + p.density.coerceIn(0f, 1.5f)),
                shape = p.particleShape.toFloat(),
                glow = dev.geode.render.scene.ParticleLook.glow(p.bloom),
                timeSeconds = timeSeconds,
            )
        }
        if (blendWas) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3])
    }

    override fun release() {
        particles.release()
        look.release()
        sim.release()
        appliedTier = -1
        appliedParticleSide = 0
    }
}
