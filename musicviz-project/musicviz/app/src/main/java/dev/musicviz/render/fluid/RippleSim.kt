package dev.musicviz.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.render.scene.GlUtil

/**
 * GPU heightfield water ("ripple") simulation: one half-float ping-pong grid
 * (R = height, G = velocity) advanced by the explicit velocity-form wave
 * equation, drops injected as batched Gaussian bumps. Pure simulation - no
 * audio, no UI; [WaterScene] converts musical events into [queueDrop]s. The
 * math is mirrored 1:1 by [RippleMath] so the headless gate verifies
 * propagation, damping, height drain and CFL stability.
 *
 * With [inkEnabled] the sim also carries a LIQUID INK field: an RGBA colour
 * film transported by the surface flow ([R.raw.water_ink_advect_frag]) and
 * stained by every drop's own palette colour. That layer is what makes the
 * WATER style read as the visuals themselves gone liquid instead of as a
 * tinted pool behind them; the renderer-owned ripple overlay leaves it off,
 * because there the underlying scene already supplies the image.
 *
 * Defensive conventions follow [FluidSim]: all GL work on the GL thread,
 * driver-rejected shaders/grids degrade to available=false + [onShaderError]
 * (never a GL-thread crash), reallocation snapshots and restores the
 * caller's framebuffer + viewport, and [queueDrop] is thread-safe with the
 * queue drained on the GL thread in [step].
 */
internal class RippleSim(
    private val context: Context,
) {
    private class Drop(
        val x: Float,
        val y: Float,
        val radius: Float,
        val amplitude: Float,
        val r: Float,
        val g: Float,
        val b: Float,
    )

    companion object {
        /** Pending-queue cap: beyond this, extra drops are dropped (sic). */
        private const val MAX_PENDING = 64

        /** Drops batched into one splat pass (uDrops uniform array size). */
        private const val DROPS_PER_PASS = 8

        /** Bound on CFL substeps per frame (cost rail at high wave speeds). */
        private const val MAX_SUBSTEPS = 6

        /** Ink RGB rail: enough HDR headroom to bloom, short of half-float loss. */
        private const val INK_CEILING = 6f
    }

    /** Short-side grid resolution; see [applyResolution]. */
    var simRes = 384
        private set

    /** Wave speed c in sim units/s (domain height = 2). */
    var waveSpeed = 1.2f

    /** Per-1/60s velocity decay factor (converted per CFL substep). */
    var damping = 0.985f

    /**
     * Allocate and run the liquid ink layer. Set BEFORE [create]/[resize];
     * flipping it later takes effect on the next grid allocation.
     */
    var inkEnabled = false

    /** Slope -> ink transport gain (uv/s per unit of height gradient). */
    var inkFlow = 1f

    /** Ink fade rate; 0 keeps the film forever, higher clears the pool sooner. */
    var inkDissipation = 0.35f

    var onShaderError: (String?) -> Unit = {}

    var available = false
        private set

    private var width = 1
    private var height = 1
    var aspect = 1f
        private set

    /** Sim-space width of one grid cell (domain height is 2 sim units). */
    private var cellSize = 2f / 384f

    private lateinit var formats: FluidBuffers.Formats
    private var grid: FluidBuffers.DoubleFbo? = null
    private var ink: FluidBuffers.DoubleFbo? = null
    private var vao = 0
    private var vbo = 0
    private val programs = HashMap<Int, Int>()
    private val uniforms = HashMap<Int, HashMap<String, Int>>()
    private val pending = ArrayList<Drop>()
    private val dropVec = FloatArray(DROPS_PER_PASS * 4)
    private val dropColorVec = FloatArray(DROPS_PER_PASS * 4)
    private val drained = ArrayList<Drop>()

    val heightTex: Int get() = grid?.read?.tex ?: 0

    /** Liquid ink colour field, or 0 while [inkEnabled] is off. */
    val inkTex: Int get() = ink?.read?.tex ?: 0

    /** True once the ink field is allocated and being stepped. */
    val inkAvailable: Boolean get() = ink?.ok == true
    val texelW: Float get() = 1f / (grid?.width ?: 1)
    val texelH: Float get() = 1f / (grid?.height ?: 1)

    /** Resolved formats from the probe, for the owning scene's own passes. */
    val texFormats: FluidBuffers.Formats get() = formats

    fun create() {
        release()
        formats = FluidBuffers.probeFormats()
        available = formats.ok
        if (!available) return
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf =
            java.nio.ByteBuffer
                .allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(quad)
                .apply { position(0) }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        // A driver-rejected shader must degrade the style to "unavailable",
        // never crash the GL thread (FluidSim convention).
        val baseVert = loadRaw(R.raw.fluid_base_vert)
        val frags =
            if (inkEnabled) {
                intArrayOf(
                    R.raw.ripple_splat_frag,
                    R.raw.ripple_update_frag,
                    R.raw.water_ink_splat_frag,
                    R.raw.water_ink_advect_frag,
                )
            } else {
                intArrayOf(R.raw.ripple_splat_frag, R.raw.ripple_update_frag)
            }
        try {
            for (f in frags) {
                programs[f] = GlUtil.buildProgram(baseVert, loadRaw(f))
                uniforms[f] = HashMap()
            }
        } catch (e: GlUtil.ShaderCompileException) {
            android.util.Log.w("RippleSim", "ripple shader rejected by driver: ${e.message}")
            onShaderError("Water unavailable on this GPU: ${e.message}")
            release()
        }
    }

    /** Returns true when the surface dimensions actually changed. */
    fun resize(
        w: Int,
        h: Int,
    ): Boolean {
        if (!available) return false
        if (w == width && h == height && grid != null) return false
        width = w
        height = h
        aspect = w.toFloat() / h.coerceAtLeast(1)
        allocGrid()
        return true
    }

    /** Applies a new short-side grid resolution (quality tier change). */
    fun applyResolution(newSimRes: Int): Boolean {
        if (!available) return false
        if (newSimRes == simRes && grid != null) return false
        simRes = newSimRes
        if (width > 1) allocGrid()
        return true
    }

    private fun allocGrid() {
        // Reallocation runs outside the scene's draw snapshot and rebinds
        // framebuffer state via Fbo.create(); restore both on exit so the
        // engine's next pass never renders into the ripple grid.
        val prevFbo = IntArray(1)
        val prevVp = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevVp, 0)
        grid?.release()
        ink?.release()
        ink = null
        val (gw, gh) = FluidBuffers.resolution(simRes, width, height)
        // LINEAR: the display pass samples the grid at screen resolution.
        grid = FluidBuffers.DoubleFbo(gw, gh, formats.rg, linear = true).also { it.create() }
        if (grid?.ok != true) {
            android.util.Log.w("RippleSim", "ripple grid allocation failed (${gw}x$gh) - water disabled")
            onShaderError("Water grid could not be allocated on this GPU")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
            GLES30.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
            release()
            return
        }
        if (inkEnabled) {
            // The ink layer is an ENHANCEMENT: if the driver refuses the extra
            // RGBA16F pair the pool still renders, just without the liquid
            // film, rather than taking the whole style down with it.
            ink = FluidBuffers.DoubleFbo(gw, gh, formats.rgba, linear = true).also { it.create() }
            if (ink?.ok != true) {
                android.util.Log.w("RippleSim", "ink grid allocation failed (${gw}x$gh) - liquid layer off")
                ink?.release()
                ink = null
            } else {
                clearInk()
            }
        }
        cellSize = 2f / gh
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
    }

    /**
     * Clears the ink film to fully TRANSPARENT.
     *
     * [FluidBuffers.Fbo.create] clears to opaque black (0,0,0,1) - right for
     * the height/velocity grids it was written for, wrong here: alpha is the
     * film's COVERAGE, so a fresh buffer would claim the whole pool is covered
     * in black liquid and the display pass would render the style as a dark
     * sheet until dissipation ate the alpha away. Visible on entry, and again
     * on every resize and quality-tier change.
     */
    private fun clearInk() {
        val fbo = ink ?: return
        for (side in listOf(fbo.read, fbo.write)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, side.fbo)
            GLES30.glViewport(0, 0, side.width, side.height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    /**
     * Queues a drop (sim space: y in [-1,1], x in [-aspect,aspect]).
     * Thread-safe; drained on the GL thread in [step]. Capped so a burst of
     * events can't accumulate an unbounded backlog.
     *
     * [r]/[g]/[b] stain the liquid ink layer where one is running; they are
     * ignored entirely when it is not, so the overlay's colourless callers
     * need no separate entry point.
     */
    @Synchronized
    fun queueDrop(
        x: Float,
        y: Float,
        radius: Float,
        amplitude: Float,
        r: Float = 0f,
        g: Float = 0f,
        b: Float = 0f,
    ) {
        if (!available) return
        if (pending.size >= MAX_PENDING) return
        pending.add(Drop(x, y, radius, amplitude, r, g, b))
    }

    /**
     * Queues one frame of a finger drag as the crest/trough pair
     * [RippleMath.strokeDrops] describes - the touch-smear input path. Safe
     * from the UI thread; the drops land on the GL thread like any other.
     */
    fun queueStroke(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        dt: Float,
        radius: Float,
        strength: Float,
        r: Float = 0f,
        g: Float = 0f,
        b: Float = 0f,
    ) {
        for (d in RippleMath.strokeDrops(x, y, dx, dy, dt, radius, strength)) {
            queueDrop(d.x, d.y, d.radius, d.amplitude, r, g, b)
        }
    }

    /**
     * One frame: batched drop injection (up to [DROPS_PER_PASS] per splat
     * pass) into the height field and, where it runs, the ink film; then the
     * wave update - iterated in CFL-clamped substeps
     * ([RippleMath.cflClampedDt]) so high wave speeds stay stable while
     * ripples still cross the screen in real time - and finally one ink
     * transport pass along the fresh surface. Caller (the scene) owns the
     * framebuffer/viewport snapshot around the whole draw, matching how
     * FluidScene wraps FluidSim.step.
     */
    fun step(dtRaw: Float) {
        if (!available) return
        val g =
            grid ?: run {
                synchronized(this) { pending.clear() }
                return
            }
        drained.clear()
        synchronized(this) {
            drained.addAll(pending)
            pending.clear()
        }
        val dt = dtRaw.coerceIn(0f, 1f / 30f)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)

        // 1. Drop injection, batched. The same batch feeds the height splat
        //    and the ink splat, so a drop's ring and its colour always land in
        //    the same place.
        val inkFbo = ink
        var i = 0
        while (i < drained.size) {
            val n = minOf(DROPS_PER_PASS, drained.size - i)
            for (j in 0 until n) {
                val d = drained[i + j]
                dropVec[j * 4] = d.x
                dropVec[j * 4 + 1] = d.y
                dropVec[j * 4 + 2] = d.radius
                dropVec[j * 4 + 3] = d.amplitude
                dropColorVec[j * 4] = d.r
                dropColorVec[j * 4 + 1] = d.g
                dropColorVec[j * 4 + 2] = d.b
                // Coverage from the drop's own brightness: a colourless drop
                // (the overlay, a smear with no palette) stains nothing.
                dropColorVec[j * 4 + 3] = maxOf(d.r, maxOf(d.g, d.b))
            }
            useProgram(R.raw.ripple_splat_frag, g.width, g.height)
            bindTex("uTarget", g.read.tex, 0, R.raw.ripple_splat_frag)
            GLES30.glUniform4fv(loc(R.raw.ripple_splat_frag, "uDrops"), DROPS_PER_PASS, dropVec, 0)
            GLES30.glUniform1i(loc(R.raw.ripple_splat_frag, "uDropCount"), n)
            blit(g.write)
            g.swap()
            if (inkFbo != null) {
                useProgram(R.raw.water_ink_splat_frag, inkFbo.width, inkFbo.height)
                bindTex("uTarget", inkFbo.read.tex, 0, R.raw.water_ink_splat_frag)
                GLES30.glUniform4fv(loc(R.raw.water_ink_splat_frag, "uDrops"), DROPS_PER_PASS, dropVec, 0)
                GLES30.glUniform4fv(loc(R.raw.water_ink_splat_frag, "uDropColor"), DROPS_PER_PASS, dropColorVec, 0)
                GLES30.glUniform1i(loc(R.raw.water_ink_splat_frag, "uDropCount"), n)
                set1f(R.raw.water_ink_splat_frag, "uCeiling", INK_CEILING)
                blit(inkFbo.write)
                inkFbo.swap()
            }
            i += n
        }
        drained.clear()

        // 2. Wave update in CFL-stable substeps. The substep count is capped
        //    (cost rail); if the cap forces sub-CFL-violating steps the
        //    per-substep clamp wins - waves slow down instead of exploding.
        if (dt > 0f) {
            val c = waveSpeed.coerceAtLeast(1e-4f)
            val cfl = RippleMath.cflClampedDt(c, dt, cellSize)
            val substeps =
                kotlin.math
                    .ceil((dt / cfl).toDouble())
                    .toInt()
                    .coerceIn(1, MAX_SUBSTEPS)
            val subDt = RippleMath.cflClampedDt(c, dt / substeps, cellSize)
            // damping is calibrated per 1/60 s; renormalize per substep so
            // ripple lifetime doesn't depend on frame rate or substep count.
            val clampedDamping = damping.coerceIn(0.9f, 0.999f)
            val subDamping =
                Math
                    .pow(clampedDamping.toDouble(), (subDt * 60f).toDouble())
                    .toFloat()
            // Height drain, same renormalization. Without it the wave step
            // conserves the mean of h and the pool fills up forever - see
            // RippleMath.HEIGHT_DECAY_RATIO.
            val subHeightDecay = RippleMath.heightDecayPerSubstep(clampedDamping, subDt)
            val k = c * c * subDt / (cellSize * cellSize)
            useProgram(R.raw.ripple_update_frag, g.width, g.height)
            set1f(R.raw.ripple_update_frag, "uK", k)
            set1f(R.raw.ripple_update_frag, "uDt", subDt)
            set1f(R.raw.ripple_update_frag, "uDamping", subDamping)
            set1f(R.raw.ripple_update_frag, "uHeightDecay", subHeightDecay)
            repeat(substeps) {
                bindTex("uHeight", g.read.tex, 0, R.raw.ripple_update_frag)
                blit(g.write)
                g.swap()
            }
        }

        // 3. Ink transport along the surface that step 2 just produced.
        if (inkFbo != null && dt > 0f) {
            useProgram(R.raw.water_ink_advect_frag, inkFbo.width, inkFbo.height)
            bindTex("uInk", inkFbo.read.tex, 0, R.raw.water_ink_advect_frag)
            bindTex("uHeight", g.read.tex, 1, R.raw.water_ink_advect_frag)
            set1f(R.raw.water_ink_advect_frag, "uDt", dt)
            set1f(R.raw.water_ink_advect_frag, "uFlow", inkFlow.coerceIn(0f, 8f))
            set1f(R.raw.water_ink_advect_frag, "uKeep", RippleMath.inkDissipation(inkDissipation, dt))
            blit(inkFbo.write)
            inkFbo.swap()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        grid?.release()
        grid = null
        ink?.release()
        ink = null
        programs.values.forEach { GLES30.glDeleteProgram(it) }
        programs.clear()
        uniforms.clear()
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        vbo = 0
        vao = 0
        synchronized(this) { pending.clear() }
        available = false
    }

    // ---- helpers (FluidSim conventions) ----
    private fun useProgram(
        fragId: Int,
        gridW: Int,
        gridH: Int,
    ) {
        val p = programs.getValue(fragId)
        GLES30.glUseProgram(p)
        GLES30.glUniform2f(loc(fragId, "uInvRes"), 1f / gridW, 1f / gridH)
        GLES30.glUniform1f(loc(fragId, "uAspect"), aspect)
    }

    private fun blit(target: FluidBuffers.Fbo) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
        GLES30.glViewport(0, 0, target.width, target.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun loc(
        fragId: Int,
        name: String,
    ): Int = uniforms.getValue(fragId).getOrPut(name) { GLES30.glGetUniformLocation(programs.getValue(fragId), name) }

    private fun bindTex(
        name: String,
        tex: Int,
        unit: Int,
        fragId: Int,
    ) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc(fragId, name), unit)
    }

    private fun set1f(
        id: Int,
        n: String,
        v: Float,
    ) = GLES30.glUniform1f(loc(id, n), v)

    private fun loadRaw(resId: Int): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
}
