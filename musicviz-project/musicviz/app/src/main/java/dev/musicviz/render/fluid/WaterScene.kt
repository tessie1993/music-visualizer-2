package dev.musicviz.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import kotlin.math.abs

/**
 * The WATER style: a pool whose surface is the [RippleSim] heightfield.
 * Musical events land as drops - the shared [FluidChoreography] spawn/catch
 * progression places WHERE they fall (the same journey params as
 * FLUID/CURLFLOW), and [FluidEmitters]' splat schedule decides WHEN: beat
 * splats become expanding interfering rings, stirrer splats become trails
 * of small drops (wakes flowing across the screen), suction/sparkle/pump
 * splats keep their triggers as smaller ripples, and catch-point suction
 * splats become drains: wells that dip the surface down over "Catch radius"
 * sim units ([WaterMath]). The display pass refracts a palette-tinted
 * depth-graded pool through the surface with Blinn specular, fresnel rim
 * and treble glints (water_display_frag), tinted by [FluidHue] (palette base
 * and palette span - the identity this pass owns). Hue shift, the colour
 * cycle, Brightness and Intensity are NOT applied here: the composite pass
 * grades the whole fluid family, and applying them in both places moved the
 * hue twice per slider unit.
 *
 * FluidScene's defensive conventions apply: GlUtil.resetFrameState() at
 * draw entry, framebuffer/viewport/blend snapshot-restore around the sim
 * passes, a PerformanceMonitor downgrade latch, and idle synthetic rain
 * when no track is playing.
 */
internal class WaterScene(
    private val context: Context,
) : Scene {
    override val id: String = SceneIds.WATER

    private companion object {
        /** Fingertip footprint in sim units (domain height is 2). */
        const val TOUCH_RADIUS = 0.11f

        /** Queued drag frames kept while the GL thread catches up. */
        const val MAX_TOUCH_BACKLOG = 24

        /** Splat colour -> film stain gain; the film is HDR, so this is < 1. */
        const val INK_GAIN = 0.8f
    }

    private val sim = RippleSim(context).also { it.inkEnabled = true }
    private val choreography = FluidChoreography()
    private val emitters = FluidEmitters().also { it.choreography = choreography }
    private val monitor = PerformanceMonitor()

    /** "Audio drive": one master reactivity gain for everything below. */
    private val audioDrive = FluidAudioDrive()

    private var params = SceneParams()
    private var time = 0f
    private var lastDt = 1f / 60f
    private var pendingFeatures: AudioFeatures? = null

    /** Last real features, kept warm so draw() > update() rates don't flicker. */
    private var lastFeatures: AudioFeatures? = null
    private var featuresAgeSec = 0f
    private var width = 1
    private var height = 1

    private var displayProgram = 0
    private val displayUniforms = HashMap<String, Int>()
    private var displayOk = false

    /** Latched automatic downgrade steps; never upgrades during a session. */
    private var autoDowngrade = 0
    private var lastUserQuality = -1
    private var appliedTier = -1

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)
    private val prevBlendFunc = IntArray(4)

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        // Handles from a lost EGL context are dead names; forget them so the
        // lazy fullscreen VAO is recreated in the new context.
        quadVao = 0
        quadVbo = 0
        sim.onShaderError = { onShaderError(it) }
        sim.inkEnabled = true
        sim.create()
        choreography.reset()
        appliedTier = -1
        lastUserQuality = -1
        autoDowngrade = 0
        displayOk = false
        if (!sim.available) {
            // Silent-black is the worst failure mode: tell the user why.
            onShaderError("Water style unavailable: this GPU can't render half-float buffers")
            return
        }
        try {
            displayProgram = GlUtil.buildProgram(loadRaw(R.raw.fluid_base_vert), loadRaw(R.raw.water_display_frag))
            displayUniforms.clear()
            displayOk = true
        } catch (e: GlUtil.ShaderCompileException) {
            android.util.Log.w("RippleSim", "water display shader rejected by driver: ${e.message}")
            onShaderError("Water display unavailable on this GPU: ${e.message}")
        }
        applyQualityTier()
    }

    override fun setParams(params: SceneParams) {
        this.params = params
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        sim.resize(width, height)
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        time += dt
        lastDt = dt
        pendingFeatures = features
        lastFeatures = features
        featuresAgeSec = 0f
    }

    /** Ripple grid short side (~384 nominal) per fluid quality tier. */
    private fun gridResFor(tierIndex: Int): Int =
        when (tierIndex) {
            0 -> 512
            1 -> 448
            2 -> 384
            3 -> 288
            else -> 192
        }

    /** Applies the effective quality tier; reallocates only on change. */
    private fun applyQualityTier() {
        if (!sim.available) return
        val userChanged = params.fluidQuality != lastUserQuality
        if (userChanged) {
            lastUserQuality = params.fluidQuality
            autoDowngrade = 0
            monitor.reset()
        }
        val idx = FluidQuality.effectiveIndex(params.fluidQuality, if (params.fluidAutoQuality) autoDowngrade else 0)
        if (idx == appliedTier) return
        appliedTier = idx
        sim.applyResolution(gridResFor(idx))
    }

    private var idlePhase = 0f
    private var rainAccum = 0f

    // Cached idle buffers: idling must not allocate two arrays per frame.
    private val idleBands = FloatArray(16)
    private val idleWaveform = FloatArray(64)

    /** Gentle synthetic features so the pool breathes with no track playing. */
    private fun idleFeatures(dt: Float): AudioFeatures {
        idlePhase += dt
        val t = idlePhase
        val bass = 0.16f + 0.10f * kotlin.math.sin(t * 0.6f)
        val mid = 0.13f + 0.09f * kotlin.math.sin(t * 1.0f + 1.7f)
        val treble = 0.05f + 0.04f * kotlin.math.sin(t * 1.8f + 3.1f)
        for (i in idleBands.indices) idleBands[i] = 0.1f + 0.07f * kotlin.math.sin(t * (0.5f + i * 0.13f))
        return AudioFeatures(
            bands = idleBands,
            waveform = idleWaveform,
            rms = 0.18f,
            bass = bass.coerceAtLeast(0f),
            mid = mid.coerceAtLeast(0f),
            treble = treble.coerceAtLeast(0f),
            beat = false,
        )
    }

    /** Idle rain: sparse random drops so a silent pool still ripples. */
    private fun queueIdleRain(dt: Float) {
        rainAccum += dt
        if (rainAccum < 0.45f) return
        rainAccum = 0f
        val x = (kotlin.random.Random.nextFloat() * 2f - 1f) * sim.aspect * 0.85f
        val y = kotlin.random.Random.nextFloat() * 2f - 1f
        val (tr, tg, tb) = FluidHue.rgb(FluidHue.base(params.paletteBase) + 0.12f * kotlin.random.Random.nextFloat(), 0.5f)
        sim.queueDrop(x, y * 0.85f, 0.05f, 0.28f * params.waterRippleStrength.coerceIn(0f, 2f), tr, tg, tb)
    }

    /**
     * Finger strokes waiting for the GL thread, queued by the renderer from
     * the UI thread. Bounded: a fast drag on a slow frame must not build an
     * unbounded backlog, and a stroke that is a frame late is worthless.
     */
    private val touchStrokes = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()

    /**
     * Queues one frame of a finger drag, in NORMALIZED surface coordinates:
     * both axes in [-1, 1] with y UP. The caller does not know this sim's
     * aspect, so the x scaling into sim space (x in [-aspect, aspect]) happens
     * here - on a portrait phone the domain is barely half a unit wide, and
     * feeding it screen-normalized x would drop every touch outside the pool.
     *
     * Called off the GL thread; drained in [draw].
     */
    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) {
        if (touchStrokes.size >= MAX_TOUCH_BACKLOG) return
        touchStrokes.add(floatArrayOf(nx, ny, ndx, ndy, dt, strength))
    }

    /**
     * Turns queued drags into crest/trough drop pairs. The stroke carries a
     * palette colour like an emitter splat does, so dragging a finger paints
     * into the liquid film as well as pushing the surface around.
     */
    private fun drainTouchStrokes(
        rippleStrength: Float,
        baseHue: Float,
        p: SceneParams,
    ) {
        while (true) {
            val st = touchStrokes.poll() ?: return
            val (tr, tg, tb) = FluidHue.rgb(baseHue + 0.5f * FluidHue.range(p.hueRange), 1f)
            sim.queueStroke(
                st[0] * sim.aspect,
                st[1],
                st[2] * sim.aspect,
                st[3],
                st[4],
                TOUCH_RADIUS,
                st[5] * rippleStrength.coerceAtLeast(0.2f),
                tr,
                tg,
                tb,
            )
        }
    }

    override fun draw(timeSeconds: Float) {
        if (!sim.available || !displayOk) return
        // The sim's FBO passes assume clean scissor/mask/blend-equation state;
        // enforce the contract in case a prior scene (native projectM
        // especially) left anything dirty this frame.
        GlUtil.resetFrameState()
        val p = params
        featuresAgeSec += lastDt
        val idle = pendingFeatures == null && featuresAgeSec >= 0.25f
        // "Audio drive" is applied HERE, once, to the snapshot the choreography,
        // the emitter schedule and the display pass' treble glints all read -
        // not in the renderer's band-gain stage, which shader and particle
        // scenes would then multiply by a second time. Identity at the default.
        val f =
            audioDrive.scaled(
                pendingFeatures
                    ?: lastFeatures.takeIf { featuresAgeSec < 0.25f }
                    ?: idleFeatures(lastDt),
                p.audioDrive,
            )

        // Snapshot the engine's target + blend state (FluidScene pattern).
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_RGB, prevBlendFunc, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_RGB, prevBlendFunc, 1)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, prevBlendFunc, 2)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, prevBlendFunc, 3)
        val blendWas = GLES30.glIsEnabled(GLES30.GL_BLEND)

        // Sustained frame deficit lowers the latched tier (FluidScene F6).
        if (p.fluidAutoQuality) {
            val severity = monitor.onFrame(lastDt)
            if (severity > 0) {
                autoDowngrade += severity
                monitor.reset()
            }
        }
        applyQualityTier()

        // Wave character. Damping now drains the HEIGHT as well as the
        // velocity (RippleMath.HEIGHT_DECAY_RATIO) - without that the pool only
        // ever gained volume and eventually pinned against the height rail.
        sim.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
        sim.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
        // Liquid film: how hard the surface drags the colour, and how fast the
        // pool clears back to open water.
        sim.inkFlow = p.waterLiquidFlow.coerceIn(0f, 4f)
        sim.inkDissipation = p.waterLiquidFade.coerceIn(0f, 2f)

        // Journey: the same spawn/catch progression as FLUID/CURLFLOW.
        choreography.path = p.fluidSpawnPath.coerceIn(0, FluidChoreography.PATH_LABELS.size - 1)
        choreography.spawnCount = p.fluidSpawnPoints.coerceIn(1, FluidChoreography.MAX_SPAWN)
        choreography.catchCount = p.fluidCatchPoints.coerceIn(0, FluidChoreography.MAX_CATCH)
        choreography.progressionAmount = p.fluidSpawnProgress.coerceIn(0f, 1f)
        choreography.speed = p.speed.coerceIn(0.1f, 2f)

        // Emitter schedule reused verbatim; splats are converted to drops.
        emitters.beatPattern = p.fluidBeatPattern.coerceIn(0, 3)
        emitters.beatSplats = p.fluidBeatSplats.coerceIn(0, 8)
        emitters.stirrers = p.fluidStirrers.coerceIn(0, 4)
        emitters.stirrerSpeed = p.fluidStirrerSpeed.coerceIn(0f, 2f) * p.speed.coerceIn(0.1f, 2f)
        emitters.bassPump = p.fluidBassPump
        emitters.sparkle = p.fluidSparkle
        emitters.splatRadius = p.fluidSplatRadius.coerceIn(0.02f, 0.4f)
        emitters.radiusPulse = p.fluidRadiusPulse.coerceIn(0f, 1f)
        emitters.catchSuction = p.fluidCatchPull.coerceIn(0f, 3f)
        emitters.forceScale = p.fluidSplatForce.coerceIn(0f, 3f)
        // "Beat response": depth of the beat envelope, which here decides how
        // much harder a beat drop lands than a stirrer wake (neutral at 1).
        emitters.beatResponse = p.beatResponse

        val simDt = lastDt.coerceIn(0f, 1f / 30f)
        choreography.tick(f, simDt, sim.aspect)
        val rippleStrength = p.waterRippleStrength.coerceIn(0f, 2f)
        val catchRadius = WaterMath.catchWellRadius(p.fluidCatchRadius)
        // Palette identity only. Hue shift rides the composite pass' uPostHue
        // for this whole family, so folding it in here as well rotated the
        // pool twice per slider unit.
        val baseHue = FluidHue.base(p.paletteBase)
        // Same clamp as the display pass' uHueSpan, via the shared helper -
        // an inline `coerceIn(MIN, 1f)` here left the top third of the Hue
        // range slider dead on the splashes while the pool colours moved.
        for (s in emitters.tick(f, simDt, sim.aspect, baseHue, FluidHue.range(p.hueRange))) {
            val speed = kotlin.math.sqrt(s.velX * s.velX + s.velY * s.velY) / FluidEmitters.BASE_SPEED
            if (WaterMath.isCatchWell(s.r, s.g, s.b)) {
                // Catch points are drains, not splashes: they dimple the pool
                // DOWN across "Catch radius" sim units, which is what makes
                // that slider mean the same thing here as the particle
                // capture radius does on FLUID/CURLFLOW.
                val well = WaterMath.catchWellAmplitude(speed, catchRadius, rippleStrength)
                // A drain carries no dye by definition (WaterMath.isCatchWell),
                // so it dips the surface without staining the film - which is
                // what makes a drain look like water leaving rather than a
                // differently coloured splash.
                if (abs(well) > 1e-4f) sim.queueDrop(s.curX, s.curY, catchRadius, well)
                continue
            }
            // Splat -> drop: position lands at the capsule head, velocity
            // magnitude scales amplitude (x waterRippleStrength), radius
            // carries over tightened. Stirrer splats arrive every frame from
            // moving anchors, so their small drops naturally trail into
            // wakes that flow across the pool.
            val amp = (0.06f + 0.5f * speed.coerceAtMost(2f)) * rippleStrength
            // The splat's own palette colour goes in with the ring: the film
            // is coloured by the same emitter schedule that shapes the waves,
            // which is what makes the pool read as the visual gone liquid
            // rather than as a blue background with drops on it.
            if (amp > 1e-4f) {
                sim.queueDrop(s.curX, s.curY, s.radius * 0.6f, amp, s.r * INK_GAIN, s.g * INK_GAIN, s.b * INK_GAIN)
            }
        }
        if (idle) queueIdleRain(lastDt)
        drainTouchStrokes(rippleStrength, baseHue, p)
        sim.step(simDt)
        pendingFeatures = null

        // Restore the engine's target and draw the display pass (opaque).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(displayProgram)
        GLES30.glUniform2f(dLoc("uInvRes"), sim.texelW, sim.texelH)
        GLES30.glUniform1f(dLoc("uAspect"), sim.aspect)
        GLES30.glUniform1f(dLoc("uTime"), time)
        GLES30.glUniform1f(dLoc("uBaseHue"), baseHue)
        GLES30.glUniform1f(dLoc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange))
        GLES30.glUniform1f(dLoc("uDepth"), p.waterDepth.coerceIn(0f, 1f))
        GLES30.glUniform1f(dLoc("uSpecular"), p.waterSpecular.coerceIn(0f, 1f))
        GLES30.glUniform1f(dLoc("uFlowDrift"), p.waterFlow.coerceIn(0f, 1f))
        GLES30.glUniform1f(dLoc("uRefract"), 0.9f)
        GLES30.glUniform1f(dLoc("uTreble"), f.treble.coerceIn(0f, 2f))
        // Neutral on purpose - see WaterMath.DISPLAY_BRIGHTNESS. Brightness
        // and Intensity are Color-tab grading params and the composite pass
        // owns them for every scene that doesn't grade itself, WATER included.
        GLES30.glUniform1f(dLoc("uBrightness"), WaterMath.DISPLAY_BRIGHTNESS)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sim.heightTex)
        GLES30.glUniform1i(dLoc("uHeight"), 0)
        // Liquid film. Bound even when the driver refused the extra RGBA16F
        // pair - the sampler must stay valid - with the amount forced to 0 so
        // the pass is an exact no-op and the plain pool renders as before.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (sim.inkAvailable) sim.inkTex else sim.heightTex)
        GLES30.glUniform1i(dLoc("uInk"), 1)
        GLES30.glUniform1f(dLoc("uInkAmount"), if (sim.inkAvailable) p.waterLiquid.coerceIn(0f, 1f) else 0f)
        drawFullscreen()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        if (blendWas) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3])
    }

    // Fullscreen triangle VAO owned by the scene (the sim's VAO is private).
    private var quadVao = 0
    private var quadVbo = 0

    private fun drawFullscreen() {
        if (quadVao == 0) {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            quadVao = ids[0]
            GLES30.glGenBuffers(1, ids, 0)
            quadVbo = ids[0]
            val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
            val buf =
                java.nio.ByteBuffer
                    .allocateDirect(quad.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(quad)
                    .apply { position(0) }
            GLES30.glBindVertexArray(quadVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        } else {
            GLES30.glBindVertexArray(quadVao)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun dLoc(name: String): Int = displayUniforms.getOrPut(name) { GLES30.glGetUniformLocation(displayProgram, name) }

    override fun release() {
        sim.release()
        if (displayProgram != 0) GLES30.glDeleteProgram(displayProgram)
        displayProgram = 0
        displayUniforms.clear()
        displayOk = false
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        quadVbo = 0
        quadVao = 0
        appliedTier = -1
    }

    private fun loadRaw(resId: Int): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
}

/**
 * Pure-Kotlin mirror of the WATER style's Customize -> sim parameter mapping
 * (RippleMath.kt convention: the maths lives in a headless-testable object
 * and the GL code only wires it up). Covers the "Catch radius" slider this
 * scene used to silently ignore, and the display pass' brightness factor,
 * which must stay neutral now that the composite pass grades the fluid
 * family. Hue arithmetic is shared with the other fluid scenes in
 * [FluidHue].
 */
internal object WaterMath {
    /** "Catch radius" slider domain (CustomizeTabs, shared with FLUID). */
    const val MIN_CATCH_RADIUS = 0.03f
    const val MAX_CATCH_RADIUS = 0.3f

    /** SceneParams.fluidCatchRadius default: the well-depth reference. */
    const val REF_CATCH_RADIUS = 0.12f

    /** Spread compensation bounds, so extreme radii stay in a usable range. */
    private const val MIN_SPREAD = 0.4f
    private const val MAX_SPREAD = 2.5f

    /**
     * True when a splat carries no dye at all. [FluidEmitters] uses that
     * exclusively for catch-point suction splats: every other emitter
     * multiplies a full-value HSV colour (max channel 1) by a strictly
     * positive gain, so a black splat is unambiguously a drain.
     */
    fun isCatchWell(
        r: Float,
        g: Float,
        b: Float,
    ): Boolean = maxOf(r, g, b) <= 0f

    /** Gaussian radius of a catch well in sim units, clamped to the slider. */
    fun catchWellRadius(catchRadius: Float): Float = catchRadius.coerceIn(MIN_CATCH_RADIUS, MAX_CATCH_RADIUS)

    /**
     * Height amplitude of a catch well: NEGATIVE, so the drain visibly sucks
     * the surface down instead of splashing like an ordinary drop (the ring
     * it radiates is the inverted-phase twin of a drop's). [speed] is the
     * suction capsule's normalized velocity, which already carries the
     * "Catch pull" slider and the bass envelope. The magnitude tapers as the
     * well widens (referenced to [REF_CATCH_RADIUS]) so a wide drain reads
     * as a broad shallow dip rather than a deep crater.
     */
    fun catchWellAmplitude(
        speed: Float,
        catchRadius: Float,
        rippleStrength: Float,
    ): Float {
        val r = catchWellRadius(catchRadius)
        val spread = (REF_CATCH_RADIUS / r).coerceIn(MIN_SPREAD, MAX_SPREAD)
        return -(0.06f + 0.5f * speed.coerceIn(0f, 2f)) * spread * rippleStrength.coerceIn(0f, 2f)
    }

    /**
     * The water display pass' own brightness factor: NEUTRAL, deliberately.
     *
     * Brightness and Intensity are Color-tab grading params, and the
     * composite pass grades every scene that does not grade itself - the
     * fluid family, WATER included - by `brightness * intensity`. This pass
     * used to fold the same product into its own uBrightness, so once the
     * composite grade landed both passes applied it and the response went
     * quadratic (blown out at the top of either slider). One pass owns it
     * now, and it is not this one.
     */
    const val DISPLAY_BRIGHTNESS = 1f

    /**
     * End-to-end brightness the pool receives: this pass' factor times the
     * composite grade's `brightness * intensity`. Exists so the gate can
     * prove the product is applied exactly ONCE - doubling either slider
     * must double the output, not quadruple it.
     */
    fun effectiveBrightness(
        brightness: Float,
        intensity: Float,
    ): Float = DISPLAY_BRIGHTNESS * brightness * intensity
}
