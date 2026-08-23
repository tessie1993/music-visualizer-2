package dev.geode.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.geode.analysis.AudioFeatures
import dev.geode.engine.scenes.R
import dev.geode.render.fluid.FluidHue
import dev.geode.render.fluid.MeltField
import dev.geode.render.fluid.MeltMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

internal class HyperspaceScene(
    private val context: Context,
    private val style: VisualStyleCatalog.HyperspaceStyle =
        requireNotNull(VisualStyleCatalog.hyperspace(SceneIds.HYPERSPACE)),
) : Scene,
    PcmSink {
    override val id: String = style.id

    private companion object {
        const val IDLE_RMS = 0.015f

        const val IDLE_FADE_SECONDS = 1.5f

        const val IDLE_CYCLE_SECONDS = 150f

        const val IDLE_IMPULSE = 0.35f

        const val IDLE_IMPULSE_SECONDS = 2.2f

        const val FOV = 0.85f

        const val EXPOSURE = 1.45f

        const val BODY_INK = 0.22f

        const val SLEW_RISE_PER_SEC = 2.2f
        const val SLEW_FALL_PER_SEC = 1.1f
    }

    private val journey = HyperspaceJourney()
    private val camera = HyperspaceCamera()
    private var bank = BloomBank()

    private val melt = MeltField(context)

    private val prevBodyXy = FloatArray(HyperspaceMath.MAX_BLOOMS * 2)
    private val hasPrevBody = BooleanArray(HyperspaceMath.MAX_BLOOMS)

    private val bloomPos = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomShape = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomLook = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomRot = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_MAT3)
    private var bloomCount = 0

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    private val bodyRgb = FloatArray(3)

    private var params = SceneParams.DEFAULT
    private var time = 0f
    private var lastDt = 1f / 60f
    private var pendingFeatures: AudioFeatures? = null
    private var width = 1
    private var height = 1

    private var program = 0
    private var uniforms = GlUtil.UniformCache(0)
    private var programOk = false
    private var vao = 0

    private val pcmPulse = PcmPulse()
    private var beatPulse = 0f
    private var idleBlend = 0f
    private var idlePhase = 0f
    private var idleImpulseAge = 0f

    private var slewBass = 0f
    private var slewMid = 0f

    private var stylePhase = 0f

    private val spectral = SpectralSummary()

    private var camDistance = 6f
    private var farPlane = 12f

    private val silence = AudioFeatures.empty()

    var onShaderError: (String?) -> Unit = {}

    val currentAct: HyperspaceMath.Act
        get() = HyperspaceMath.ACTS[journey.act.coerceIn(0, HyperspaceMath.ACTS.size - 1)]

    override fun init() {
        program = 0
        vao = 0
        uniforms = GlUtil.UniformCache(0)
        programOk = false
        bank.reset()
        journey.reset()
        camera.reset()
        bloomCount = 0
        hasPrevBody.fill(false)
        slewBass = 0f
        slewMid = 0f
        stylePhase = 0f
        spectral.reset()
        melt.onShaderError = { onShaderError(it) }
        melt.create()
        program =
            GlUtil.buildProgramReporting(
                GlUtil.loadShader(context, R.raw.quad_vert),
                GlUtil.loadShader(context, R.raw.hyperspace_frag),
            ) {
                onShaderError("Hyperspace unavailable on this GPU: $it")
            }
        if (program == 0) return
        programOk = true
        uniforms = GlUtil.UniformCache(program)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
    }

    override fun setParams(params: SceneParams) {
        this.params = params
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = max(width, 1)
        this.height = max(height, 1)
        melt.resize(this.width, this.height)
    }

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        strength: Float,
    ) = melt.queueTouchStroke(nx, ny, ndx, ndy, strength)

    override fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) = pcmPulse.accept(samples, count)

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        time = (time + dt) % HyperspaceMath.TIME_WRAP_SECONDS
        lastDt = dt
        pendingFeatures = features
    }

    override fun draw(timeSeconds: Float) {
        if (!programOk) return
        GlUtil.resetFrameState()
        val p = params
        val dt = lastDt.coerceIn(0f, 1f / 15f)
        val f = pendingFeatures ?: silence
        pendingFeatures = null
        val pace = p.speed.coerceIn(0.05f, 4f)

        val silent = f.rms < IDLE_RMS
        val fadeStep = if (IDLE_FADE_SECONDS > 0f) dt / IDLE_FADE_SECONDS else 1f
        idleBlend = (idleBlend + if (silent) fadeStep else -fadeStep * 3f).coerceIn(0f, 1f)
        idlePhase = (idlePhase + dt / IDLE_CYCLE_SECONDS) % 1f
        val live = (if (f.macroEnergy > 0f) f.macroEnergy else f.rms) * p.audioDrive.coerceIn(0f, 4f)
        val idle = 0.5f - 0.5f * cos(idlePhase * 2f * PI.toFloat())
        val energy = (live * (1f - idleBlend) + idle * idleBlend).coerceIn(0f, 1f)

        journey.advance(
            dt = dt,
            energy = energy,
            mode = p.hyperJourney,
            holdAct = p.hyperAct,
            cycleSeconds = p.hyperCycleSeconds,
            pace = pace,
            progress = f.progress,
        )
        val profile = journey.profile()

        slewBass = HyperspaceMath.slewLimit(slewBass, f.bass, dt, SLEW_RISE_PER_SEC, SLEW_FALL_PER_SEC)
        slewMid = HyperspaceMath.slewLimit(slewMid, f.mid, dt, SLEW_RISE_PER_SEC, SLEW_FALL_PER_SEC)
        spectral.advance(f.bands, dt)
        val pcmKick = pcmPulse.tick(dt).coerceIn(0f, 1f)
        stylePhase = (stylePhase + dt * pace * (style.phaseRate + style.phaseBassRate * slewBass) * (1f + pcmKick * 0.35f)) % 1f
        val beatWeight = HyperspaceMath.beatGate(f.pulseConfidence)

        val target = HyperspaceLook.bodyTarget(profile.bodies, p.hyperBodies * style.bodyScale)
        val spread = HyperspaceLook.spread(target)
        idleImpulseAge += dt
        var impulse = ((f.motionImpulse * beatWeight + pcmKick * 0.5f) * p.beatResponse.coerceIn(0f, 2f)).coerceIn(0f, 1.5f)
        if (idleBlend > 0.5f && idleImpulseAge >= IDLE_IMPULSE_SECONDS) {
            impulse = max(impulse, IDLE_IMPULSE)
            idleImpulseAge = 0f
        } else if (impulse > 0.2f) {
            idleImpulseAge = 0f
        }
        bank.advance(
            dt = dt,
            target = target,
            impulse = impulse,
            species = forcedSpecies(style.forcedSpecies ?: p.hyperSpecies),
            lifetime = p.hyperLifetime.coerceIn(2f, 60f),
            spread = spread,
            sizeScale = HyperspaceLook.bodySize(target),
            motion = profile.motion * pace,
            orbitScale = p.hyperOrbit.coerceIn(0f, 3f),
            spinScale = p.hyperSpin.coerceIn(0f, 3f),
        )
        val meltAmount = if (melt.available) (p.hyperMelt * style.meltScale).coerceIn(0f, 2f) else 0f
        val hueBase = FluidHue.base(p.paletteBase)
        val hueSpan = FluidHue.span(p.hueRange, p.paletteRange)
        if (melt.available) {
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
            stirWithBodies(p, hueBase, hueSpan)
            melt.step(f, dt, p, hueBase, hueSpan)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
            GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        }

        bloomCount =
            bank.snapshot(
                p.hyperFold,
                bloomPos,
                bloomShape,
                bloomLook,
                bloomRot,
                boundInflate = MeltMath.reach(meltAmount, MeltMath.DEFAULT_SCALE),
            )

        camDistance =
            HyperspaceLook.cameraDistance(
                actCamera = profile.camera,
                spread = spread,
                maxBodyRadius = HyperspaceLook.maxBodyRadius(target),
                cameraScale = style.cameraScale,
            )
        camera.advance(
            dt = dt,
            distance = camDistance,
            drift = (p.hyperCamera * style.driftScale).coerceIn(0f, 3f) * pace,
        )
        farPlane = HyperspaceLook.farPlane(camDistance, spread)

        beatPulse =
            maxOf((f.motionImpulse * beatWeight + pcmKick * 0.6f) * p.beatResponse.coerceIn(0f, 2f), beatPulse - dt * 3f)
                .coerceIn(0f, 1.5f)
        val budget = MarchBudget.forDetail(p.hyperDetail)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(loc("uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(loc("uTime"), time)
        GLES30.glUniform1i(loc("uBloomCount"), bloomCount)
        GLES30.glUniform4fv(loc("uBloomPos"), uniforms.arrayCount("uBloomPos", HyperspaceMath.MAX_BLOOMS), bloomPos, 0)
        GLES30.glUniform4fv(loc("uBloomShape"), uniforms.arrayCount("uBloomShape", HyperspaceMath.MAX_BLOOMS), bloomShape, 0)
        GLES30.glUniform4fv(loc("uBloomLook"), uniforms.arrayCount("uBloomLook", HyperspaceMath.MAX_BLOOMS), bloomLook, 0)
        GLES30.glUniformMatrix3fv(loc("uBloomRot"), uniforms.arrayCount("uBloomRot", HyperspaceMath.MAX_BLOOMS), false, bloomRot, 0)
        GLES30.glUniform3f(loc("uCamPos"), camera.position[0], camera.position[1], camera.position[2])
        GLES30.glUniformMatrix3fv(loc("uCamBasis"), 1, false, camera.basis, 0)
        GLES30.glUniform1f(loc("uFov"), FOV)
        GLES30.glUniform1i(loc("uStyle"), style.shaderStyle)
        GLES30.glUniform1f(loc("uLipschitz"), style.lipschitz.coerceAtLeast(1f))
        GLES30.glUniform1f(loc("uStyleFloor"), style.signatureFloor.coerceIn(0f, 1f))
        GLES30.glUniform1f(loc("uStyleKaleido"), styleKaleidoFolds(profile, p))
        GLES30.glUniform3f(loc("uStyleTint"), style.tintHue, style.tintSat, style.tintAmount)
        GLES30.glUniform1f(loc("uSlewBass"), slewBass)
        GLES30.glUniform1f(loc("uSlewMid"), slewMid)
        GLES30.glUniform1f(loc("uStylePhase"), stylePhase)
        GLES30.glUniform1fv(loc("uBands"), uniforms.arrayCount("uBands", SpectralSummary.SIZE), spectral.levels, 0)
        GLES30.glUniform1i(loc("uSteps"), budget.steps)
        GLES30.glUniform1i(loc("uIters"), budget.iterations)
        GLES30.glUniform1i(loc("uBulbIters"), budget.bulbIterations)
        GLES30.glUniform1i(loc("uSeedIters"), budget.seedIterations)
        GLES30.glUniform1f(loc("uFar"), farPlane)
        GLES30.glUniform1f(loc("uMaxStep"), HyperspaceLook.maxMarchStep(MeltMath.DEFAULT_SCALE))
        GLES30.glUniform1f(loc("uHitEps"), HyperspaceLook.HIT_EPSILON)
        GLES30.glUniform1f(loc("uBoundMargin"), HyperspaceLook.BOUND_MARGIN)
        GLES30.glUniform1f(loc("uField"), profile.field * (p.hyperField * style.fieldScale).coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uMirror"), profile.mirror)
        GLES30.glUniform1f(loc("uMirrorFolds"), p.hyperMirrorFolds.coerceIn(2, 16).toFloat())
        GLES30.glUniform1f(loc("uGlow"), profile.glow * (p.hyperGlow * style.glowScale).coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uNeon"), (p.hyperNeon * style.neonScale).coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uHaze"), (p.hyperHaze * style.hazeScale).coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uTrapColor"), p.hyperTrap.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uHueSpread"), profile.hueSpread)
        GLES30.glUniform1f(loc("uBaseHue"), hueBase)
        GLES30.glUniform1f(loc("uHueSpan"), hueSpan)
        GLES30.glUniform1f(loc("uHasMelt"), if (melt.available) 1f else 0f)
        GLES30.glUniform1f(loc("uMelt"), meltAmount)
        GLES30.glUniform1f(
            loc("uFlowGain"),
            melt.flowScale * MeltMath.DEFAULT_SCALE * MeltMath.MELT_SECONDS,
        )
        GLES30.glUniform1f(loc("uMeltReach"), MeltMath.reach(meltAmount, MeltMath.DEFAULT_SCALE))
        GLES30.glUniform1f(loc("uMeltScale"), MeltMath.DEFAULT_SCALE)
        GLES30.glUniform1f(loc("uMeltAspect"), melt.aspect)
        GLES30.glUniform1f(loc("uMeltRelax"), MeltMath.stepRelaxation(meltAmount))
        GLES30.glUniform1f(loc("uStain"), if (melt.available) (p.hyperStain * style.stainScale).coerceIn(0f, 1.5f) else 0f)
        GLES30.glUniform1f(loc("uLiquid"), if (melt.available) (p.hyperLiquid * style.liquidScale).coerceIn(0f, 1.5f) else 0f)
        GLES30.glUniform1f(loc("uRidges"), if (melt.available) (p.hyperRidges * style.ridgeScale).coerceIn(0f, 1f) else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, melt.velocityTex)
        GLES30.glUniform1i(loc("uFlowTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, melt.dyeTex)
        GLES30.glUniform1i(loc("uDyeTex"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1f(loc("uEnergy"), f.rms.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uBass"), f.bass.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uTreble"), f.treble.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uBeat"), beatPulse)
        GLES30.glUniform1f(loc("uExposure"), EXPOSURE)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun stirWithBodies(
        p: SceneParams,
        hueBase: Float,
        hueSpan: Float,
    ) {
        val strength =
            (p.hyperStain * style.stainScale).coerceIn(0f, 1.5f) +
                (p.hyperLiquid * style.liquidScale).coerceIn(0f, 1.5f)
        if (strength <= 0.01f) {
            trackBodyPositions()
            return
        }
        val blooms = bank.blooms
        for (i in blooms.indices) {
            val b = blooms[i]
            if (!b.alive || b.fade <= 0.01f) {
                hasPrevBody[i] = false
                continue
            }
            val x = b.centre[0]
            val y = b.centre[1]
            if (hasPrevBody[i]) {
                FluidHue.rgb(hueBase + b.hue * hueSpan, 0.95f, bodyRgb)
                melt.queueBodySplat(
                    prevWorldX = prevBodyXy[i * 2],
                    prevWorldY = prevBodyXy[i * 2 + 1],
                    worldX = x,
                    worldY = y,
                    radius = HyperspaceMath.localRadius(b.species) * b.scale * b.fade,
                    life = b.fade,
                    scale = MeltMath.DEFAULT_SCALE,
                    r = bodyRgb[0],
                    g = bodyRgb[1],
                    b = bodyRgb[2],
                    strength = BODY_INK * strength * b.fade,
                )
            }
            prevBodyXy[i * 2] = x
            prevBodyXy[i * 2 + 1] = y
            hasPrevBody[i] = true
        }
    }

    private fun trackBodyPositions() {
        val blooms = bank.blooms
        for (i in blooms.indices) {
            val b = blooms[i]
            if (!b.alive) {
                hasPrevBody[i] = false
                continue
            }
            prevBodyXy[i * 2] = b.centre[0]
            prevBodyXy[i * 2 + 1] = b.centre[1]
            hasPrevBody[i] = true
        }
    }

    private fun forcedSpecies(choice: Int): HyperspaceMath.Species? =
        if (choice <= 0) null else HyperspaceMath.SPECIES.getOrNull(choice - 1)

    private fun styleKaleidoFolds(
        profile: HyperspaceMath.ActProfile,
        p: SceneParams,
    ): Float {
        if (style.kaleidoFolds <= 0 || profile.styleMirror < 0.5f) return 0f
        val folds = Math.round(style.kaleidoFolds * p.hyperMirrorFolds.coerceIn(2, 16) / 6f)
        return folds.coerceIn(2, 16).toFloat()
    }

    private fun loc(name: String): Int = uniforms.loc(name)

    override fun release() {
        melt.release()
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        program = 0
        vao = 0
        programOk = false
        uniforms = GlUtil.UniformCache(0)
    }
}
