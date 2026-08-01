package dev.musicviz.export

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.CompositeGrade
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.SceneParams
import kotlin.math.pow

/**
 * The composite pass' grading uniforms for one frame, in the exact shape
 * `composite_frag.glsl` reads them.
 *
 * [enabled] is load-bearing rather than decorative: the neutral value of the
 * rest of this block is 1.0, not 0.0, so a program that leaves them unset
 * reads GL's default 0 and renders black at 20x zoom. It is the `z` component
 * of the shader's per-texture gate (`CompositeGrade.Gate.grade`); when a scene
 * grades itself the gate is off AND every value here is the identity, so the
 * block is a no-op either way.
 */
internal data class ExportGradeUniforms(
    val enabled: Boolean,
    val zoom: Float,
    val rotation: Float,
    val saturation: Float,
    val brightness: Float,
    val contrast: Float,
    val gamma: Float,
    val hue: Float,
)

/**
 * Export-side mirror of the live renderer's composite grading state.
 *
 * Rotation is a SPEED in every scene (`rotationAngle += p.rotation * dt`) and
 * the colour cycle is a phase, so the composite pass integrates both itself
 * instead of feeding the raw sliders through as static offsets. An export
 * renders on its own clock, so [advance] is driven by the export's frame delta
 * (1/fps): ten seconds of exported video then spins and cycles exactly as far
 * as ten seconds of live playback, at any frame rate.
 *
 * Kept out of [FxCompositor] itself (and free of GL calls) so the headless
 * gate can drive it frame by frame - see `ExportCompositeGradeTest`.
 */
internal class ExportGradeState {
    /** Integrated rotation angle, in radians, wrapped to +-2*pi. */
    var rotationAngle: Float = 0f
        private set

    /** Integrated colour-cycle phase in [0,1), added to the Hue shift. */
    var cyclePhase: Float = 0f
        private set

    /**
     * Beat envelope driving the "Beat pulse" swell: a peak-hold of the graded
     * impulse that decays at [CompositeGrade.BEAT_DECAY]. NOT 1 on every beat
     * - it rises to how hard the hit actually was, so a soft verse hit leaves
     * a smaller swell than a drop. [pulseAmount] then SQUARES it.
     */
    var beatPulse: Float = 0f
        private set

    /** Advances one exported frame; [dtSeconds] is the export's 1/fps.
     *  [impulse] is the exported frame's graded motion impulse - production
     *  passes [dev.musicviz.analysis.AudioFeatures.motionImpulse], matching
     *  `VisualizerRenderer`'s live `postBeatPulse`; it is the beat impulse
     *  topped up by off-grid transients, so the two differ whenever a
     *  suppressed transient is present. It decays on the export's own clock so
     *  a 30 fps and a 60 fps render pulse for the same wall time. */
    fun advance(
        params: SceneParams,
        dtSeconds: Float,
        impulse: Float,
    ) {
        rotationAngle = CompositeGrade.integrateRotation(rotationAngle, params.rotation, dtSeconds)
        cyclePhase = CompositeGrade.integrateCyclePhase(cyclePhase, params.cycleSpeed, dtSeconds, params.colorCycle)
        beatPulse = CompositeGrade.integrateBeatPulse(beatPulse, impulse, dtSeconds)
    }

    /** Boolean convenience (a beat = a full-strength kick), for callers and
     *  tests without a graded impulse. */
    fun advance(
        params: SceneParams,
        dtSeconds: Float,
        beat: Boolean = false,
    ) = advance(params, dtSeconds, if (beat) 1f else 0f)

    /**
     * The value to upload as `uPostPulse`, gated on a DIFFERENT set from
     * [uniforms]: only ShaderScene (uPulse) and the particle pipeline (a uSize
     * swell) read `pulse` themselves, so only they are neutralised. MilkDrop
     * grades itself but never pulses, so it is graded in its own pass yet
     * pulsed in the composite - see `VisualizerRenderer`'s matching comment.
     */
    fun pulseAmount(
        params: SceneParams,
        pulsesItself: Boolean,
    ): Float = if (pulsesItself) 0f else CompositeGrade.pulseAmount(params.pulse, beatPulse)

    /**
     * The uniforms to upload, gated exactly like `VisualizerRenderer`'s
     * composite pass: scenes that grade themselves (shader, particle,
     * milkdrop) get the neutral identity and the disable flag, so they stay
     * bit-identical; only the fluid family (Fluid, Curl Flow, Water), which
     * grades nothing of its own, is graded here.
     */
    fun uniforms(
        params: SceneParams,
        gradesItself: Boolean,
    ): ExportGradeUniforms =
        if (gradesItself) {
            ExportGradeUniforms(
                enabled = false,
                zoom = 1f,
                rotation = 0f,
                saturation = 1f,
                brightness = 1f,
                contrast = 1f,
                gamma = 1f,
                hue = 0f,
            )
        } else {
            ExportGradeUniforms(
                enabled = true,
                zoom = params.zoom,
                rotation = rotationAngle,
                saturation = params.saturation,
                brightness = CompositeGrade.brightness(params.brightness, params.intensity),
                contrast = params.contrast,
                gamma = params.gamma,
                hue = params.colorShift + cyclePhase,
            )
        }
}

/**
 * Applies MusicViz's screen-space composite FX chain (geometry, chromatic
 * aberration, vignette, scanlines, grain, glitch, fisheye, strobe, bloom,
 * posterize, invert) plus the universal colour grade / zoom / rotation to an
 * exported frame, exactly as the live renderer does.
 *
 * The scene is drawn into [sceneFbo] instead of straight to the encoder
 * surface; each frame we then draw a fullscreen quad sampling that texture
 * through the composite shader onto the encoder surface. Without this pass,
 * exports would omit every composite-only customization - most visibly on
 * particle scenes, whose own pipeline can't honor shape/color params.
 */
internal class FxCompositor(
    context: Context,
    val width: Int,
    val height: Int,
) {
    private val program = GlUtil.buildProgram(loadRaw(context, R.raw.fade_vert), loadRaw(context, R.raw.composite_frag))
    private val fadeProgram = GlUtil.buildProgram(loadRaw(context, R.raw.fade_vert), loadRaw(context, R.raw.fade_frag))
    private val trailWarpProgram = GlUtil.buildProgram(loadRaw(context, R.raw.fade_vert), loadRaw(context, R.raw.trail_warp_frag))
    private var trailTex = 0
    private var trailFbo = 0
    private var trailW = 0
    private var trailH = 0
    private val vao: Int
    val sceneFbo: Int
    private val sceneTex: Int
    private val emptyTex: Int

    /** Depth renderbuffer for 3D scenes; 0 until [ensureSceneDepth]. */
    private var sceneDepth = 0

    /** Integrated rotation angle / colour-cycle phase for the grade block. */
    private val grade = ExportGradeState()

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        // Colour texture the scene renders into.
        GLES30.glGenTextures(1, ids, 0)
        sceneTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
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
        sceneFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            sceneTex,
            0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // A 1x1 texture for the unused second (transition) sampler.
        GLES30.glGenTextures(1, ids, 0)
        emptyTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, emptyTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            1,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
    }

    /**
     * Attaches a depth renderbuffer to [sceneFbo], for scenes that draw real
     * 3D geometry ([dev.musicviz.render.scene.Scene.needsDepth]).
     *
     * The live renderer does the same to its own targets, and for the same
     * reason: without it the far side of a surface paints over the near side.
     * Called once, before the frame loop - an export renders exactly one
     * scene, so there is nothing to re-decide per frame.
     */
    fun ensureSceneDepth() {
        if (sceneDepth != 0) return
        val ids = IntArray(1)
        GLES30.glGenRenderbuffers(1, ids, 0)
        sceneDepth = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            // Same fallback as the live path: render without depth testing
            // rather than fail the export outright.
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
            sceneDepth = 0
        }
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Binds the scene FBO so the next scene.draw() renders into it. */
    fun bindSceneTarget() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, width, height)
    }

    /**
     * Fades the scene FBO toward black instead of clearing it, so particle
     * trails render in exports exactly like the live view (which fades FBO A
     * Warp-aware trail fade for export parity with the live renderer: when
     * trailZoom/trailWarp are set, the persisted frame is copied aside and
     * redrawn zoomed/warped/decayed; otherwise the plain alpha fade runs.
     * [sceneFbo]/[w]/[h] describe the scene target currently being faded.
     */
    fun fadeSceneTargetWarp(
        params: dev.musicviz.render.scene.SceneParams,
        sceneFbo: Int,
        w: Int,
        h: Int,
        timeSeconds: Float,
        dtSeconds: Float = 1f / 60f,
    ) {
        if (params.trailZoom == 0f && params.trailWarp <= 0f) {
            fadeSceneTarget(params.trailLength, dtSeconds)
            return
        }
        ensureTrailBuffer(w, h)
        if (trailFbo == 0) {
            fadeSceneTarget(params.trailLength, dtSeconds)
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sceneFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, trailFbo)
        GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(trailWarpProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(trailWarpProgram, "uPrev"), 0)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(trailWarpProgram, "uDecay"),
            (params.trailLength * 0.97f + 0.02f).coerceIn(0f, 0.99f).pow(dtSeconds * 60f),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(trailWarpProgram, "uZoom"), params.trailZoom)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(trailWarpProgram, "uWarp"), params.trailWarp)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(trailWarpProgram, "uTime"), timeSeconds)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun ensureTrailBuffer(
        w: Int,
        h: Int,
    ) {
        if (trailTex != 0 && trailW == w && trailH == h) return
        releaseTrailBuffer()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        trailTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        trailFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, trailTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            releaseTrailBuffer()
        }
        trailW = w
        trailH = h
    }

    private fun releaseTrailBuffer() {
        if (trailTex != 0) GLES30.glDeleteTextures(1, intArrayOf(trailTex), 0)
        if (trailFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(trailFbo), 0)
        trailTex = 0
        trailFbo = 0
    }

    fun fadeSceneTarget(
        trailLength: Float,
        dtSeconds: Float = 1f / 60f,
    ) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(fadeProgram)
        // Retention^(dt*60): matches the live renderer's frame-rate-independent
        // fade so a 30 fps export decays trails like the 60 Hz live view.
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(fadeProgram, "uFadeAlpha"),
            (1f - (trailLength * 0.97f).pow(dtSeconds * 60f)).coerceIn(0.02f, 1f),
        )
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Composites the scene texture (with FX) onto the currently-bound surface.
     *  Call exactly once per exported frame: [dtSeconds] (the export's 1/fps)
     *  advances the integrated rotation angle and colour-cycle phase, so the
     *  spin/cycle of a rendered clip matches the same span of live playback.
     *  [flowTex]/[flowStrength] feed the fluidWarp slot so FlowField bending
     *  appears in exports exactly like the live view (0 = disabled).
     *  [rippleTex]/[rippleTexelW]/[rippleTexelH]/[rippleStrength]/
     *  [rippleSpecular] feed the F2 ripple overlay slot the same way (0 =
     *  disabled; the 1x1 empty texture keeps the sampler valid). */
    fun composite(
        timeSeconds: Float,
        dtSeconds: Float,
        features: AudioFeatures,
        isParticle: Boolean,
        isShaderScene: Boolean,
        isProjectM: Boolean,
        params: SceneParams,
        flowTex: Int = 0,
        flowStrength: Float = 0f,
        rippleTex: Int = 0,
        rippleTexelW: Float = 0f,
        rippleTexelH: Float = 0f,
        rippleStrength: Float = 0f,
        rippleSpecular: Float = 0f,
        /** `uStrobeHz`; see `VisualSafety.strobeHz`. The default is the rate
         *  that used to be a literal in the shader, so an export with safety
         *  off is unchanged. */
        strobeHz: Float = dev.musicviz.render.VisualSafety.DEFAULT_STROBE_HZ,
    ) {
        // Rotation and the colour cycle are SPEEDS: integrate them on the
        // export's own clock, once per exported frame, exactly as the live
        // renderer integrates once per displayed frame.
        grade.advance(params, dtSeconds, features.motionImpulse)
        // Which uPost* groups the composite owns is decided by the gate, not
        // by neutralising the values: exports never transition, so both gate
        // slots carry the same scene family, but the two programs must declare
        // and upload the same uniform set or a later change to one desyncs the
        // other (an export that no longer matches the screen).
        val family =
            when {
                isShaderScene -> CompositeGrade.SceneFamily.SHADER
                isParticle -> CompositeGrade.SceneFamily.PARTICLE
                isProjectM -> CompositeGrade.SceneFamily.MILKDROP
                else -> CompositeGrade.SceneFamily.FLUID
            }
        val gate = CompositeGrade.gateFor(family)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glUniform1i(loc("uTexA"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, emptyTex)
        GLES30.glUniform1i(loc("uTexB"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (flowTex != 0) flowTex else emptyTex)
        GLES30.glUniform1i(loc("uFlow"), 2)
        GLES30.glUniform1f(loc("uFlowStrength"), if (flowTex != 0) flowStrength else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (rippleTex != 0) rippleTex else emptyTex)
        GLES30.glUniform1i(loc("uRipple"), 3)
        GLES30.glUniform2f(loc("uRippleTexel"), rippleTexelW, rippleTexelH)
        GLES30.glUniform1f(loc("uRippleStrength"), if (rippleTex != 0) rippleStrength else 0f)
        GLES30.glUniform1f(loc("uRippleSpecular"), if (rippleTex != 0) rippleSpecular else 0f)
        GLES30.glUniform1f(loc("uProgress"), 1f)
        GLES30.glUniform1i(loc("uStyle"), 0)
        GLES30.glUniform1f(loc("uTime"), timeSeconds)
        GLES30.glUniform1f(loc("uBeat"), features.beatImpulse)
        GLES30.glUniform1f(loc("uChroma"), params.chromaAb)
        GLES30.glUniform1f(loc("uVignette"), params.vignette)
        GLES30.glUniform1f(loc("uScanline"), params.scanlines)
        GLES30.glUniform1f(loc("uGrain"), params.grain)
        GLES30.glUniform1f(loc("uGlitch"), params.glitch)
        GLES30.glUniform1f(loc("uFisheye"), params.fisheye)
        GLES30.glUniform1f(loc("uStrobe"), params.strobe)
        GLES30.glUniform1f(loc("uStrobeHz"), strobeHz)
        GLES30.glUniform1f(loc("uPostWarp"), params.warp)
        GLES30.glUniform1f(loc("uPostRipple"), params.ripple)
        GLES30.glUniform1f(loc("uPostSymmetry"), params.symmetry.toFloat())
        GLES30.glUniform1f(loc("uPostKaleido"), if (params.kaleidoscope) 1f else 0f)
        GLES30.glUniform1f(loc("uPostPixelate"), params.pixelate)
        GLES30.glUniform1f(loc("uPostTile"), params.tile)
        GLES30.glUniform1f(loc("uPostTwist"), params.twist)
        GLES30.glUniform1f(loc("uPostBloom"), params.bloom)
        GLES30.glUniform1f(loc("uPostPosterize"), params.posterize)
        GLES30.glUniform1f(loc("uPostDriftX"), params.driftX)
        GLES30.glUniform1f(loc("uPostDriftY"), params.driftY)
        GLES30.glUniform1f(loc("uPostSway"), params.sway)
        GLES30.glUniform1f(loc("uPostShake"), params.shake)
        GLES30.glUniform1f(loc("uPostFlash"), params.flash)
        GLES30.glUniform1f(loc("uPostTemp"), params.temperature)
        GLES30.glUniform1f(loc("uPostSolarize"), if (params.solarize) 1f else 0f)
        // Match the live renderer: shader scenes AND the milkdrop post pass
        // apply mirror/invert themselves; everything else needs them here -
        // particle scenes (whose fragment shader defers invert to this pass)
        // and the fluid family, which applies neither. (Gate component y.)
        GLES30.glUniform1f(loc("uPostMirror"), if (params.mirror) 1f else 0f)
        GLES30.glUniform1f(loc("uPostInvert"), if (params.invert) 1f else 0f)
        // Universal grading + zoom/rotation, same gate as the live renderer:
        // ShaderScene (view()/grade()), the particle pipeline (particle_vert's
        // uZoom/uRotation, particle_frag's uSat/uBright/uContrast/uGamma) and
        // the milkdrop post pass grade in their OWN pass and get the neutral
        // identity here; only the fluid family (Fluid, Curl Flow, Water) is
        // graded in the composite. Without this block an exported fluid clip
        // came out ungraded while the live view was graded.
        val gu = grade.uniforms(params, gradesItself = !gate.grade)
        GLES30.glUniform1f(loc("uPostZoom"), gu.zoom)
        GLES30.glUniform1f(loc("uPostRotation"), gu.rotation)
        GLES30.glUniform1f(loc("uPostSat"), gu.saturation)
        GLES30.glUniform1f(loc("uPostBright"), gu.brightness)
        GLES30.glUniform1f(loc("uPostContrast"), gu.contrast)
        GLES30.glUniform1f(loc("uPostGamma"), gu.gamma)
        GLES30.glUniform1f(loc("uPostHue"), gu.hue)
        // "Beat pulse": mirrors the live renderer, including its deliberately
        // different gate - milkdrop is excluded from the grade block above but
        // pulsed here, because nothing in its pipeline reads `pulse`. Uploaded
        // unconditionally so exports never diverge from the live view; the
        // uniform is neutral at 0, so a self-pulsing scene is a no-op.
        GLES30.glUniform1f(loc("uPostPulse"), grade.pulseAmount(params, pulsesItself = !gate.pulse))
        // Both gate slots carry the same family: an export renders one scene,
        // never a transition (uProgress = 1, uStyle = 0, so uTexB is unread).
        val gateVec = gate.toVec4()
        GLES30.glUniform4fv(loc("uGateA"), 1, gateVec, 0)
        GLES30.glUniform4fv(loc("uGateB"), 1, gateVec, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        val ids = intArrayOf(sceneTex, emptyTex)
        GLES30.glDeleteTextures(2, ids, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
        if (sceneDepth != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteProgram(fadeProgram)
        GLES30.glDeleteProgram(trailWarpProgram)
        releaseTrailBuffer()
    }

    private val uniformLocs = HashMap<String, Int>()

    private fun loc(name: String): Int = uniformLocs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun loadRaw(
        context: Context,
        resId: Int,
    ): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
}
