package dev.musicviz.render.scene

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidHue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * The CYMATICS style: the sound itself, as the shape it would make.
 *
 * A Chladni plate driven by whatever is playing - a track, or the microphone.
 * [CymaticsPlate] decides which of the plate's standing waves are ringing and
 * how hard ([CymaticsMath] for the pitch -> mode law); this class draws the
 * result, either as the lit 3D surface or as the flat sand figure seen from
 * above. Nothing here is decoration keyed to a beat: the figure on screen is
 * the one that frequency would actually put on a plate, which is why a pure
 * tone gives a clean symmetric pattern and a chord gives the superposition of
 * its notes' patterns.
 *
 * ### How it draws
 *
 * The plate is a `uGrid + 1` square vertex grid with NO vertex buffer: the
 * vertex shader derives its plate coordinates from `gl_VertexID`, which under
 * `glDrawElements` is the index value, so only a (rebuilt-on-resize) index
 * buffer exists and a resolution change costs nothing per frame. The surface
 * and its analytic normal are evaluated per vertex, which keeps the fragment
 * pass cheap enough for the nodal-line antialiasing to run at full
 * resolution.
 *
 * ### Conventions borrowed from the fluid family
 *
 * - `GlUtil.resetFrameState()` at draw entry, and depth/blend state restored
 *   before returning: a scene that leaves depth testing on breaks every pass
 *   that runs after it.
 * - Palette IDENTITY only ([FluidHue] base + span). Hue shift, the colour
 *   cycle, Brightness, Contrast and Intensity belong to the composite pass
 *   for scenes without a grading pass of their own, this one included -
 *   applying them here as well would move each slider twice.
 * - A synthetic idle drive when nothing is playing, so a silent app is not a
 *   black screen. Here that is a slow tone sweep: the plate walks up through
 *   its own modes exactly as a bench cymatics rig does.
 */
internal class CymaticsScene(
    private val context: Context,
) : Scene {
    override val id: String = SceneIds.CYMATICS

    /**
     * The one scene in the tree that draws real 3D geometry, so the render
     * target needs a depth attachment; without it the far side of the surface
     * paints over the near side wherever the draw order disagrees with depth.
     */
    override val needsDepth: Boolean = true

    private companion object {
        /** Vertex-grid cells per side, by the "Detail" tier. */
        val GRID_TIERS = intArrayOf(96, 160, 224)

        /** Surface height at "Relief" = 1, in plate units (the plate is 2 wide). */
        const val BASE_RELIEF = 0.3f

        /** Excitation gain: analyzer bands sit well under 1 even when loud. */
        const val DRIVE_GAIN = 1.5f

        /** Vertical field of view of the 3D camera, in degrees. */
        const val FOV_DEGREES = 42f

        /**
         * Half-diagonal of the plate plus margin: what the 3D camera has to
         * frame, since an orbiting square is up to sqrt(2) wide on screen.
         */
        const val FRAME_RADIUS = 1.5f

        /**
         * What the flat view has to frame: the plate's half-WIDTH, not its
         * half-diagonal - seen from straight above it is axis-aligned and
         * never turns, so framing it for a rotation that cannot happen just
         * shrinks the figure to two thirds of the screen for nothing.
         */
        const val FLAT_FRAME_RADIUS = 1.06f

        /** Level below which the plate is considered undriven. */
        const val IDLE_RMS = 0.015f

        /** Seconds of silence before the idle sweep is at full strength. */
        const val IDLE_FADE_SECONDS = 1.2f

        /** Sweep rate of the idle tone, in full traversals per second. */
        const val IDLE_SWEEP_HZ = 0.035f

        /** Colour normalization floor: keeps a quiet plate from reading flat. */
        const val MIN_COLOR_AMPLITUDE = 0.12f

        /** Bands the idle sweep synthesizes when no real spectrum has arrived. */
        const val DEFAULT_BAND_COUNT = 64
    }

    private val plate = CymaticsPlate()

    /** (n, m, amplitude) triples, the shader's `uModes[]` layout. */
    private val modes = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
    private var modeCount = 0

    private var params = SceneParams.DEFAULT
    private var time = 0f
    private var lastDt = 1f / 60f
    private var pendingFeatures: AudioFeatures? = null
    private var width = 1
    private var height = 1

    private var plateProgram = 0
    private var tableProgram = 0
    private val plateUniforms = HashMap<String, Int>()
    private val tableUniforms = HashMap<String, Int>()
    private var programsOk = false

    private var vao = 0
    private var ibo = 0
    private var gridCells = 0
    private var indexCount = 0

    /** Orbit angle, integrated so a "Spin" change never teleports the camera. */
    private var spin = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)

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
        vao = 0
        ibo = 0
        gridCells = 0
        indexCount = 0
        plateProgram = 0
        tableProgram = 0
        plateUniforms.clear()
        tableUniforms.clear()
        programsOk = false
        plate.reset()
        try {
            plateProgram = GlUtil.buildProgram(loadRaw(R.raw.cymatics_plate_vert), loadRaw(R.raw.cymatics_plate_frag))
            tableProgram = GlUtil.buildProgram(loadRaw(R.raw.quad_vert), loadRaw(R.raw.cymatics_table_frag))
            programsOk = true
        } catch (e: GlUtil.ShaderCompileException) {
            // Silent black is the worst failure mode: say why instead.
            onShaderError("Cymatics unavailable on this GPU: ${e.message}")
            return
        }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        ensureGrid(GRID_TIERS[params.cymaticsGrid.coerceIn(0, GRID_TIERS.size - 1)])
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
        if (!programsOk) return
        GlUtil.resetFrameState()
        val p = params
        val dt = lastDt.coerceIn(0f, 1f / 15f)
        val f = pendingFeatures ?: silence
        pendingFeatures = null
        ensureGrid(GRID_TIERS[p.cymaticsGrid.coerceIn(0, GRID_TIERS.size - 1)])

        // Drive the plate. "Audio drive" is applied HERE, once: the renderer's
        // band-gain stage only touches the bass/mid/treble scalars, and the
        // spectrum is what this style listens to.
        val bands = driveSpectrum(f, dt)
        plate.excite(
            bands = bands,
            dt = dt,
            fundamentalHz = p.cymaticsFundamental,
            drive = DRIVE_GAIN * p.audioDrive.coerceIn(0f, 4f),
            ringSeconds = CymaticsMath.ringSeconds(p.cymaticsRing),
            focus = p.cymaticsFocus,
        )
        val vibration = plate.advanceVibration(dt, p.cymaticsVibration, p.speed)
        modeCount = plate.snapshot(p.cymaticsModes, 1f, modes)

        val flat = !p.cymatics3d
        buildCamera(flat, p, dt)

        // The table first, with depth writes off: it is the backdrop, and it
        // must never occlude the plate that is drawn into it.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        drawTable(f, p)

        // The plate. Depth is cleared here rather than by the renderer: the
        // engine clears colour (or fades it, for trails) and knows nothing
        // about which scenes are three-dimensional.
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        // The surface is genuinely two-sided - the underside of a wave is as
        // visible as its crest at a low camera angle - so no culling, and the
        // fragment pass flips the normal on back faces.
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        drawPlate(f, p, vibration, flat)

        // Leave the state as the pipeline expects to find it. GlUtil's frame
        // reset covers the enable bits and the write mask, but not the depth
        // FUNCTION, so that one is put back by hand.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glBindVertexArray(0)
    }

    /**
     * The spectrum the plate is driven by: what is playing, or - after
     * [IDLE_FADE_SECONDS] of silence - a slow synthetic tone sweep, so an idle
     * app shows the plate walking up through its own figures instead of
     * nothing at all. The crossfade means a quiet passage does not hand the
     * plate over to the sweep mid-track.
     */
    private fun driveSpectrum(
        f: AudioFeatures,
        dt: Float,
    ): FloatArray {
        val silent = f.rms < IDLE_RMS
        val step = if (IDLE_FADE_SECONDS > 0f) dt / IDLE_FADE_SECONDS else 1f
        // Fades in over IDLE_FADE_SECONDS but out three times as fast: the
        // moment real audio arrives the plate is its again.
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

    /** Camera for this frame: perspective orbit, or the flat view from above. */
    private fun buildCamera(
        flat: Boolean,
        p: SceneParams,
        dt: Float,
    ) {
        val aspect = width.toFloat() / height.toFloat()
        if (flat) {
            // Fit the whole square plate inside the short side: a Chladni
            // figure is a symmetric object and cropping it costs the symmetry.
            val halfX = if (aspect >= 1f) FLAT_FRAME_RADIUS * aspect else FLAT_FRAME_RADIUS
            val halfY = if (aspect >= 1f) FLAT_FRAME_RADIUS else FLAT_FRAME_RADIUS / aspect
            Matrix.orthoM(projection, 0, -halfX, halfX, -halfY, halfY, 0.1f, 10f)
            eye[0] = 0f
            eye[1] = 4f
            eye[2] = 0f
            // Looking straight down, with plate +y toward the top of the screen.
            Matrix.setLookAtM(view, 0, 0f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, -1f)
        } else {
            spin += p.cymaticsSpin * p.speed.coerceIn(0.05f, 4f) * dt
            Matrix.perspectiveM(projection, 0, FOV_DEGREES, aspect, 0.1f, 40f)
            // Frame the plate on whichever axis is tighter: on a portrait
            // screen the horizontal field is the narrow one, and a distance
            // picked from the vertical field alone would run the plate off
            // the sides.
            val halfFov = FOV_DEGREES * 0.5f * PI.toFloat() / 180f
            val tanY = tan(halfFov)
            val tanX = tanY * aspect
            val distance = FRAME_RADIUS / minOf(tanY, tanX)
            // "Tilt": straight down at 0, almost edge-on at 1.
            val elevation = (85f - 72f * p.cymaticsTilt.coerceIn(0f, 1f)) * PI.toFloat() / 180f
            val ce = cos(elevation)
            eye[0] = distance * ce * sin(spin)
            eye[1] = distance * sin(elevation)
            eye[2] = distance * ce * cos(spin)
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0f, 0f, 0f, 1f, 0f)
        }
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
    }

    private fun drawTable(
        f: AudioFeatures,
        p: SceneParams,
    ) {
        GLES30.glUseProgram(tableProgram)
        GLES30.glUniform2f(tLoc("uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(tLoc("uBaseHue"), FluidHue.base(p.paletteBase))
        GLES30.glUniform1f(tLoc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange))
        GLES30.glUniform1f(tLoc("uEnergy"), f.rms.coerceIn(0f, 1.5f))
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun drawPlate(
        f: AudioFeatures,
        p: SceneParams,
        vibration: Float,
        flat: Boolean,
    ) {
        // No ringing mode means a flat plate, and a flat plate is one giant
        // nodal region: the sand pass would paint the whole screen. Silence
        // draws the bare table instead.
        if (indexCount == 0 || modeCount == 0) return
        GLES30.glUseProgram(plateProgram)
        GLES30.glUniformMatrix4fv(pLoc("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1i(pLoc("uGrid"), gridCells)
        GLES30.glUniform1i(pLoc("uModeCount"), modeCount)
        GLES30.glUniform3fv(pLoc("uModes"), CymaticsMath.MAX_RENDERED_MODES, modes, 0)
        GLES30.glUniform1f(pLoc("uRelief"), BASE_RELIEF * p.cymaticsRelief.coerceIn(0f, 2f))
        GLES30.glUniform1f(pLoc("uVibration"), vibration)
        GLES30.glUniform1f(pLoc("uFlat"), if (flat) 1f else 0f)
        GLES30.glUniform1f(pLoc("uSand"), p.cymaticsSand.coerceIn(0f, 2f))
        GLES30.glUniform1f(pLoc("uHeightNorm"), colorNormalization())
        GLES30.glUniform1f(pLoc("uBaseHue"), FluidHue.base(p.paletteBase))
        GLES30.glUniform1f(pLoc("uHueSpan"), FluidHue.span(p.hueRange, p.paletteRange))
        GLES30.glUniform1f(pLoc("uEnergy"), f.rms.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(pLoc("uTreble"), f.treble.coerceIn(0f, 1.5f))
        GLES30.glUniform1f(pLoc("uTime"), time)
        GLES30.glUniform3f(pLoc("uEye"), eye[0], eye[1], eye[2])
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    /**
     * `1 / peak displacement`, so the shader can map height onto the palette
     * span. Taken from the amplitudes actually being rendered rather than
     * from the fixed maximum: normalizing against the worst case would leave
     * every quiet passage sitting in one flat colour at the middle of the
     * palette.
     */
    private fun colorNormalization(): Float {
        var total = 0f
        for (i in 0 until modeCount) total += modes[i * 3 + 2]
        // A Chladni term spans [-2, 2], so the peak of the superposition is
        // twice the summed amplitude.
        return 1f / (2f * max(total, MIN_COLOR_AMPLITUDE))
    }

    /**
     * (Re)builds the index buffer for a `cells x cells` plate. Vertices are
     * derived from `gl_VertexID` in the shader, so this is the only geometry
     * the scene owns; the vertex count stays inside 16-bit indices for every
     * tier in [GRID_TIERS], which is what keeps the buffer at ~0.6 MB rather
     * than 1.2 MB at the top tier.
     */
    private fun ensureGrid(cells: Int) {
        if (cells == gridCells && ibo != 0) return
        if (ibo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(ibo), 0)
            ibo = 0
        }
        gridCells = cells
        val verts = cells + 1
        val indices = ShortArray(cells * cells * 6)
        var at = 0
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                val i00 = (y * verts + x).toShort()
                val i10 = (y * verts + x + 1).toShort()
                val i01 = ((y + 1) * verts + x).toShort()
                val i11 = ((y + 1) * verts + x + 1).toShort()
                indices[at++] = i00
                indices[at++] = i10
                indices[at++] = i01
                indices[at++] = i10
                indices[at++] = i11
                indices[at++] = i01
            }
        }
        val buffer =
            ByteBuffer
                .allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .put(indices)
                .apply { position(0) }
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        ibo = ids[0]
        // The element-array binding is VAO state, so it has to be recorded
        // while the scene's own VAO is bound.
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        indexCount = indices.size
    }

    private fun pLoc(name: String): Int = plateUniforms.getOrPut(name) { GLES30.glGetUniformLocation(plateProgram, name) }

    private fun tLoc(name: String): Int = tableUniforms.getOrPut(name) { GLES30.glGetUniformLocation(tableProgram, name) }

    override fun release() {
        if (plateProgram != 0) GLES30.glDeleteProgram(plateProgram)
        if (tableProgram != 0) GLES30.glDeleteProgram(tableProgram)
        if (ibo != 0) GLES30.glDeleteBuffers(1, intArrayOf(ibo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        plateProgram = 0
        tableProgram = 0
        ibo = 0
        vao = 0
        gridCells = 0
        indexCount = 0
        programsOk = false
        plateUniforms.clear()
        tableUniforms.clear()
    }

    private fun loadRaw(resId: Int): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
}
