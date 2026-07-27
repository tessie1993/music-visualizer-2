package dev.musicviz.render.fluid

// Ported in part from WebGL-Fluid-Simulation - MIT License,
// Copyright (c) 2017 Pavel Dobryakov. Architecture upgrades (sim-space
// coordinates, boundary sampling, alpha=-dx^2 Jacobi, capsule emitters with
// velocity blending) implemented clean-room from the FLUID_SIM v2 spec /
// GPU Gems ch. 38.

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.render.scene.GlUtil

/**
 * Stam "stable fluids" on GLES3: velocity/dye/pressure grids in half-float
 * ping-pong FBOs, one fullscreen pass per operator. Pure simulation - no
 * audio, no UI. Callers queue [Splat]s, then [step]; [dyeTex]/[velocityTex]
 * expose the fields. All methods run on the GL thread.
 */
internal class FluidSim(private val context: Context) {
    /** Capsule injection request; positions/radius in sim space (y in [-1,1]). */
    class Splat(
        val prevX: Float,
        val prevY: Float,
        val curX: Float,
        val curY: Float,
        val radius: Float,
        // Velocity target (grid units) for the velocity pass:
        val velX: Float,
        val velY: Float,
        // Dye color (HDR-friendly):
        val r: Float,
        val g: Float,
        val b: Float,
    )

    var simRes = 128
    var dyeRes = 512
    var densityDissipation = 1.0f
    var velocityDissipation = 0.2f
    var pressureDamp = 0.8f
    var pressureIterations = 20
    var curlStrength = 30f

    /**
     * Chromatic aging (v2 spec 6.3): spreads the per-channel dye decay so
     * fading ink drifts in hue (G fades fastest -> warm splats cool toward
     * magenta/blue). 0 = identical rates (pure fade, opt-in).
     */
    var chromaticAging = 0f

    private var width = 1
    private var height = 1
    var aspect = 1f
        private set

    // Grid scale: dx is the physical (sim-space) width of one velocity-grid
    // cell - domain height is 2 sim units, so dx = 2/gridHeight. Deriving it
    // from the allocated grid (v2 spec 6.4, alpha = -dx^2) is what decouples
    // sim resolution from visual character.
    private var cellSize = 2f / 128f
    private var rdx = 1f / cellSize
    private var halfRdx = 0.5f / cellSize
    private var alpha = -cellSize * cellSize

    private lateinit var formats: FluidBuffers.Formats
    private var velocity: FluidBuffers.DoubleFbo? = null
    private var dye: FluidBuffers.DoubleFbo? = null
    private var pressure: FluidBuffers.DoubleFbo? = null
    private var divergence: FluidBuffers.Fbo? = null
    private var curl: FluidBuffers.Fbo? = null

    private var vao = 0
    private var vbo = 0
    private val programs = HashMap<Int, Int>()
    private val uniforms = HashMap<Int, HashMap<String, Int>>()
    private val pending = ArrayList<Splat>()

    var available = false
        private set

    /** Resolved texture formats from the probe, for sibling GPU layers. */
    val texFormats: FluidBuffers.Formats get() = formats

    /**
     * Converts fluid grid velocity into sim-space units per second for the
     * particle layer: UV displacement/s = v * rdx * texel; sim height = 2.
     */
    val flowScale: Float get() = 2f * rdx / (velocity?.height ?: 128)

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
            java.nio.ByteBuffer.allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(quad).apply { position(0) }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        val frags =
            intArrayOf(
                R.raw.fluid_splat_frag, R.raw.fluid_advect_frag, R.raw.fluid_curl_frag,
                R.raw.fluid_vorticity_frag, R.raw.fluid_divergence_frag, R.raw.fluid_pressure_frag,
                R.raw.fluid_gradient_frag, R.raw.fluid_clear_frag, R.raw.fluid_copy_frag,
                R.raw.fluid_display_frag,
            )
        val vert = loadRaw(R.raw.fluid_base_vert)
        for (f in frags) {
            val p = GlUtil.buildProgram(vert, loadRaw(f))
            programs[f] = p
            uniforms[f] = HashMap()
        }
    }

    fun resize(
        w: Int,
        h: Int,
    ) {
        if (!available) return
        width = w
        height = h
        aspect = w.toFloat() / h.coerceAtLeast(1)
        allocGrids()
    }

    private fun allocGrids() {
        velocity?.release()
        dye?.release()
        pressure?.release()
        divergence?.release()
        curl?.release()
        val (sw, sh) = FluidBuffers.resolution(simRes, width, height)
        val (dw, dh) = FluidBuffers.resolution(dyeRes, width, height)
        // Velocity NEAREST (manual bilerp in advection); dye LINEAR for display.
        velocity = FluidBuffers.DoubleFbo(sw, sh, formats.rg, linear = false).also { it.create() }
        dye = FluidBuffers.DoubleFbo(dw, dh, formats.rgba, linear = true).also { it.create() }
        pressure = FluidBuffers.DoubleFbo(sw, sh, formats.r, linear = false).also { it.create() }
        divergence = FluidBuffers.Fbo(sw, sh, formats.r, linear = false).also { it.create() }
        curl = FluidBuffers.Fbo(sw, sh, formats.r, linear = false).also { it.create() }
        cellSize = 2f / sh
        rdx = 1f / cellSize
        halfRdx = 0.5f / cellSize
        alpha = -cellSize * cellSize
    }

    fun queueSplat(s: Splat) {
        pending.add(s)
    }

    val dyeTex: Int get() = dye?.read?.tex ?: 0
    val velocityTex: Int get() = velocity?.read?.tex ?: 0

    /** v2 pass order: advect vel -> forces -> curl -> vorticity -> project -> dye. */
    fun step(dtRaw: Float) {
        if (!available) return
        val vel = velocity ?: return
        val dyeB = dye ?: return
        val press = pressure ?: return
        val div = divergence ?: return
        val crl = curl ?: return
        val dt = dtRaw.coerceIn(0f, 1f / 60f)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)

        // 1. Advect velocity (velocity is both field and carrier).
        useProgram(R.raw.fluid_advect_frag, vel.width, vel.height)
        bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_advect_frag)
        bindTex("uSource", vel.read.tex, 0, R.raw.fluid_advect_frag)
        set2f(R.raw.fluid_advect_frag, "uSrcInvRes", 1f / vel.width, 1f / vel.height)
        set1f(R.raw.fluid_advect_frag, "uDt", dt)
        set1f(R.raw.fluid_advect_frag, "uRdx", rdx)
        val vd = 1f + velocityDissipation * dt
        set3f(R.raw.fluid_advect_frag, "uDecay", vd, vd, vd)
        blit(vel.write)
        vel.swap()

        // 2. Forces: capsule velocity splats (blend toward target).
        for (s in pending) {
            useProgram(R.raw.fluid_splat_frag, vel.width, vel.height)
            bindTex("uTarget", vel.read.tex, 0, R.raw.fluid_splat_frag)
            set2f(R.raw.fluid_splat_frag, "uPrev", s.prevX, s.prevY)
            set2f(R.raw.fluid_splat_frag, "uCur", s.curX, s.curY)
            set1f(R.raw.fluid_splat_frag, "uRadius", s.radius)
            set3f(R.raw.fluid_splat_frag, "uValue", s.velX, s.velY, 0f)
            set1i(R.raw.fluid_splat_frag, "uMode", 0)
            blit(vel.write)
            vel.swap()
        }

        // 3-4. Curl + vorticity confinement.
        useProgram(R.raw.fluid_curl_frag, crl.width, crl.height)
        bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_curl_frag)
        set1f(R.raw.fluid_curl_frag, "uHalfRdx", halfRdx)
        blit(crl)
        useProgram(R.raw.fluid_vorticity_frag, vel.width, vel.height)
        bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_vorticity_frag)
        bindTex("uCurl", crl.tex, 1, R.raw.fluid_vorticity_frag)
        set1f(R.raw.fluid_vorticity_frag, "uCurlStrength", curlStrength)
        set1f(R.raw.fluid_vorticity_frag, "uDt", dt)
        blit(vel.write)
        vel.swap()

        // 5-7. Projection: divergence -> damped warm start -> Jacobi -> subtract.
        useProgram(R.raw.fluid_divergence_frag, div.width, div.height)
        bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_divergence_frag)
        set1f(R.raw.fluid_divergence_frag, "uHalfRdx", halfRdx)
        set2f(R.raw.fluid_divergence_frag, "uInvRes", 1f / div.width, 1f / div.height)
        blit(div)
        useProgram(R.raw.fluid_clear_frag, press.width, press.height)
        bindTex("uTexture", press.read.tex, 0, R.raw.fluid_clear_frag)
        set1f(R.raw.fluid_clear_frag, "uValue", pressureDamp)
        blit(press.write)
        press.swap()
        useProgram(R.raw.fluid_pressure_frag, press.width, press.height)
        bindTex("uDivergence", div.tex, 1, R.raw.fluid_pressure_frag)
        set1f(R.raw.fluid_pressure_frag, "uAlpha", alpha)
        set2f(R.raw.fluid_pressure_frag, "uInvRes", 1f / press.width, 1f / press.height)
        repeat(pressureIterations) {
            bindTex("uPressure", press.read.tex, 0, R.raw.fluid_pressure_frag)
            blit(press.write)
            press.swap()
        }
        useProgram(R.raw.fluid_gradient_frag, vel.width, vel.height)
        bindTex("uPressure", press.read.tex, 0, R.raw.fluid_gradient_frag)
        bindTex("uVelocity", vel.read.tex, 1, R.raw.fluid_gradient_frag)
        set1f(R.raw.fluid_gradient_frag, "uHalfRdx", halfRdx)
        set2f(R.raw.fluid_gradient_frag, "uInvRes", 1f / vel.width, 1f / vel.height)
        blit(vel.write)
        vel.swap()

        // 8. Dye injection (additive), 9. dye advection.
        for (s in pending) {
            useProgram(R.raw.fluid_splat_frag, dyeB.width, dyeB.height)
            bindTex("uTarget", dyeB.read.tex, 0, R.raw.fluid_splat_frag)
            set2f(R.raw.fluid_splat_frag, "uPrev", s.prevX, s.prevY)
            set2f(R.raw.fluid_splat_frag, "uCur", s.curX, s.curY)
            set1f(R.raw.fluid_splat_frag, "uRadius", s.radius)
            set3f(R.raw.fluid_splat_frag, "uValue", s.r, s.g, s.b)
            set1i(R.raw.fluid_splat_frag, "uMode", 1)
            blit(dyeB.write)
            dyeB.swap()
        }
        pending.clear()
        useProgram(R.raw.fluid_advect_frag, dyeB.width, dyeB.height)
        bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_advect_frag)
        bindTex("uSource", dyeB.read.tex, 1, R.raw.fluid_advect_frag)
        set2f(R.raw.fluid_advect_frag, "uSrcInvRes", 1f / dyeB.width, 1f / dyeB.height)
        set1f(R.raw.fluid_advect_frag, "uDt", dt)
        set1f(R.raw.fluid_advect_frag, "uRdx", rdx)
        val a = chromaticAging.coerceIn(0f, 1f)
        val ddR = 1f + densityDissipation * (1f - 0.20f * a) * dt
        val ddG = 1f + densityDissipation * (1f + 0.35f * a) * dt
        val ddB = 1f + densityDissipation * (1f - 0.05f * a) * dt
        set3f(R.raw.fluid_advect_frag, "uDecay", ddR, ddG, ddB)
        blit(dyeB.write)
        dyeB.swap()
        GLES30.glBindVertexArray(0)
    }

    /** Draws the dye into whatever framebuffer/viewport is currently bound. */
    fun drawDisplay() {
        if (!available) return
        val d = dye ?: return
        GLES30.glBindVertexArray(vao)
        val prog = programs.getValue(R.raw.fluid_display_frag)
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, d.read.tex)
        GLES30.glUniform1i(loc(R.raw.fluid_display_frag, "uDye"), 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        velocity?.release()
        dye?.release()
        pressure?.release()
        divergence?.release()
        curl?.release()
        velocity = null
        dye = null
        pressure = null
        divergence = null
        curl = null
        programs.values.forEach { GLES30.glDeleteProgram(it) }
        programs.clear()
        uniforms.clear()
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        vbo = 0
        vao = 0
        pending.clear()
        available = false
    }

    // ---- helpers ----
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

    private fun blit(target: FluidBuffers.DoubleFbo) = blit(target.write)

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

    private fun set2f(
        id: Int,
        n: String,
        a: Float,
        b: Float,
    ) = GLES30.glUniform2f(loc(id, n), a, b)

    private fun set3f(
        id: Int,
        n: String,
        a: Float,
        b: Float,
        c: Float,
    ) = GLES30.glUniform3f(loc(id, n), a, b, c)

    private fun set1i(
        id: Int,
        n: String,
        v: Int,
    ) = GLES30.glUniform1i(loc(id, n), v)

    private fun loadRaw(resId: Int): String = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
}
