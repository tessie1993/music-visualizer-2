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
 *
 * The force and dye injection passes are EXTENSION POINTS (FLUID_SIM v2
 * section 13): [setInjectionShaders] installs user fragment sources in place
 * of the built-in capsule splat; a failed compile keeps the last good
 * program and reports through [onShaderError].
 */
internal class FluidSim(
    private val context: Context,
    /** Velocity-only mode for the FlowField service: no dye grid, no dye passes. */
    private val velocityOnly: Boolean = false,
) {
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

    /** Audio context for user injection shaders (extension points). */
    var audioBass = 0f
    var audioMid = 0f
    var audioTreble = 0f
    var audioEnergy = 0f
    var audioBeat = 0f
    var timeSeconds = 0f

    var onShaderError: (String?) -> Unit = {}

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
    private var baseVertSrc = ""
    private val programs = HashMap<Int, Int>()
    private val uniforms = HashMap<Int, HashMap<String, Int>>()
    private val pending = ArrayList<Splat>()

    /** User injection programs (program handle + uniform cache), or null. */
    private var customForce: Pair<Int, HashMap<String, Int>>? = null
    private var customDye: Pair<Int, HashMap<String, Int>>? = null
    private var pendingForceSrc: String? = null
    private var pendingDyeSrc: String? = null
    private var injectionDirty = false

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
        baseVertSrc = loadRaw(R.raw.fluid_base_vert)
        for (f in frags) {
            val p = GlUtil.buildProgram(baseVertSrc, loadRaw(f))
            programs[f] = p
            uniforms[f] = HashMap()
        }
        // Re-apply any user injection shaders after a context loss.
        injectionDirty = pendingForceSrc != null || pendingDyeSrc != null
    }

    /** Returns true when the surface dimensions actually changed. */
    fun resize(
        w: Int,
        h: Int,
    ): Boolean {
        if (!available) return false
        if (w == width && h == height && velocity != null) return false
        width = w
        height = h
        aspect = w.toFloat() / h.coerceAtLeast(1)
        allocGrids(preserve = true)
        return true
    }

    /**
     * Applies new grid resolutions / iteration count (quality tier change).
     * Reallocates only when the values actually changed; fluid contents are
     * preserved through the copy-resize path. Call at a frame boundary.
     */
    fun applyResolution(
        newSimRes: Int,
        newDyeRes: Int,
    ): Boolean {
        if (!available) return false
        if (newSimRes == simRes && newDyeRes == dyeRes && velocity != null) return false
        simRes = newSimRes
        dyeRes = newDyeRes
        if (width > 1) allocGrids(preserve = true)
        return true
    }

    private fun allocGrids(preserve: Boolean) {
        val (sw, sh) = FluidBuffers.resolution(simRes, width, height)
        val (dw, dh) = FluidBuffers.resolution(dyeRes, width, height)
        val oldVelocity = velocity
        val oldDye = dye
        val oldPressure = pressure
        divergence?.release()
        curl?.release()
        // Velocity NEAREST (manual bilerp in advection); dye LINEAR for display.
        velocity = FluidBuffers.DoubleFbo(sw, sh, formats.rg, linear = false).also { it.create() }
        if (!velocityOnly) dye = FluidBuffers.DoubleFbo(dw, dh, formats.rgba, linear = true).also { it.create() }
        pressure = FluidBuffers.DoubleFbo(sw, sh, formats.r, linear = false).also { it.create() }
        divergence = FluidBuffers.Fbo(sw, sh, formats.r, linear = false).also { it.create() }
        curl = FluidBuffers.Fbo(sw, sh, formats.r, linear = false).also { it.create() }
        if (preserve && oldVelocity != null) {
            // Copy-preserving resize: the picture survives rotation and
            // quality changes instead of resetting to black.
            copyInto(oldVelocity.read, velocity!!.read)
            oldDye?.let { old -> dye?.let { copyInto(old.read, it.read) } }
            oldPressure?.let { old -> pressure?.let { copyInto(old.read, it.read) } }
        }
        oldVelocity?.release()
        oldDye?.release()
        oldPressure?.release()
        cellSize = 2f / sh
        rdx = 1f / cellSize
        halfRdx = 0.5f / cellSize
        alpha = -cellSize * cellSize
    }

    private fun copyInto(
        src: FluidBuffers.Fbo,
        dst: FluidBuffers.Fbo,
    ) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)
        useProgram(R.raw.fluid_copy_frag, dst.width, dst.height)
        bindTex("uTexture", src.tex, 0, R.raw.fluid_copy_frag)
        blit(dst)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Installs user force/dye injection fragment sources (null = built-in
     * capsule splat). Thread-safe; compiled on the GL thread in [step].
     */
    @Synchronized
    fun setInjectionShaders(
        forceSrc: String?,
        dyeSrc: String?,
    ) {
        pendingForceSrc = forceSrc
        pendingDyeSrc = dyeSrc
        injectionDirty = true
    }

    private fun compileInjectionIfNeeded() {
        val (force, dyeS) =
            synchronized(this) {
                if (!injectionDirty) return
                injectionDirty = false
                pendingForceSrc to pendingDyeSrc
            }
        customForce = compileCustom(force, customForce)
        customDye = compileCustom(dyeS, customDye)
    }

    private fun compileCustom(
        src: String?,
        current: Pair<Int, HashMap<String, Int>>?,
    ): Pair<Int, HashMap<String, Int>>? {
        if (src.isNullOrBlank()) {
            current?.let { GLES30.glDeleteProgram(it.first) }
            return null
        }
        return try {
            val p = GlUtil.buildProgram(baseVertSrc, src)
            current?.let { GLES30.glDeleteProgram(it.first) }
            onShaderError(null)
            p to HashMap()
        } catch (e: GlUtil.ShaderCompileException) {
            // Keep the last good program rather than dropping to black.
            onShaderError(e.message)
            current
        }
    }

    fun queueSplat(s: Splat) {
        pending.add(s)
    }

    val dyeTex: Int get() = dye?.read?.tex ?: 0
    val velocityTex: Int get() = velocity?.read?.tex ?: 0
    val velocityGridHeight: Int get() = velocity?.height ?: 128

    /** v2 pass order: advect vel -> forces -> curl -> vorticity -> project -> dye. */
    fun step(dtRaw: Float) {
        if (!available) return
        val vel = velocity ?: return
        val press = pressure ?: return
        val div = divergence ?: return
        val crl = curl ?: return
        val dt = dtRaw.coerceIn(0f, 1f / 60f)
        compileInjectionIfNeeded()
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)
        val velInvW = 1f / vel.width
        val velInvH = 1f / vel.height

        // 1. Advect velocity (velocity is both field and carrier).
        useProgram(R.raw.fluid_advect_frag, vel.width, vel.height)
        bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_advect_frag)
        bindTex("uSource", vel.read.tex, 0, R.raw.fluid_advect_frag)
        set2f(R.raw.fluid_advect_frag, "uSrcInvRes", velInvW, velInvH)
        set2f(R.raw.fluid_advect_frag, "uVelInvRes", velInvW, velInvH)
        set1f(R.raw.fluid_advect_frag, "uDt", dt)
        set1f(R.raw.fluid_advect_frag, "uRdx", rdx)
        val vd = 1f + velocityDissipation * dt
        set3f(R.raw.fluid_advect_frag, "uDecay", vd, vd, vd)
        blit(vel.write)
        vel.swap()

        // 2. Forces: capsule velocity splats (blend toward target), or the
        //    user force shader when one is installed (extension point).
        runInjection(vel, mode = 0, custom = customForce, dt = dt)

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

        // 8. Dye injection (additive / user shader), 9. dye advection.
        val dyeB = dye
        if (!velocityOnly && dyeB != null) {
            runInjection(dyeB, mode = 1, custom = customDye, dt = dt)
            useProgram(R.raw.fluid_advect_frag, dyeB.width, dyeB.height)
            bindTex("uVelocity", vel.read.tex, 0, R.raw.fluid_advect_frag)
            bindTex("uSource", dyeB.read.tex, 1, R.raw.fluid_advect_frag)
            set2f(R.raw.fluid_advect_frag, "uSrcInvRes", 1f / dyeB.width, 1f / dyeB.height)
            set2f(R.raw.fluid_advect_frag, "uVelInvRes", velInvW, velInvH)
            set1f(R.raw.fluid_advect_frag, "uDt", dt)
            set1f(R.raw.fluid_advect_frag, "uRdx", rdx)
            val a = chromaticAging.coerceIn(0f, 1f)
            val ddR = 1f + densityDissipation * (1f - 0.20f * a) * dt
            val ddG = 1f + densityDissipation * (1f + 0.35f * a) * dt
            val ddB = 1f + densityDissipation * (1f - 0.05f * a) * dt
            set3f(R.raw.fluid_advect_frag, "uDecay", ddR, ddG, ddB)
            blit(dyeB.write)
            dyeB.swap()
        }
        pending.clear()
        GLES30.glBindVertexArray(0)
    }

    /** One injection pass per queued splat, via the built-in or user program. */
    private fun runInjection(
        target: FluidBuffers.DoubleFbo,
        mode: Int,
        custom: Pair<Int, HashMap<String, Int>>?,
        dt: Float,
    ) {
        for (s in pending) {
            val (program, cache) =
                custom ?: (programs.getValue(R.raw.fluid_splat_frag) to uniforms.getValue(R.raw.fluid_splat_frag))
            GLES30.glUseProgram(program)

            fun cLoc(name: String): Int = cache.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }
            GLES30.glUniform2f(cLoc("uInvRes"), 1f / target.width, 1f / target.height)
            GLES30.glUniform1f(cLoc("uAspect"), aspect)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.read.tex)
            GLES30.glUniform1i(cLoc("uTarget"), 0)
            GLES30.glUniform2f(cLoc("uPrev"), s.prevX, s.prevY)
            GLES30.glUniform2f(cLoc("uCur"), s.curX, s.curY)
            GLES30.glUniform1f(cLoc("uRadius"), s.radius)
            if (mode == 0) {
                GLES30.glUniform3f(cLoc("uValue"), s.velX, s.velY, 0f)
            } else {
                GLES30.glUniform3f(cLoc("uValue"), s.r, s.g, s.b)
            }
            GLES30.glUniform1i(cLoc("uMode"), mode)
            if (custom != null) {
                // Extension-point context (same set the scene shaders get).
                GLES30.glUniform1f(cLoc("uDt"), dt)
                GLES30.glUniform1f(cLoc("uDx"), cellSize)
                GLES30.glUniform1f(cLoc("uTime"), timeSeconds)
                GLES30.glUniform1f(cLoc("uBass"), audioBass)
                GLES30.glUniform1f(cLoc("uMid"), audioMid)
                GLES30.glUniform1f(cLoc("uTreble"), audioTreble)
                GLES30.glUniform1f(cLoc("uEnergy"), audioEnergy)
                GLES30.glUniform1f(cLoc("uBeat"), audioBeat)
            }
            blit(target.write)
            target.swap()
        }
    }

    /** Plain dye presentation fallback (no look chain), into the bound FBO. */
    fun drawDisplay() {
        if (!available) return
        val d = dye ?: return
        GLES30.glBindVertexArray(vao)
        val prog = programs.getValue(R.raw.fluid_display_frag)
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, d.read.tex)
        GLES30.glUniform1i(loc(R.raw.fluid_display_frag, "uDye"), 0)
        GLES30.glUniform2f(loc(R.raw.fluid_display_frag, "uInvRes"), 1f / d.width, 1f / d.height)
        GLES30.glUniform2f(loc(R.raw.fluid_display_frag, "uTexelSize"), 1f / d.width, 1f / d.height)
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
        customForce?.let { GLES30.glDeleteProgram(it.first) }
        customDye?.let { GLES30.glDeleteProgram(it.first) }
        customForce = null
        customDye = null
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

    private fun loadRaw(resId: Int): String = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
}
