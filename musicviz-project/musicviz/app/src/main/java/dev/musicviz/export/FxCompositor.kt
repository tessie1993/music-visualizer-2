package dev.musicviz.export

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.SceneParams
import kotlin.math.pow

/**
 * Applies MusicViz's screen-space composite FX chain (geometry, chromatic
 * aberration, vignette, scanlines, grain, glitch, fisheye, strobe, bloom,
 * posterize, invert) to an exported frame, exactly as the live renderer does.
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
     *  [flowTex]/[flowStrength] feed the fluidWarp slot so FlowField bending
     *  appears in exports exactly like the live view (0 = disabled).
     *  [rippleTex]/[rippleTexelW]/[rippleTexelH]/[rippleStrength]/
     *  [rippleSpecular] feed the F2 ripple overlay slot the same way (0 =
     *  disabled; the 1x1 empty texture keeps the sampler valid). */
    fun composite(
        timeSeconds: Float,
        features: AudioFeatures,
        isParticle: Boolean,
        isShaderScene: Boolean,
        params: SceneParams,
        flowTex: Int = 0,
        flowStrength: Float = 0f,
        rippleTex: Int = 0,
        rippleTexelW: Float = 0f,
        rippleTexelH: Float = 0f,
        rippleStrength: Float = 0f,
        rippleSpecular: Float = 0f,
    ) {
        // Shader scenes apply all geometric/stylize FX in-shader already;
        // pass neutral values so they aren't applied twice (matches the
        // live renderer's guard).
        val applyGeo = !isShaderScene

        fun geoF(v: Float) = if (applyGeo) v else 0f
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
        GLES30.glUniform1f(loc("uBeat"), if (features.beat) 1f else 0f)
        GLES30.glUniform1f(loc("uChroma"), params.chromaAb)
        GLES30.glUniform1f(loc("uVignette"), params.vignette)
        GLES30.glUniform1f(loc("uScanline"), params.scanlines)
        GLES30.glUniform1f(loc("uGrain"), params.grain)
        GLES30.glUniform1f(loc("uGlitch"), params.glitch)
        GLES30.glUniform1f(loc("uFisheye"), params.fisheye)
        GLES30.glUniform1f(loc("uStrobe"), params.strobe)
        GLES30.glUniform1f(loc("uPostWarp"), geoF(params.warp))
        GLES30.glUniform1f(loc("uPostRipple"), geoF(params.ripple))
        GLES30.glUniform1f(loc("uPostSymmetry"), params.symmetry.toFloat())
        GLES30.glUniform1f(loc("uPostKaleido"), if (applyGeo && params.kaleidoscope) 1f else 0f)
        GLES30.glUniform1f(loc("uPostPixelate"), geoF(params.pixelate))
        GLES30.glUniform1f(loc("uPostTile"), geoF(params.tile))
        GLES30.glUniform1f(loc("uPostTwist"), geoF(params.twist))
        GLES30.glUniform1f(loc("uPostBloom"), geoF(params.bloom))
        GLES30.glUniform1f(loc("uPostPosterize"), geoF(params.posterize))
        GLES30.glUniform1f(loc("uPostDriftX"), geoF(params.driftX))
        GLES30.glUniform1f(loc("uPostDriftY"), geoF(params.driftY))
        GLES30.glUniform1f(loc("uPostSway"), geoF(params.sway))
        GLES30.glUniform1f(loc("uPostShake"), geoF(params.shake))
        GLES30.glUniform1f(loc("uPostFlash"), geoF(params.flash))
        GLES30.glUniform1f(loc("uPostTemp"), geoF(params.temperature))
        GLES30.glUniform1f(loc("uPostSolarize"), if (applyGeo && params.solarize) 1f else 0f)
        // Match the live renderer: geometric mirror/invert only for particle
        // scenes (shader scenes already apply them in-shader).
        GLES30.glUniform1f(loc("uPostMirror"), if (isParticle && params.mirror) 1f else 0f)
        GLES30.glUniform1f(loc("uPostInvert"), if (isParticle && params.invert) 1f else 0f)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        val ids = intArrayOf(sceneTex, emptyTex)
        GLES30.glDeleteTextures(2, ids, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
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
