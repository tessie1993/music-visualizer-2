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
 * a sphere packing, a kaleidoscopic IFS, a box, a Kleinian coral, a bulb) and
 * every one of them has its OWN rotation, its own orbit, its own colour and its
 * own life. They are born on transients, they grow out of nothing, they drift
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
 *   scenes without a grading pass of their own, this one included.
 * - A synthetic idle drive when nothing is playing. Here that is a slow swell
 *   that walks the journey through all five acts on its own, so an idle app or
 *   a live wallpaper shows the whole story rather than parking on the empty
 *   opening act forever.
 */
internal class HyperspaceScene(
    private val context: Context,
) : Scene {
    override val id: String = SceneIds.HYPERSPACE

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
        time += dt
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
        idlePhase += dt / IDLE_CYCLE_SECONDS
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
        )
        val profile = journey.profile()

        // ---- the bodies ----------------------------------------------------
        val target = HyperspaceLook.bodyTarget(profile.bodies, p.hyperBodies)
        val spread = HyperspaceLook.spread(target)
        // Impulse gates spawning. In silence the scene fakes one every couple
        // of seconds, so an idle app still fills instead of holding whatever
        // was alive when the music stopped.
        idleImpulseAge += dt
        var impulse = (f.motionImpulse * p.beatResponse.coerceIn(0f, 2f)).coerceIn(0f, 1.5f)
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
            species = forcedSpecies(p.hyperSpecies),
            lifetime = p.hyperLifetime.coerceIn(2f, 60f),
            spread = spread,
            sizeScale = HyperspaceLook.bodySize(target),
            motion = profile.motion * pace * p.hyperSpin.coerceIn(0f, 3f),
            orbitScale = p.hyperOrbit.coerceIn(0f, 3f),
        )
        // ---- the medium ----------------------------------------------------
        // Stepped BEFORE the snapshot so this frame's uniforms and this
        // frame's fluid describe the same instant, and before anything is
        // drawn: the sim binds its own framebuffers, and the renderer already
        // has the scene target bound by the time draw() runs.
        val meltAmount = if (melt.available) p.hyperMelt.coerceIn(0f, 2f) else 0f
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
        camDistance =
            HyperspaceLook.cameraDistance(
                actCamera = profile.camera * p.zoom.coerceIn(0.4f, 3f),
                spread = spread,
                maxBodyRadius = HyperspaceLook.maxBodyRadius(target),
            )
        camera.advance(
            dt = dt,
            distance = camDistance,
            drift = p.hyperCamera.coerceIn(0f, 3f) * pace,
            roll = p.rotation * time,
        )
        farPlane = HyperspaceLook.farPlane(camDistance, spread)

        beatPulse = maxOf(f.motionImpulse * p.beatResponse.coerceIn(0f, 2f), beatPulse - dt * 3f).coerceIn(0f, 1.5f)
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
        GLES30.glUniform1i(loc("uSteps"), budget.steps)
        GLES30.glUniform1i(loc("uIters"), budget.iterations)
        GLES30.glUniform1i(loc("uBulbIters"), budget.bulbIterations)
        GLES30.glUniform1f(loc("uFar"), farPlane)
        GLES30.glUniform1f(loc("uHitEps"), HyperspaceLook.HIT_EPSILON)
        GLES30.glUniform1f(loc("uBoundMargin"), HyperspaceLook.BOUND_MARGIN)
        GLES30.glUniform1f(loc("uAct"), journey.actPosition)
        GLES30.glUniform1f(loc("uField"), profile.field * p.hyperField.coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uMirror"), profile.mirror)
        GLES30.glUniform1f(loc("uMirrorFolds"), p.hyperMirrorFolds.coerceIn(2, 16).toFloat())
        GLES30.glUniform1f(loc("uGlow"), profile.glow * p.hyperGlow.coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uNeon"), p.hyperNeon.coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uHaze"), p.hyperHaze.coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uTrapColor"), p.hyperTrap.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uHueSpread"), profile.hueSpread)
        GLES30.glUniform1f(loc("uBaseHue"), hueBase)
        GLES30.glUniform1f(loc("uHueSpan"), hueSpan)
        // The melt. uHasMelt is the single gate: on a GPU that cannot give us
        // half-float buffers the style still runs, just as solid geometry.
        GLES30.glUniform1f(loc("uHasMelt"), if (melt.available) 1f else 0f)
        GLES30.glUniform1f(loc("uMelt"), meltAmount)
        // Grid texel -> sim units/s -> world units/s -> displacement, folded
        // into one multiply, and the reach the spheres were inflated by.
        GLES30.glUniform1f(
            loc("uMeltGain"),
            melt.flowScale * MeltMath.DEFAULT_SCALE * MeltMath.MELT_SECONDS * meltAmount,
        )
        GLES30.glUniform1f(loc("uMeltReach"), MeltMath.reach(meltAmount, MeltMath.DEFAULT_SCALE))
        GLES30.glUniform1f(loc("uMeltScale"), MeltMath.DEFAULT_SCALE)
        GLES30.glUniform1f(loc("uMeltAspect"), melt.aspect)
        GLES30.glUniform1f(loc("uMeltRelax"), MeltMath.stepRelaxation(meltAmount))
        GLES30.glUniform1f(loc("uStain"), if (melt.available) p.hyperStain.coerceIn(0f, 1.5f) else 0f)
        GLES30.glUniform1f(loc("uLiquid"), if (melt.available) p.hyperLiquid.coerceIn(0f, 1.5f) else 0f)
        GLES30.glUniform1f(loc("uRidges"), p.hyperRidges.coerceIn(0f, 1f))
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
        val strength = p.hyperStain.coerceIn(0f, 1.5f) + p.hyperLiquid.coerceIn(0f, 1.5f)
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
