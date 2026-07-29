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
 * propagation, damping and CFL stability.
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
    )

    companion object {
        /** Pending-queue cap: beyond this, extra drops are dropped (sic). */
        private const val MAX_PENDING = 64

        /** Drops batched into one splat pass (uDrops uniform array size). */
        private const val DROPS_PER_PASS = 8

        /** Bound on CFL substeps per frame (cost rail at high wave speeds). */
        private const val MAX_SUBSTEPS = 6
    }

    /** Short-side grid resolution; see [applyResolution]. */
    var simRes = 384
        private set

    /** Wave speed c in sim units/s (domain height = 2). */
    var waveSpeed = 1.2f

    /** Per-1/60s velocity decay factor (converted per CFL substep). */
    var damping = 0.985f

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
    private var vao = 0
    private var vbo = 0
    private val programs = HashMap<Int, Int>()
    private val uniforms = HashMap<Int, HashMap<String, Int>>()
    private val pending = ArrayList<Drop>()
    private val dropVec = FloatArray(DROPS_PER_PASS * 4)
    private val drained = ArrayList<Drop>()

    val heightTex: Int get() = grid?.read?.tex ?: 0
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
        try {
            for (f in intArrayOf(R.raw.ripple_splat_frag, R.raw.ripple_update_frag)) {
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
        cellSize = 2f / gh
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
    }

    /**
     * Queues a drop (sim space: y in [-1,1], x in [-aspect,aspect]).
     * Thread-safe; drained on the GL thread in [step]. Capped so a burst of
     * events can't accumulate an unbounded backlog.
     */
    @Synchronized
    fun queueDrop(
        x: Float,
        y: Float,
        radius: Float,
        amplitude: Float,
    ) {
        if (!available) return
        if (pending.size >= MAX_PENDING) return
        pending.add(Drop(x, y, radius, amplitude))
    }

    /**
     * One frame: batched drop injection (up to [DROPS_PER_PASS] per splat
     * pass), then the wave update - iterated in CFL-clamped substeps
     * ([RippleMath.cflClampedDt]) so high wave speeds stay stable while
     * ripples still cross the screen in real time. Caller (the scene) owns
     * the framebuffer/viewport snapshot around the whole draw, matching how
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

        // 1. Drop injection, batched.
        var i = 0
        while (i < drained.size) {
            val n = minOf(DROPS_PER_PASS, drained.size - i)
            for (j in 0 until n) {
                val d = drained[i + j]
                dropVec[j * 4] = d.x
                dropVec[j * 4 + 1] = d.y
                dropVec[j * 4 + 2] = d.radius
                dropVec[j * 4 + 3] = d.amplitude
            }
            useProgram(R.raw.ripple_splat_frag, g.width, g.height)
            bindTex("uTarget", g.read.tex, 0, R.raw.ripple_splat_frag)
            GLES30.glUniform4fv(loc(R.raw.ripple_splat_frag, "uDrops"), DROPS_PER_PASS, dropVec, 0)
            GLES30.glUniform1i(loc(R.raw.ripple_splat_frag, "uDropCount"), n)
            blit(g.write)
            g.swap()
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
            val subDamping =
                Math
                    .pow(damping.coerceIn(0.9f, 0.999f).toDouble(), (subDt * 60f).toDouble())
                    .toFloat()
            val k = c * c * subDt / (cellSize * cellSize)
            useProgram(R.raw.ripple_update_frag, g.width, g.height)
            set1f(R.raw.ripple_update_frag, "uK", k)
            set1f(R.raw.ripple_update_frag, "uDt", subDt)
            set1f(R.raw.ripple_update_frag, "uDamping", subDamping)
            repeat(substeps) {
                bindTex("uHeight", g.read.tex, 0, R.raw.ripple_update_frag)
                blit(g.write)
                g.swap()
            }
        }
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        grid?.release()
        grid = null
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
