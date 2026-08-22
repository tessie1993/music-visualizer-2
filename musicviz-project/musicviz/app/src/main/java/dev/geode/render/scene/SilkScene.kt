package dev.geode.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.geode.R
import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FluidBuffers
import dev.geode.render.fluid.FluidHue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

internal class SilkScene(
    private val context: Context,
    private val style: VisualStyleCatalog.SilkStyle,
) : Scene,
    PcmSink {
    override val id: String = style.id

    private companion object {
        const val SIM_RES = 320

        const val BYTE_STATE_SCALE = 8f

        const val TIME_WRAP_SECONDS = 628.31853f

        const val TWO_PI = (2.0 * PI).toFloat()

        const val SEED_EPOCH_SECONDS = 9f

        const val ENV_RISE_PER_SEC = 8f
        const val ENV_FALL_PER_SEC = 2.2f

        const val RING_SPEED = 2.6f
        const val RING_MAX = 3.4f

        const val BEAT_THRESHOLD = 0.28f
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

    private var formats: FluidBuffers.Formats? = null
    private var dye: FluidBuffers.DoubleFbo? = null
    private var byteDye = false

    private val pcmPulse = PcmPulse()
    private var pcmStrike = 0f
    private var envBass = 0f
    private var envMid = 0f
    private var envTreble = 0f
    private var beatPulse = 0f
    private var ringRadius = -1f
    private var slabTurn = 0f
    private var foldPhase = 0f
    private var drift = 0f

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        stepProgram = 0
        showProgram = 0
        vao = 0
        programOk = false
        formats = null
        dye = null
        val quad = GlUtil.loadShader(context, R.raw.quad_vert)
        stepProgram =
            GlUtil.buildProgramReporting(quad, GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.silk_step_frag)) {
                onShaderError("Silk unavailable on this GPU: $it")
            }
        if (stepProgram == 0) return
        showProgram =
            GlUtil.buildProgramReporting(quad, GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.silk_show_frag)) {
                onShaderError("Silk unavailable on this GPU: $it")
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
        dye?.release()
        dye = null
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

    private fun ensureDye(): FluidBuffers.DoubleFbo? {
        dye?.let { return it }
        val fmt = formats ?: FluidBuffers.probeFormats().also { formats = it }
        byteDye = !fmt.ok
        val texFmt =
            if (byteDye) {
                FluidBuffers.TexFormat(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
            } else {
                fmt.rgba
            }
        val (w, h) = FluidBuffers.resolution(SIM_RES, width, height)
        val next = FluidBuffers.DoubleFbo(w, h, texFmt, linear = true)
        next.create()
        return if (next.ok) {
            dye = next
            next
        } else {
            next.release()
            null
        }
    }

    private fun slew(
        current: Float,
        target: Float,
        dt: Float,
    ): Float {
        val limit = if (target > current) ENV_RISE_PER_SEC else ENV_FALL_PER_SEC
        val step = (target - current).coerceIn(-limit * dt, limit * dt)
        return current + step
    }

    override fun draw(timeSeconds: Float) {
        if (!programOk) return
        GlUtil.resetFrameState()
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        val field = ensureDye() ?: return
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
        if (f.beatImpulse * p.beatResponse > BEAT_THRESHOLD) ringRadius = 0f
        if (ringRadius >= 0f) {
            ringRadius += dt * RING_SPEED * speed
            if (ringRadius > RING_MAX) ringRadius = -1f
        }

        slabTurn = (slabTurn + dt * style.slabRate * speed) % 1f
        foldPhase = (foldPhase + dt * 0.03f * speed * TWO_PI) % TWO_PI
        drift = (drift + dt * 0.05f * speed) % 1024f
        val b = style.bBase + style.bAmp * sin(TWO_PI * time / style.bPeriod)
        val seedEpoch = (time / SEED_EPOCH_SECONDS).toInt().toFloat()

        var decay = style.decay
        if (p.trails) decay += (1f - decay) * 0.6f * p.trailLength.coerceIn(0f, 1f)
        val frameDecay = decay.pow(dt * 60f)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(vao)

        GLES30.glUseProgram(stepProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, field.write.fbo)
        GLES30.glViewport(0, 0, field.width, field.height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, field.read.tex)
        GLES30.glUniform1i(stepLocs.loc("uPrev"), 0)
        GLES30.glUniform2f(stepLocs.loc("uRes"), field.width.toFloat(), field.height.toFloat())
        GLES30.glUniform1i(stepLocs.loc("uField"), style.field)
        GLES30.glUniform1f(stepLocs.loc("uB"), b)
        GLES30.glUniform1f(stepLocs.loc("uAdvect"), dt * 0.18f * style.flow * speed)
        GLES30.glUniform1f(stepLocs.loc("uDecay"), frameDecay)
        GLES30.glUniform1f(stepLocs.loc("uFieldScale"), style.fieldScale)
        GLES30.glUniform1f(stepLocs.loc("uSwirl"), style.swirl)
        GLES30.glUniform1f(stepLocs.loc("uSlabX"), cos(slabTurn * TWO_PI))
        GLES30.glUniform1f(stepLocs.loc("uSlabY"), sin(slabTurn * TWO_PI))
        GLES30.glUniform1f(stepLocs.loc("uSeedEpoch"), seedEpoch)
        GLES30.glUniform1f(stepLocs.loc("uDrift"), drift)
        GLES30.glUniform1f(stepLocs.loc("uStrokes"), style.strokes)
        GLES30.glUniform1f(stepLocs.loc("uElong"), style.elong)
        GLES30.glUniform1f(stepLocs.loc("uDrive"), CymaticsMath.safeDrive(p.audioDrive))
        GLES30.glUniform1f(stepLocs.loc("uBass"), envBass)
        GLES30.glUniform1f(stepLocs.loc("uMid"), envMid)
        GLES30.glUniform1f(stepLocs.loc("uTreble"), envTreble)
        GLES30.glUniform1f(stepLocs.loc("uBeat"), beatPulse)
        GLES30.glUniform1f(stepLocs.loc("uStrike"), pcmStrike.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(stepLocs.loc("uBeatRing"), ringRadius)
        GLES30.glUniform1f(stepLocs.loc("uStateScale"), if (byteDye) BYTE_STATE_SCALE else 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        field.swap()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        GLES30.glUseProgram(showProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, field.read.tex)
        GLES30.glUniform1i(showLocs.loc("uField"), 0)
        GLES30.glUniform2f(showLocs.loc("uRes"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(showLocs.loc("uBaseHue"), FluidHue.base(p.paletteBase) + style.hueOffset)
        GLES30.glUniform1f(showLocs.loc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan)
        GLES30.glUniform1f(showLocs.loc("uExposure"), style.exposure)
        GLES30.glUniform1i(showLocs.loc("uFold"), style.fold)
        GLES30.glUniform1f(showLocs.loc("uFoldPhase"), foldPhase)
        GLES30.glUniform1f(showLocs.loc("uEnergy"), f.rms.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(showLocs.loc("uStateScale"), if (byteDye) BYTE_STATE_SCALE else 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    override fun release() {
        if (stepProgram != 0) GLES30.glDeleteProgram(stepProgram)
        if (showProgram != 0) GLES30.glDeleteProgram(showProgram)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        dye?.release()
        dye = null
        formats = null
        stepProgram = 0
        showProgram = 0
        vao = 0
        programOk = false
        stepLocs = GlUtil.UniformCache(0)
        showLocs = GlUtil.UniformCache(0)
    }
}
