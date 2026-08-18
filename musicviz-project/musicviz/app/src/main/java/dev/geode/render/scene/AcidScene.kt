package dev.geode.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.geode.R
import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FluidBuffers
import dev.geode.render.fluid.FluidHue
import kotlin.math.max
import kotlin.math.pow

/**
 * The ACID family: a video-synthesis feedback loop.
 *
 * One RGBA8 ping-pong at capped resolution. Every frame `acid_step_frag`
 * re-samples the previous frame through a style-owned warp (zoom, rotation,
 * polar folds, log-polar throat, block glitch, mirrors, smear), rotates its
 * hue, attenuates it below unity and adds a live audio-drawn source layer -
 * a chroma mandala, band rings, a coarse circular spectrum or an orbiting
 * ribbon - INTO the loop, where it echoes. The source layer's brightness can
 * also displace where the feedback re-samples, the general video-synth
 * modulation idea, which welds the trails to the music.
 *
 * Ten substyles are ten warp/colour/source recipes ([VisualStyleCatalog.acid]).
 * Everything is reimplemented from the public video-synthesis algebra; no
 * external shader text is used.
 *
 * The "calm body, reactive skin" rule: the loop's slow zoom/rotation never
 * follows raw amplitude - audio reaches the picture through the source layer,
 * the beat-gated glitch window and the strike-lifted treble, so the space
 * feels stable while its skin is alive.
 *
 * SAFETY: feedback survival is hard-capped below 1, the state is clamped both
 * sides of the loop, glitch is an envelope (never a strobe), and the scene
 * runs under the same composite grading and flash budget as every other.
 */
internal class AcidScene(
    private val context: Context,
    private val style: VisualStyleCatalog.AcidStyle,
) : Scene,
    PcmSink {
    override val id: String = style.id

    private companion object {
        /** Feedback short side, texels: echoes soften anyway, full res is waste. */
        const val SIM_RES = 540

        const val TIME_WRAP_SECONDS = 628.31853f

        /** Absolute cap on feedback survival, whatever a style declares. */
        const val FEEDBACK_CAP = 0.975f

        /** Beat level that opens the glitch window. */
        const val GLITCH_THRESHOLD = 0.32f

        /** Glitch envelope decay per second. */
        const val GLITCH_DECAY = 2.4f

        const val ENV_RISE_PER_SEC = 9f
        const val ENV_FALL_PER_SEC = 2.4f
    }

    private var params = SceneParams.DEFAULT
    private var pendingFeatures: AudioFeatures? = null
    private val silence = AudioFeatures.empty()
    private var width = 1
    private var height = 1
    private var time = 0f
    private var lastDt = 1f / 60f

    private var stepProgram = 0
    private var showProgram = 0
    private var stepLocs = GlUtil.UniformCache(0)
    private var showLocs = GlUtil.UniformCache(0)
    private var programOk = false
    private var vao = 0
    private var state: FluidBuffers.DoubleFbo? = null

    private val pcmPulse = PcmPulse()
    private var pcmStrike = 0f
    private var envBass = 0f
    private var envMid = 0f
    private var envTreble = 0f
    private var beatPulse = 0f
    private var glitch = 0f
    private var glitchEpoch = 0f
    private val chroma = FloatArray(12)

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        stepProgram = 0
        showProgram = 0
        vao = 0
        programOk = false
        state = null
        val quad = GlUtil.loadShader(context, R.raw.quad_vert)
        stepProgram =
            GlUtil.buildProgramReporting(quad, GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.acid_step_frag)) {
                onShaderError("Acid unavailable on this GPU: $it")
            }
        if (stepProgram == 0) return
        showProgram =
            GlUtil.buildProgramReporting(quad, GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.acid_show_frag)) {
                onShaderError("Acid unavailable on this GPU: $it")
            }
        if (showProgram == 0) return
        stepLocs = GlUtil.UniformCache(stepProgram)
        showLocs = GlUtil.UniformCache(showProgram)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        programOk = true
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
        state?.release()
        state = null
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
    }

    private fun ensureState(): FluidBuffers.DoubleFbo? {
        state?.let { return it }
        // RGBA8 by design: an echo loop tolerates 8 bits, and the fallback
        // question float targets pose does not arise at all.
        val fmt = FluidBuffers.TexFormat(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
        val (w, h) = FluidBuffers.resolution(SIM_RES, width, height)
        val next = FluidBuffers.DoubleFbo(w, h, fmt, linear = true)
        next.create()
        if (!next.ok) {
            next.release()
            return null
        }
        state = next
        return next
    }

    private fun slew(
        current: Float,
        target: Float,
        dt: Float,
    ): Float {
        val limit = if (target > current) ENV_RISE_PER_SEC else ENV_FALL_PER_SEC
        return current + (target - current).coerceIn(-limit * dt, limit * dt)
    }

    override fun draw(timeSeconds: Float) {
        if (!programOk) return
        GlUtil.resetFrameState()
        val loop = ensureState() ?: return
        val p = params
        val dt = lastDt.coerceIn(0f, 1f / 15f)
        val f = pendingFeatures ?: silence
        pendingFeatures = null

        val speed = p.speed.coerceIn(0.05f, 4f)
        envBass = slew(envBass, f.bass.coerceIn(0f, 1.5f), dt)
        envMid = slew(envMid, f.mid.coerceIn(0f, 1.5f), dt)
        envTreble = slew(envTreble, f.treble.coerceIn(0f, 1.5f), dt)
        beatPulse =
            maxOf(f.motionImpulse * p.beatResponse.coerceIn(0f, 2f), beatPulse - dt * 3f)
                .coerceIn(0f, 1.5f)
        if (f.beatImpulse * p.beatResponse > GLITCH_THRESHOLD) {
            glitch = 1f
            glitchEpoch = (glitchEpoch + 1f) % 1024f
        }
        glitch = (glitch - dt * GLITCH_DECAY).coerceAtLeast(0f)
        if (f.hasChroma) {
            for (i in 0 until 12) chroma[i] = f.chroma[i].coerceIn(0f, 1f)
        } else {
            for (i in 0 until 12) chroma[i] = 0f
        }

        // Frame-rate-compensated loop constants: survival, zoom and rotation
        // are per-frame quantities at the authored 60 Hz.
        val frames = dt * 60f
        val feedback = (style.feedback.coerceAtMost(FEEDBACK_CAP)).pow(frames)
        val zoom = style.zoom.pow(frames)
        val rotate = style.rotate * frames * speed
        val hueShift = style.hueRate * dt * speed

        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(vao)

        GLES30.glUseProgram(stepProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, loop.write.fbo)
        GLES30.glViewport(0, 0, loop.width, loop.height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, loop.read.tex)
        GLES30.glUniform1i(stepLocs.loc("uPrev"), 0)
        GLES30.glUniform2f(stepLocs.loc("uRes"), loop.width.toFloat(), loop.height.toFloat())
        GLES30.glUniform1i(stepLocs.loc("uStyle"), style.mode)
        GLES30.glUniform1i(stepLocs.loc("uSource"), style.source)
        GLES30.glUniform1f(stepLocs.loc("uZoom"), zoom)
        GLES30.glUniform1f(stepLocs.loc("uRotate"), rotate)
        GLES30.glUniform1f(stepLocs.loc("uHueShift"), hueShift)
        GLES30.glUniform1f(stepLocs.loc("uFeedback"), feedback)
        GLES30.glUniform1f(stepLocs.loc("uModulate"), style.modulate)
        GLES30.glUniform1f(stepLocs.loc("uGlitch"), glitch * style.glitch)
        GLES30.glUniform1f(stepLocs.loc("uEpoch"), glitchEpoch)
        GLES30.glUniform1f(stepLocs.loc("uTime"), time)
        GLES30.glUniform1f(stepLocs.loc("uBass"), envBass)
        GLES30.glUniform1f(stepLocs.loc("uMid"), envMid)
        GLES30.glUniform1f(stepLocs.loc("uTreble"), envTreble)
        GLES30.glUniform1f(stepLocs.loc("uBeat"), beatPulse)
        GLES30.glUniform1f(stepLocs.loc("uStrike"), pcmStrike.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(stepLocs.loc("uDrive"), CymaticsMath.safeDrive(p.audioDrive))
        GLES30.glUniform1fv(stepLocs.loc("uChroma"), 12, chroma, 0)
        GLES30.glUniform1f(stepLocs.loc("uBaseHue"), FluidHue.base(p.paletteBase) + style.hueOffset)
        GLES30.glUniform1f(stepLocs.loc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan)
        GLES30.glUniform1f(stepLocs.loc("uOverdrive"), style.overdrive)
        GLES30.glUniform1f(stepLocs.loc("uLiquid"), style.liquid + p.turbulence.coerceIn(0f, 1f) * 0.6f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        loop.swap()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        GLES30.glUseProgram(showProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, loop.read.tex)
        GLES30.glUniform1i(showLocs.loc("uState"), 0)
        GLES30.glUniform2f(showLocs.loc("uRes"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(showLocs.loc("uScanline"), style.scanline)
        GLES30.glUniform1f(showLocs.loc("uCurve"), style.curve)
        GLES30.glUniform1f(showLocs.loc("uSat"), style.saturation)
        GLES30.glUniform1f(showLocs.loc("uEnergy"), f.rms.coerceIn(0f, 1.5f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    override fun release() {
        if (stepProgram != 0) GLES30.glDeleteProgram(stepProgram)
        if (showProgram != 0) GLES30.glDeleteProgram(showProgram)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        state?.release()
        state = null
        stepProgram = 0
        showProgram = 0
        vao = 0
        programOk = false
        stepLocs = GlUtil.UniformCache(0)
        showLocs = GlUtil.UniformCache(0)
    }
}
