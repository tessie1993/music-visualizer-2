package dev.musicviz.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidHue
import dev.musicviz.render.fluid.MeltField
import dev.musicviz.render.fluid.MeltMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * The HYPERSPACE style: a room full of 3D fractals, alive, telling a story.
 *
 * Every visible body is a distance-estimated 3D fractal ([HyperspaceMath.Species]:
 * a sphere packing, a kaleidoscopic IFS, a box, a Kleinian coral, a bulb, a
 * cross-section of a quaternion Julia set) and every one of them has its OWN
 * rotation, its own orbit, its own colour and its own life. They are born on
 * transients, they grow out of nothing, they drift
 * past each other on unrelated clocks, and they dissolve. That is the whole
 * design: the reference art this style was built from is never one object
 * turning, it is many things living next to each other.
 *
 * Over a track the scene walks five acts - Threshold, Chrysanthemum, Magic eye,
 * Waiting room, Breakthrough - and the act decides how many bodies live at
 * once, how strongly the background filigree is drawn, how close the camera
 * sits, how fast everything turns and how much of the colour wheel is in play.
 * Loud passages take the journey deeper, quiet ones bring it back
 * ([HyperspaceJourney]); "Journey" can also hold one act or cycle them on a
 * timer. That is what makes it tell a story rather than loop a look.
 *
 * ### How it draws
 *
 * One fullscreen fragment pass. `hyperspace_frag.glsl` raymarches the union of
 * the living bodies, each evaluated in its own rotated, scaled frame, against
 * a background filigree evaluated on the ray direction. All of the maths lives
 * in [HyperspaceMath] so it can be tested without a GPU; this class owns the
 * GL objects, the uniform packing and the audio wiring only.
 *
 * ### Conventions
 *
 * - `GlUtil.resetFrameState()` at draw entry (the fluid family's rule).
 * - Palette IDENTITY only ([FluidHue] base + span). Hue shift, the colour
 *   cycle, Brightness, Contrast and Intensity belong to the composite pass for
 *   scenes without a grading pass of their own, this one included - and so, on
 *   the same gate and for the same reason, do Zoom and Rotation. This scene
 *   reads neither; see the camera block in [draw].
 * - A synthetic idle drive when nothing is playing. Here that is a slow swell
 *   that walks the journey through all five acts on its own, so an idle app or
 *   a live wallpaper shows the whole story rather than parking on the empty
 *   opening act forever.
 */
internal class HyperspaceScene(
    private val context: Context,
    private val style: VisualStyleCatalog.HyperspaceStyle =
        requireNotNull(VisualStyleCatalog.hyperspace(SceneIds.HYPERSPACE)),
) : Scene {
    override val id: String = style.id

    private companion object {
        /** Level below which the scene considers itself undriven. */
        const val IDLE_RMS = 0.015f

        /** Seconds of silence before the idle swell is at full strength. */
        const val IDLE_FADE_SECONDS = 1.5f

        /**
         * Seconds for the idle swell to walk the journey out and back. Long on
         * purpose: this is a wallpaper's pace, not a track's, and the five acts
         * should each be somewhere to sit rather than somewhere to pass through.
         */
        const val IDLE_CYCLE_SECONDS = 150f

        /** Transient the idle drive fakes, so bodies still spawn in silence. */
        const val IDLE_IMPULSE = 0.35f

        /** Seconds between idle spawn impulses. */
        const val IDLE_IMPULSE_SECONDS = 2.2f

        /** Vertical half-extent the camera sees at unit distance. */
        const val FOV = 0.85f

        /**
         * Highlight roll-off. The shader sums a lit surface, a rim, an aura and
         * a background filigree and is HDR by construction; clipping that would
         * flatten every rim into the same white. Not a user control -
         * Brightness and Intensity are the composite pass' job.
         */
        const val EXPOSURE = 1.45f

        /**
         * Dye gain for a body's own wake. Low: eight bodies each laying ink
         * every frame saturates the field within a second at anything higher,
         * and a saturated dye field is one flat colour, not a medium.
         */
        const val BODY_INK = 0.22f

        /**
         * Rates for the slew-limited bass/mid envelopes (uSlewBass/uSlewMid),
         * in units per second. These are the ONLY audio values allowed to
         * steer geometry in the shader (fold rotations, shell swell, bulb
         * power): bounded 0..1 with a bounded rate of change, so no transient
         * can jump a body's projected area between frames - the hazard
         * VisualSafety cannot clamp. Rise faster than fall, so a drop lands
         * inside a couple of frames and releases over about a second.
         */
        const val SLEW_RISE_PER_SEC = 2.2f
        const val SLEW_FALL_PER_SEC = 1.1f
    }

    private val journey = HyperspaceJourney()
    private val camera = HyperspaceCamera()
    private var bank = BloomBank()

    /**
     * The medium. Owned by this scene rather than borrowed from the shared
     * FlowField service, because that one is velocity-only and half of what
     * makes this style liquid is the DYE - the colour a body leaves behind it
     * and then gets lit by.
     */
    private val melt = MeltField(context)

    /**
     * Where each body was last frame, in world xy, indexed by BANK SLOT.
     * Slot, not snapshot position: the snapshot packs live bodies together, so
     * its indices shuffle whenever one dies, and a capsule drawn from the
     * wrong previous point is a wake across the room to a body that never
     * went there.
     */
    private val prevBodyXy = FloatArray(HyperspaceMath.MAX_BLOOMS * 2)
    private val hasPrevBody = BooleanArray(HyperspaceMath.MAX_BLOOMS)

    private val bloomPos = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomShape = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomLook = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomRot = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_MAT3)
    private var bloomCount = 0

    /**
     * GL state snapshot around the melt sim's own passes, as fields the way
     * `WaterScene` and `CurlFlowScene` keep theirs: [update] runs once per
     * frame and this was two `IntArray` allocations each time. Written and
     * read back inside the same block, so nothing else can observe them.
     */
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    /**
     * Scratch for the per-body dye colour in [stirWithBodies], which converts
     * once per live body per frame; the `Triple` form boxes all three floats.
     * Consumed by the `queueBodySplat` call directly below each conversion.
     */
    private val bodyRgb = FloatArray(3)

    private var params = SceneParams.DEFAULT
    private var time = 0f
    private var lastDt = 1f / 60f
    private var pendingFeatures: AudioFeatures? = null
    private var width = 1
    private var height = 1

    private var program = 0
    private val uniforms = HashMap<String, Int>()
    private var programOk = false
    private var vao = 0

    private var beatPulse = 0f
    private var idleBlend = 0f
    private var idlePhase = 0f
    private var idleImpulseAge = 0f

    /** Slew-limited audio envelopes for the shader's geometry couplings. */
    private var slewBass = 0f
    private var slewMid = 0f

    /**
     * The substyle's own phase (uStylePhase), integrated here because its
     * rate rides the slewed bass (the hex tunnel flies and the wormhole
     * lurches on the low end) - a shader `uTime * k` cannot express a
     * varying rate. Wraps at 1; every shader consumer multiplies it by a
     * whole number, so the wrap never shows.
     */
    private var stylePhase = 0f

    /** The 16-bucket spectrum summary the substyle signatures read. */
    private val spectral = SpectralSummary()

    /** The far plane and camera distance the last frame resolved to. */
    private var camDistance = 6f
    private var farPlane = 12f

    private val silence = AudioFeatures.empty()

    var onShaderError: (String?) -> Unit = {}

    /** The act on screen, for anything that wants to name it. */
    val currentAct: HyperspaceMath.Act
        get() = HyperspaceMath.ACTS[journey.act.coerceIn(0, HyperspaceMath.ACTS.size - 1)]

    override fun init() {
        // Handles from a lost EGL context are dead names, never valid again.
        program = 0
        vao = 0
        uniforms.clear()
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
        try {
            program = GlUtil.buildProgram(loadRaw(R.raw.quad_vert), loadRaw(R.raw.hyperspace_frag))
            programOk = true
        } catch (e: GlUtil.ShaderCompileException) {
            // Silent black is the worst failure mode: say why instead.
            onShaderError("Hyperspace unavailable on this GPU: ${e.message}")
            return
        }
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

    /**
     * A drag, in normalized screen coordinates. Routed here by the renderer so
     * a finger stirs the medium, which then pulls the fractals it was dragged
     * across out of shape and stains them in the same gesture.
     */
    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        strength: Float,
    ) = melt.queueTouchStroke(nx, ny, ndx, ndy, strength)

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        // Wrapped: a live wallpaper runs for days, and an unwrapped float
        // clock decays sin(uTime * k) into a stutter once its ULP passes the
        // frame advance. The period is 1000 turns of 2*pi, which every
        // multiplier in hyperspace_frag.glsl crosses on a whole turn - see
        // HyperspaceMath.TIME_WRAP_SECONDS and HyperspaceReworkTest.
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

        // ---- the story -----------------------------------------------------
        // Fades in over IDLE_FADE_SECONDS but out three times as fast: the
        // moment real audio arrives the journey is the track's again.
        val silent = f.rms < IDLE_RMS
        val fadeStep = if (IDLE_FADE_SECONDS > 0f) dt / IDLE_FADE_SECONDS else 1f
        idleBlend = (idleBlend + if (silent) fadeStep else -fadeStep * 3f).coerceIn(0f, 1f)
        // Wrapped like every other clock here: consumed as cos(phase * 2pi),
        // so the wrap at 1 is exactly one period and invisible.
        idlePhase = (idlePhase + dt / IDLE_CYCLE_SECONDS) % 1f
        // macroEnergy is the track's own dynamics envelope, which is what the
        // journey is meant to follow; rms is the fallback for features that
        // predate it (synthesised frames, cache entries without analysis).
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
            // Track position floors the immersion, so a quiet track still
            // leaves THRESHOLD by its back half; 0 (unknown) floors nothing.
            progress = f.progress,
        )
        val profile = journey.profile()

        // ---- the audio envelopes the shader steers geometry with ------------
        // Slew-limited, which is the licence: bounded value, bounded rate.
        slewBass = HyperspaceMath.slewLimit(slewBass, f.bass, dt, SLEW_RISE_PER_SEC, SLEW_FALL_PER_SEC)
        slewMid = HyperspaceMath.slewLimit(slewMid, f.mid, dt, SLEW_RISE_PER_SEC, SLEW_FALL_PER_SEC)
        spectral.advance(f.bands, dt)
        stylePhase = (stylePhase + dt * pace * (style.phaseRate + style.phaseBassRate * slewBass)) % 1f
        // Beat choreography (spawns, the neon flash) is gated by the beat
        // tracker's own confidence, per its KDoc: a low-confidence grid gets
        // a reduced - never zero - weight instead of strobing false beats.
        val beatWeight = HyperspaceMath.beatGate(f.pulseConfidence)

        // ---- the bodies ----------------------------------------------------
        val target = HyperspaceLook.bodyTarget(profile.bodies, p.hyperBodies * style.bodyScale)
        val spread = HyperspaceLook.spread(target)
        // Impulse gates spawning. In silence the scene fakes one every couple
        // of seconds, so an idle app still fills instead of holding whatever
        // was alive when the music stopped.
        idleImpulseAge += dt
        var impulse = (f.motionImpulse * beatWeight * p.beatResponse.coerceIn(0f, 2f)).coerceIn(0f, 1.5f)
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
            // Three independent channels, not one product: Body spin at 0
            // used to freeze the orbits AND the breath with it, because the
            // spin multiplier was folded into the shared motion term.
            motion = profile.motion * pace,
            orbitScale = p.hyperOrbit.coerceIn(0f, 3f),
            spinScale = p.hyperSpin.coerceIn(0f, 3f),
        )
        // ---- the medium ----------------------------------------------------
        // Stepped BEFORE the snapshot so this frame's uniforms and this
        // frame's fluid describe the same instant, and before anything is
        // drawn: the sim binds its own framebuffers, and the renderer already
        // has the scene target bound by the time draw() runs.
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

        // ---- the camera ----------------------------------------------------
        // Kept outside every body: a raymarcher started inside a folded
        // distance estimator draws stripes, not an interior.
        //
        // Zoom and Rotation are deliberately NOT read here. This style has no
        // grading pass of its own, so it is in the composite's FLUID family
        // and `CompositeGrade.gateFor` hands that family the whole
        // uPostZoom..uPostHue block - the composite magnifies and turns the
        // finished frame for every one of them. A scene in that family that
        // also acts on the two undoes the composite: the camera pushed back
        // by exactly the factor the composite then magnified by, and the roll
        // turned the image by -angle against the composite's +angle. Both
        // cancelled to nothing. One layer owns them, and for this family that
        // layer is the composite - see the gate's own comment, and
        // `FxCompositor`, which shares `gateFor` so an exported clip and the
        // screen make the same decision.
        camDistance =
            HyperspaceLook.cameraDistance(
                actCamera = profile.camera,
                spread = spread,
                maxBodyRadius = HyperspaceLook.maxBodyRadius(target),
            ) * style.cameraScale
        camera.advance(
            dt = dt,
            distance = camDistance,
            // driftScale, not cameraScale: the catalog used to apply one
            // number to the eye DISTANCE and the drift RATE at once, so a
            // style asking for a wider shot also got a faster orbit.
            drift = (p.hyperCamera * style.driftScale).coerceIn(0f, 3f) * pace,
        )
        farPlane = HyperspaceLook.farPlane(camDistance, spread)

        beatPulse =
            maxOf(f.motionImpulse * beatWeight * p.beatResponse.coerceIn(0f, 2f), beatPulse - dt * 3f)
                .coerceIn(0f, 1.5f)
        val budget = MarchBudget.forDetail(p.hyperDetail)

        // ---- upload --------------------------------------------------------
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(loc("uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(loc("uTime"), time)
        GLES30.glUniform1i(loc("uBloomCount"), bloomCount)
        GLES30.glUniform4fv(loc("uBloomPos"), HyperspaceMath.MAX_BLOOMS, bloomPos, 0)
        GLES30.glUniform4fv(loc("uBloomShape"), HyperspaceMath.MAX_BLOOMS, bloomShape, 0)
        GLES30.glUniform4fv(loc("uBloomLook"), HyperspaceMath.MAX_BLOOMS, bloomLook, 0)
        GLES30.glUniformMatrix3fv(loc("uBloomRot"), HyperspaceMath.MAX_BLOOMS, false, bloomRot, 0)
        GLES30.glUniform3f(loc("uCamPos"), camera.position[0], camera.position[1], camera.position[2])
        GLES30.glUniformMatrix3fv(loc("uCamBasis"), 1, false, camera.basis, 0)
        GLES30.glUniform1f(loc("uFov"), FOV)
        GLES30.glUniform1i(loc("uStyle"), style.shaderStyle)
        // The substyle identity block, all catalog-driven: the shader holds
        // no per-style constants of its own beyond the branch bodies.
        GLES30.glUniform1f(loc("uLipschitz"), style.lipschitz.coerceAtLeast(1f))
        GLES30.glUniform1f(loc("uStyleFloor"), style.signatureFloor.coerceIn(0f, 1f))
        GLES30.glUniform1f(loc("uStyleKaleido"), styleKaleidoFolds(profile, p))
        GLES30.glUniform3f(loc("uStyleTint"), style.tintHue, style.tintSat, style.tintAmount)
        GLES30.glUniform1f(loc("uSlewBass"), slewBass)
        GLES30.glUniform1f(loc("uSlewMid"), slewMid)
        GLES30.glUniform1f(loc("uStylePhase"), stylePhase)
        GLES30.glUniform1fv(loc("uBands"), SpectralSummary.SIZE, spectral.levels, 0)
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
        // The melt. uHasMelt is the single gate: on a GPU that cannot give us
        // half-float buffers the style still runs, just as solid geometry.
        GLES30.glUniform1f(loc("uHasMelt"), if (melt.available) 1f else 0f)
        GLES30.glUniform1f(loc("uMelt"), meltAmount)
        // Grid texel -> sim units/s -> world units/s -> displacement, folded
        // into one multiply. Free of the Melt amount on purpose: this is the
        // FIELD's conversion, and the shader scales it by uMelt where it bends
        // geometry. Ridges reads it unscaled, which is why that control now
        // marks a surface whether or not the geometry is being pulled.
        GLES30.glUniform1f(
            loc("uFlowGain"),
            melt.flowScale * MeltMath.DEFAULT_SCALE * MeltMath.MELT_SECONDS,
        )
        // The reach the spheres were inflated by, and the melt's own ceiling.
        GLES30.glUniform1f(loc("uMeltReach"), MeltMath.reach(meltAmount, MeltMath.DEFAULT_SCALE))
        GLES30.glUniform1f(loc("uMeltScale"), MeltMath.DEFAULT_SCALE)
        GLES30.glUniform1f(loc("uMeltAspect"), melt.aspect)
        GLES30.glUniform1f(loc("uMeltRelax"), MeltMath.stepRelaxation(meltAmount))
        GLES30.glUniform1f(loc("uStain"), if (melt.available) (p.hyperStain * style.stainScale).coerceIn(0f, 1.5f) else 0f)
        GLES30.glUniform1f(loc("uLiquid"), if (melt.available) (p.hyperLiquid * style.liquidScale).coerceIn(0f, 1.5f) else 0f)
        // Zeroed with the medium like Ink stain and Liquid light: all three
        // read the fluid, and on a GPU that cannot run it there is no current
        // to comb along.
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

    /**
     * Every living body drops a capsule of its own colour into the medium,
     * from where it was to where it is. This is the half of the loop that
     * makes the fluid belong to the scene rather than float in front of it: a
     * body drifting past leaves a wake, a body being born blooms ink outward,
     * and the medium then carries all of it back into the geometry as the
     * melt.
     *
     * Walks the bank's SLOTS, not the packed snapshot: slots are stable across
     * frames, and the previous position is what the capsule is drawn from.
     */
    private fun stirWithBodies(
        p: SceneParams,
        hueBase: Float,
        hueSpan: Float,
    ) {
        val strength =
            (p.hyperStain * style.stainScale).coerceIn(0f, 1.5f) +
                (p.hyperLiquid * style.liquidScale).coerceIn(0f, 1.5f)
        if (strength <= 0.01f) {
            // Nothing is going to look at the dye, so nothing needs to be laid
            // down - but the previous positions must still be tracked, or the
            // first frame after it is turned back on draws one capsule from
            // wherever each body was when it was turned off.
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
                    // Scaled by the body's own life, so ink arrives with it and
                    // stops when it goes rather than snapping on and off.
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

    /**
     * The species new bodies take: null when the user leaves "Fractal" on
     * Mixed, in which case the bank rolls one per body - which is what makes a
     * room of them read as a place rather than as a pattern of one shape.
     */
    private fun forcedSpecies(choice: Int): HyperspaceMath.Species? =
        if (choice <= 0) null else HyperspaceMath.SPECIES.getOrNull(choice - 1)

    /**
     * The substyle screen pre-fold the shader applies this frame, as a fold
     * count (0 = off).
     *
     * Two things gate it, fixing the old unconditional `kaleido4`/`kaleido12`:
     * the ACT - `styleMirror` releases every fold at BREAKTHROUGH, the act
     * the profile table deliberately un-mirrors, so the substyles open with
     * it - and the USER, whose Mirror-folds control rescales the catalog
     * count around its default of 6 (Moire at the default 6 keeps its 12;
     * push the control to 12 and it doubles, capped at 16 like the control).
     */
    private fun styleKaleidoFolds(
        profile: HyperspaceMath.ActProfile,
        p: SceneParams,
    ): Float {
        if (style.kaleidoFolds <= 0 || profile.styleMirror < 0.5f) return 0f
        val folds = Math.round(style.kaleidoFolds * p.hyperMirrorFolds.coerceIn(2, 16) / 6f)
        return folds.coerceIn(2, 16).toFloat()
    }

    private fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    override fun release() {
        melt.release()
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        program = 0
        vao = 0
        programOk = false
        uniforms.clear()
    }

    /** Reads a raw shader, resolving its `//#include` directives. */
    private fun loadRaw(resId: Int): String = GlUtil.loadShader(context, resId)
}
