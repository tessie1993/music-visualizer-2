package dev.musicviz.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidHue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max

/**
 * The CYMATICS style: the standing-wave field of the sound, fullscreen.
 *
 * A dish of water (or a Chladni plate) driven by whatever is playing - a
 * track, or the microphone on live input. [CymaticsPlate] decides which of
 * the plate's standing waves are ringing and how hard ([CymaticsMath] for the
 * pitch -> mode law); `cymatics_field_frag.glsl` evaluates their superposition
 * PER PIXEL, edge to edge, so the picture is a depiction of the sound rather
 * than an object reacting to it. A pure tone draws one clean symmetric figure,
 * a chord draws the superposition of its notes' figures, and silence draws
 * almost nothing.
 *
 * ### How it draws
 *
 * One fullscreen fragment pass, no geometry, no camera, no letterboxing: the
 * wave field continues past every edge of the screen. Detail comes from the
 * modes the music excites - higher pitch, finer figure - and from the "Field
 * scale" control, which decides how much of the field is on screen at once.
 * Everything else is shading: nodal filigree, its halo, an embossed relief
 * from the field's own slope, a caustic sheen and iridescent dispersion.
 *
 * The modes keep MOVING: each carries a phase that advances at its own
 * strobed-down rate ([CymaticsMath.vibrationHz]), and "Flow" turns the
 * standing waves into travelling ones, so the figure flows rather than
 * standing frozen while the music changes underneath it.
 *
 * ### Conventions
 *
 * - `GlUtil.resetFrameState()` at draw entry (the fluid family's rule).
 * - Palette IDENTITY only ([FluidHue] base + span). Hue shift, the colour
 *   cycle, Brightness, Contrast and Intensity belong to the composite pass
 *   for scenes without a grading pass of their own, this one included -
 *   applying them here as well would move each slider twice.
 * - A synthetic idle drive when nothing is playing, so a silent app is not a
 *   black screen. Here that is a slow tone sweep: the field walks up through
 *   its own modes exactly as a bench cymatics rig does.
 */
internal class CymaticsScene(
    private val context: Context,
) : Scene {
    override val id: String = SceneIds.CYMATICS

    private companion object {
        /** Excitation gain: analyzer bands sit well under 1 even when loud. */
        const val DRIVE_GAIN = 1.5f

        /** Level below which the field is considered undriven. */
        const val IDLE_RMS = 0.015f

        /** Seconds of silence before the idle sweep is at full strength. */
        const val IDLE_FADE_SECONDS = 1.2f

        /** Sweep rate of the idle tone, in full traversals per second. */
        const val IDLE_SWEEP_HZ = 0.035f

        /** Bands the idle sweep synthesizes when no real spectrum has arrived. */
        const val DEFAULT_BAND_COUNT = 64

        /** Colour normalization floor: keeps a quiet field from reading flat. */
        const val MIN_COLOR_AMPLITUDE = 0.12f

        /**
         * Highlight roll-off. The shader sums three additive layers (filled
         * surface, halo, filigree) and is HDR by construction; clipping that
         * would flatten every bright ridge into the same white blob, so it is
         * tone-mapped with `1 - exp(-c * EXPOSURE)` instead. Not a user
         * control: Brightness and Intensity are the composite pass' job.
         */
        const val EXPOSURE = 1.6f
    }

    private val plate = CymaticsPlate()

    /** (n, m, amplitude, phase) per mode - the shader's `uModes[]` layout. */
    private val modes = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
    private var modeCount = 0

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

    /** Decaying beat envelope, so a hit flares the filigree instead of popping. */
    private var beatPulse = 0f

    /** How far the idle sweep has taken over, 0 (driven) .. 1 (silent). */
    private var idleBlend = 0f
    private var idlePhase = 0f

    /** Idle sweep spectrum and the driven/idle crossfade, sized to the
     *  analyzer's band count on first use so a frame allocates nothing. */
    private var idleBands = FloatArray(0)
    private var driveBands = FloatArray(0)

    /** Reused for the frames where the engine has no features to give. */
    private val silence = AudioFeatures.empty()

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        // Handles from a lost EGL context are dead names, never valid again.
        program = 0
        vao = 0
        uniforms.clear()
        programOk = false
        plate.reset()
        try {
            program = GlUtil.buildProgram(loadRaw(R.raw.quad_vert), loadRaw(R.raw.cymatics_field_frag))
            programOk = true
        } catch (e: GlUtil.ShaderCompileException) {
            // Silent black is the worst failure mode: say why instead.
            onShaderError("Cymatics unavailable on this GPU: ${e.message}")
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

        // Drive the field. "Audio drive" is applied HERE, once: the renderer's
        // band-gain stage only touches the bass/mid/treble scalars, and the
        // spectrum is what this style listens to.
        plate.excite(
            bands = driveSpectrum(f, dt),
            dt = dt,
            fundamentalHz = p.cymaticsFundamental,
            drive = DRIVE_GAIN * p.audioDrive.coerceIn(0f, 4f),
            ringSeconds = CymaticsMath.ringSeconds(p.cymaticsRing),
            focus = p.cymaticsFocus,
        )
        plate.advancePhases(dt, p.speed)
        modeCount = plate.snapshot(p.cymaticsModes, modes)
        // Graded, decaying: a hard hit flares the ridges, a soft one nudges.
        beatPulse = maxOf(f.motionImpulse * p.beatResponse.coerceIn(0f, 2f), beatPulse - dt * 3f).coerceIn(0f, 1.5f)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(loc("uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(loc("uTime"), time)
        GLES30.glUniform4fv(loc("uModes"), CymaticsMath.MAX_RENDERED_MODES, modes, 0)
        GLES30.glUniform1i(loc("uModeCount"), modeCount)
        GLES30.glUniform1f(loc("uGeometry"), if (p.cymaticsGeometry == 1) 1f else 0f)
        GLES30.glUniform1f(loc("uScale"), p.cymaticsScale.coerceIn(0.5f, 8f))
        GLES30.glUniform1f(loc("uHeightNorm"), colorNormalization())
        GLES30.glUniform1f(loc("uLine"), p.cymaticsLine.coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uGlow"), p.cymaticsGlow.coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uFill"), p.cymaticsFill.coerceIn(0f, 1f))
        GLES30.glUniform1f(loc("uIridescence"), p.cymaticsIridescence.coerceIn(0f, 1f))
        GLES30.glUniform1f(loc("uCaustic"), p.cymaticsCaustic.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uSwirl"), p.cymaticsSwirl.coerceIn(-1f, 1f) * p.speed.coerceIn(0.05f, 4f))
        GLES30.glUniform1f(loc("uTravel"), p.cymaticsFlow.coerceIn(0f, 1f) * p.speed.coerceIn(0.05f, 4f))
        GLES30.glUniform1f(loc("uBaseHue"), FluidHue.base(p.paletteBase))
        GLES30.glUniform1f(loc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange))
        GLES30.glUniform1f(loc("uEnergy"), f.rms.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uTreble"), f.treble.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uBeat"), beatPulse)
        GLES30.glUniform1f(loc("uExposure"), EXPOSURE)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    /**
     * The spectrum the field is driven by: what is playing, or - after
     * [IDLE_FADE_SECONDS] of silence - a slow synthetic tone sweep, so an idle
     * app shows the field walking up through its own figures instead of
     * nothing at all. The crossfade means a quiet passage does not hand the
     * field over to the sweep mid-track.
     */
    private fun driveSpectrum(
        f: AudioFeatures,
        dt: Float,
    ): FloatArray {
        val silent = f.rms < IDLE_RMS
        val step = if (IDLE_FADE_SECONDS > 0f) dt / IDLE_FADE_SECONDS else 1f
        // Fades in over IDLE_FADE_SECONDS but out three times as fast: the
        // moment real audio arrives the field is its again.
        idleBlend = (idleBlend + if (silent) step else -step * 3f).coerceIn(0f, 1f)
        if (idleBlend <= 0f) return f.bands
        val count = if (f.bands.isNotEmpty()) f.bands.size else DEFAULT_BAND_COUNT
        if (idleBands.size != count) {
            idleBands = FloatArray(count)
            driveBands = FloatArray(count)
        }
        idlePhase += dt * IDLE_SWEEP_HZ
        // A single travelling peak in log-frequency, i.e. one tone sweeping.
        val center = (0.5f - 0.42f * cos(idlePhase * 2f * PI.toFloat())) * count
        for (i in idleBands.indices) {
            val d = (i - center) / 2.6f
            idleBands[i] = 0.62f * exp(-d * d)
        }
        if (idleBlend >= 1f || f.bands.isEmpty()) return idleBands
        for (i in driveBands.indices) {
            driveBands[i] = f.bands[i] * (1f - idleBlend) + idleBands[i] * idleBlend
        }
        return driveBands
    }

    /**
     * `1 / peak displacement`, so the shader can work in normalized height -
     * every threshold in it (line width, cell brightness, hue banding) then
     * means the same thing at any loudness. Taken from the amplitudes actually
     * being rendered rather than from the worst case, which would leave every
     * quiet passage sitting in one flat colour.
     */
    private fun colorNormalization(): Float {
        var total = 0f
        for (i in 0 until modeCount) total += modes[i * 4 + 2]
        return 1f / max(total, MIN_COLOR_AMPLITUDE)
    }

    private fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    override fun release() {
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
