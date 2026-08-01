package dev.musicviz.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidHue
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
    }

    private val journey = HyperspaceJourney()
    private val camera = HyperspaceCamera()
    private var bank = BloomBank()

    private val bloomPos = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomShape = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomLook = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_VEC4)
    private val bloomRot = FloatArray(HyperspaceMath.MAX_BLOOMS * HyperspaceMath.FLOATS_PER_MAT3)
    private var bloomCount = 0

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
    }

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
        bloomCount = bank.snapshot(p.hyperFold, bloomPos, bloomShape, bloomLook, bloomRot)

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
        GLES30.glUniform1f(loc("uBaseHue"), FluidHue.base(p.paletteBase))
        GLES30.glUniform1f(loc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange))
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
     * The species new bodies take: null when the user leaves "Fractal" on
     * Mixed, in which case the bank rolls one per body - which is what makes a
     * room of them read as a place rather than as a pattern of one shape.
     */
    private fun forcedSpecies(choice: Int): HyperspaceMath.Species? =
        if (choice <= 0) null else HyperspaceMath.SPECIES.getOrNull(choice - 1)

    private fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        program = 0
        vao = 0
        programOk = false
        uniforms.clear()
    }

    private fun loadRaw(resId: Int): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
}
