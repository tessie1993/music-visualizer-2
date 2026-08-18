package dev.geode.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.geode.R
import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FluidHue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

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
 * ### Clocks and phases
 *
 * The repo convention (see `VisualizerRenderer.postRotationAngle`): rotation
 * is a SPEED, integrated here, never a `rate * uptime` product evaluated in
 * the shader - that product teleports the whole field whenever the rate
 * moves (a preset fade lerping Swirl, an LFO on Speed), and drifts into
 * float mush as uptime grows. So swirl, travel and the plate scroll are
 * accumulated per frame and uploaded as wrapped phases, and the scene clock
 * itself wraps at [TIME_WRAP_SECONDS], a whole number of turns for every
 * `sin(uTime * k)` the shader contains (k is always a two-decimal constant;
 * `CymaticsClockSafetyTest` holds both sides to that contract).
 *
 * ### Conventions
 *
 * - `GlUtil.resetFrameState()` at draw entry (the fluid family's rule).
 * - Palette IDENTITY only ([FluidHue] base + span, plus this substyle's own
 *   hue offset and a chroma nudge). Hue shift, the colour cycle, Brightness,
 *   Contrast and Intensity belong to the composite pass for scenes without a
 *   grading pass of their own, this one included - applying them here as
 *   well would move each slider twice.
 * - A synthetic idle drive when nothing is playing, so a silent app is not a
 *   black screen. Here that is a slow tone sweep: the field walks up through
 *   its own modes exactly as a bench cymatics rig does.
 */
internal class CymaticsScene(
    private val context: Context,
    private val style: VisualStyleCatalog.CymaticsStyle =
        requireNotNull(VisualStyleCatalog.cymatics(SceneIds.CYMATICS)),
) : Scene,
    PcmSink {
    override val id: String = style.id

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

        /**
         * Scene clock wrap: 200 * pi seconds (~10.5 min). The live wallpaper
         * renders for days without a context loss, so an unwrapped `+= dt`
         * clock decays into float32 mush (the renderer wraps its own clock at
         * `TIME_WRAP_SEC` for the same reason). 200 * pi specifically: the
         * shader only reads uTime as `sin/cos(uTime * k)` with k a TWO-DECIMAL
         * constant, and k * 200pi is k * 100 whole turns, so every such term
         * lands back on its own phase at the wrap (within ~1e-4 rad of float
         * rounding - invisible).
         */
        const val TIME_WRAP_SECONDS = 628.31853f

        const val TWO_PI = (2.0 * PI).toFloat()

        /** Plate-scroll wrap: the Chladni formula is 2-periodic, exactly. */
        const val DRIFT_WRAP = 2f

        /** Dish travelling-phase rate at Flow = 1, rad/s (base harmonic). */
        const val TRAVEL_OMEGA = 1.1f

        /** Plate scroll rate at Flow = 1, plate units per second. */
        const val DRIFT_RATE = 0.05f

        /** The shader branch that consumes the droplet bank. */
        const val STYLE_FARADAY = 4

        /** Chroma confidence below which the last harmony is held. */
        const val CHROMA_CONFIDENCE = 0.35f

        /** Time constant of the pitch-class -> hue drift, seconds. */
        const val CHROMA_TAU_SECONDS = 2.5f

        /**
         * Peak hue nudge, in turns, that the dominant pitch class applies on
         * top of the substyle's own offset. Sinusoidal in the pitch class so
         * neighbouring classes across the B/C wrap stay neighbours on the
         * wheel - subtle enough that uBaseHue still clearly belongs to the
         * user's palette choice.
         */
        const val CHROMA_HUE_SPAN = 0.05f

        const val PCM_STRIKE_GAIN = 0.6f
    }

    private val plate = CymaticsPlate()

    /** Faraday's beat-spawned droplet rings; zeros for every other substyle. */
    private val drops = CymaticsDrops()

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
    private var uniforms = GlUtil.UniformCache(0)
    private var programOk = false
    private var vao = 0

    /** Decaying beat envelope, so a hit flares the filigree instead of popping. */
    private var beatPulse = 0f

    private val pcmPulse = PcmPulse()
    private var pcmStrike = 0f

    /** Integrated field rotation, radians, wrapped to one turn. */
    private var swirlPhase = 0f

    /** Integrated dish travelling-wave phase, radians, wrapped to one turn. */
    private var travelPhase = 0f

    /** Integrated plate scroll, plate units, wrapped at [DRIFT_WRAP]. */
    private var driftShift = 0f

    /** Smoothed dominant pitch class, 0..1 around the circle of semitones. */
    private var chromaHue = 0f

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
        uniforms = GlUtil.UniformCache(0)
        programOk = false
        plate.reset()
        drops.reset()
        program =
            GlUtil.buildProgramReporting(
                GlUtil.loadShader(context, R.raw.quad_vert),
                GlUtil.loadShader(context, R.raw.cymatics_field_frag),
            ) {
                // Silent black is the worst failure mode: say why instead.
                onShaderError("Cymatics unavailable on this GPU: $it")
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
    }

    override fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) = pcmPulse.accept(samples, count)

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        // Wrapped, not merely accumulated: the wallpaper renders for days,
        // and every shader consumer of uTime is periodic in TIME_WRAP_SECONDS.
        time = (time + dt) % TIME_WRAP_SECONDS
        lastDt = dt
        pcmStrike = pcmPulse.tick(dt)
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
        // spectrum is what this style listens to. safeDrive is the read-in
        // clamp: presets and preset links carry raw doubles, and a negative
        // (or NaN) drive must mean "silent", never a poisoned resonator bank.
        plate.excite(
            bands = driveSpectrum(f, dt),
            dt = dt,
            fundamentalHz = p.cymaticsFundamental,
            drive = DRIVE_GAIN * CymaticsMath.safeDrive(p.audioDrive) * (1f + PCM_STRIKE_GAIN * pcmStrike),
            ringSeconds = CymaticsMath.ringSeconds(p.cymaticsRing),
            focus = p.cymaticsFocus,
        )
        plate.advancePhases(dt, p.speed)
        modeCount = plate.snapshot(minOf(p.cymaticsModes, style.modeCap), modes)

        // `1 / peak displacement`, so the shader works in normalized height -
        // every threshold in it (line width, cell brightness, hue banding)
        // then means the same thing at any loudness. The same sum feeds
        // uFieldLive, the flat-field safety gate: when nothing rings, the
        // shader's additive layers all fade to black with it.
        var totalAmplitude = 0f
        for (i in 0 until modeCount) totalAmplitude += modes[i * 4 + 2]

        // Graded, decaying: a hard hit flares the ridges, a soft one nudges.
        beatPulse = maxOf(f.motionImpulse * p.beatResponse.coerceIn(0f, 2f), beatPulse - dt * 3f).coerceIn(0f, 1.5f)

        // Swirl and travel are SPEEDS, integrated here into wrapped phases
        // (the repo's rotation convention). Uploading rate * uptime instead
        // made every Swirl/Speed change - a preset fade, an LFO - teleport
        // the field by (new - old) * uptime radians, worse the longer the
        // wallpaper had been up.
        val speed = p.speed.coerceIn(0.05f, 4f)
        val swirlRate = (p.cymaticsSwirl * style.swirl).coerceIn(-1f, 1f) * speed
        swirlPhase = CymaticsMath.wrapPhase(swirlPhase + swirlRate * dt, TWO_PI)
        val flowRate = (p.cymaticsFlow * style.flow).coerceIn(0f, 1f) * speed
        travelPhase = CymaticsMath.wrapPhase(travelPhase + flowRate * TRAVEL_OMEGA * dt, TWO_PI)
        driftShift = CymaticsMath.wrapPhase(driftShift + flowRate * DRIFT_RATE * dt, DRIFT_WRAP)

        // Faraday's droplet rings ride discrete beats (beatImpulse, not the
        // motion envelope - a ripple ring is an event, not a texture).
        if (style.shaderStyle == STYLE_FARADAY) drops.update(dt, f.beatImpulse)

        // Pitch class -> hue: the dominant chroma bin nudges the palette a
        // few degrees, smoothed and held through unpitched passages.
        if (f.hasChroma && f.chromaConfidence >= CHROMA_CONFIDENCE) {
            var best = 0
            for (i in 1 until 12) if (f.chroma[i] > f.chroma[best]) best = i
            chromaHue = CymaticsMath.approachHue(chromaHue, best / 12f, CymaticsMath.smoothing(dt, CHROMA_TAU_SECONDS))
        }
        val chromaNudge = sin(chromaHue * TWO_PI) * CHROMA_HUE_SPAN

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(loc("uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(loc("uTime"), time)
        GLES30.glUniform1i(loc("uStyle"), style.shaderStyle)
        GLES30.glUniform4fv(loc("uModes"), CymaticsMath.MAX_RENDERED_MODES, modes, 0)
        GLES30.glUniform1i(loc("uModeCount"), modeCount)
        val geometry = style.geometryOverride ?: p.cymaticsGeometry
        GLES30.glUniform1f(loc("uGeometry"), if (geometry == 1) 1f else 0f)
        GLES30.glUniform1f(loc("uScale"), (p.cymaticsScale * style.scale).coerceIn(0.5f, 8f))
        GLES30.glUniform1f(loc("uHeightNorm"), 1f / max(totalAmplitude, MIN_COLOR_AMPLITUDE))
        GLES30.glUniform1f(loc("uFieldLive"), CymaticsMath.fieldLiveness(totalAmplitude))
        GLES30.glUniform1f(loc("uLine"), (p.cymaticsLine * style.line).coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uGlow"), (p.cymaticsGlow * style.glow).coerceIn(0f, 2f))
        GLES30.glUniform1f(loc("uFill"), (p.cymaticsFill * style.fill).coerceIn(0f, 1f))
        GLES30.glUniform1f(loc("uIridescence"), (p.cymaticsIridescence * style.iridescence).coerceIn(0f, 1f))
        GLES30.glUniform1f(loc("uCaustic"), (p.cymaticsCaustic * style.caustic).coerceIn(0f, 1.5f))
        GLES30.glUniform1f(loc("uSwirlPhase"), swirlPhase)
        GLES30.glUniform1f(loc("uTravelPhase"), travelPhase)
        GLES30.glUniform1f(loc("uDriftShift"), driftShift)
        GLES30.glUniform4fv(loc("uDrops"), CymaticsDrops.SLOTS, drops.packed, 0)
        GLES30.glUniform1f(loc("uBaseHue"), FluidHue.base(p.paletteBase) + style.hueOffset + chromaNudge)
        GLES30.glUniform1f(loc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan)
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
        idlePhase = (idlePhase + dt * IDLE_SWEEP_HZ) % 1f
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

    private fun loc(name: String): Int = uniforms.loc(name)

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        program = 0
        vao = 0
        programOk = false
        uniforms = GlUtil.UniformCache(0)
    }
}
