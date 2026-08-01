package dev.musicviz.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.SceneParams

/**
 * F7 FlowField service per FLUID_SIM v2 section 12: a stripped, velocity-only
 * fluid simulation (64 grid, 12 Jacobi iterations, no dye, no look chain)
 * run as a shared service so fluid motion can drive ANY style:
 *
 * 1. the universal `fluidWarp` composite FX samples [velocityTex],
 * 2. particle scenes ride a 16x16 CPU downsample ([cpuGrid]),
 * 3. shader scenes get the field as `uFlow`.
 *
 * When the FLUID scene is active the renderer does NOT run this service -
 * it exposes the fluid's own velocity texture instead (one source of truth,
 * zero duplicate cost) and only the readback path here is used.
 */
internal class FlowField(
    context: Context,
) {
    companion object {
        private const val GRID_RES = 64

        /**
         * Resolution of the CPU downsample. 16 was a drift field and nothing
         * more: at that spacing a whole vortex of the 64-grid sim collapses to
         * one bilinear cell, so a particle rode a smooth ramp rather than a
         * flow. 32 resolves the eddies the sim actually produces while keeping
         * the per-frame `glReadPixels` at 16 KB - still a rounding error next
         * to the pipeline stall the call itself costs.
         */
        const val CPU_GRID = 32

        /**
         * Force convention shared with `FluidEmitters`: it scales its own
         * splat velocities by 0.22, and a consumer kick of the same magnitude
         * has to land with the same authority or the two sources of motion
         * would be on different scales.
         */
        private const val KICK_FORCE = 0.22f
    }

    /**
     * CPU downsample of the velocity field for CPU-side particle scenes.
     * [data] holds RGBA texels (xy = grid velocity); [scale] converts grid
     * velocity to sim-space units/second; [aspect] maps sim x to clip x.
     */
    class CpuGrid {
        val data = FloatArray(CPU_GRID * CPU_GRID * 4)
        var scale = 0f
        var aspect = 1f

        /**
         * Bilinear sample at clip-space (u,v) in [0,1]; writes clip-space
         * velocity (units/second) into [out].
         */
        fun sample(
            u: Float,
            v: Float,
            out: FloatArray,
        ) {
            val x = (u.coerceIn(0f, 1f) * (CPU_GRID - 1)).coerceAtLeast(0f)
            val y = (v.coerceIn(0f, 1f) * (CPU_GRID - 1)).coerceAtLeast(0f)
            val x0 = x.toInt().coerceAtMost(CPU_GRID - 2)
            val y0 = y.toInt().coerceAtMost(CPU_GRID - 2)
            val fx = x - x0
            val fy = y - y0

            fun at(
                gx: Int,
                gy: Int,
                c: Int,
            ) = data[(gy * CPU_GRID + gx) * 4 + c]
            for (c in 0 until 2) {
                val a = at(x0, y0, c) * (1 - fx) + at(x0 + 1, y0, c) * fx
                val b = at(x0, y0 + 1, c) * (1 - fx) + at(x0 + 1, y0 + 1, c) * fx
                out[c] = (a * (1 - fy) + b * fy) * scale
            }
            // Sim x spans [-aspect, aspect] but clip x spans [-1, 1].
            if (aspect > 1e-3f) out[0] /= aspect
        }
    }

    private val sim = FluidSim(context, velocityOnly = true)
    private val emitters =
        FluidEmitters().apply {
            beatPattern = FluidEmitters.PATTERN_RING
            beatSplats = 2
            stirrers = 2
            sparkle = false
        }

    val cpuGrid = CpuGrid()
    private var readFbo = 0
    private var readTex = 0
    private var copyProgram = 0
    private var copyVao = 0
    private var copyVbo = 0
    private val readBuf =
        java.nio.ByteBuffer
            .allocateDirect(CPU_GRID * CPU_GRID * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder())

    /**
     * Typed view of [readBuf], made once rather than once per [readback]:
     * `asFloatBuffer()` allocates a fresh DirectFloatBufferU every call and
     * this runs every frame a particle style rides the field. Safe to keep
     * because [readBuf] is a `val` created at position 0, so the view spans
     * it for good, and both are GL-thread only.
     */
    private val readFloats = readBuf.asFloatBuffer()

    /**
     * GL state snapshot for [readback], hoisted the way [WaterScene] and
     * [CurlFlowScene] keep theirs. Written and read back inside one call,
     * which is what makes reusing them safe.
     */
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    /**
     * This frame's splat requests, reused across frames: [step] fills it and
     * drains it into the sim in the same call, so nothing is still reading
     * last frame's contents when it is cleared.
     */
    private val splats = ArrayList<FluidSim.Splat>()
    private var canReadback = false

    val available: Boolean get() = sim.available
    val velocityTex: Int get() = sim.velocityTex

    /** Grid velocity -> sim units/second for this service's own field. */
    val flowScale: Float get() = sim.flowScale
    val aspect: Float get() = sim.aspect

    fun create() {
        release()
        sim.simRes = GRID_RES
        sim.pressureIterations = 12
        sim.create()
        if (!sim.available) return
        // Tiny RGBA32F target for glReadPixels (float readback of RGBA/FLOAT
        // is only guaranteed against a full-float color buffer). Without
        // renderable RGBA32F the CPU-advection consumer is skipped; the
        // composite fluidWarp and uFlow paths still work.
        val fmt = sim.texFormats.rgba32
        canReadback = fmt != null
        if (fmt != null) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            readTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, readTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                fmt.internal,
                CPU_GRID,
                CPU_GRID,
                0,
                fmt.format,
                fmt.type,
                null,
            )
            GLES30.glGenFramebuffers(1, ids, 0)
            readFbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                readTex,
                0,
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            try {
                val ctx = copyContext()
                copyProgram = ctx.first
                copyVao = ctx.second
                copyVbo = ctx.third
            } catch (e: GlUtil.ShaderCompileException) {
                // The uFlow / fluidWarp paths still work without CPU readback.
                android.util.Log.w("FluidSim", "flowfield copy shader rejected: ${e.message}")
                canReadback = false
            }
        }
    }

    private fun copyContext(): Triple<Int, Int, Int> {
        val program =
            GlUtil.buildProgram(
                loadRaw(R.raw.fluid_base_vert),
                loadRaw(R.raw.fluid_copy_frag),
            )
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        val vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        val vbo = ids[0]
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
        return Triple(program, vao, vbo)
    }

    fun resize(
        w: Int,
        h: Int,
    ) {
        sim.resize(w, h)
    }

    /**
     * Injects a velocity kick from a CONSUMER of the field - the return leg of
     * two-way coupling, used by the particle styles that push back into the
     * flow they ride (see `InkflowScene`). Coordinates are clip space
     * (-1..1); this maps them onto the sim's own aspect-scaled x, applies the
     * emitters' force convention so a scene kick and an emitter splat of the
     * same magnitude do the same thing, and drops the dye (the service is
     * velocity-only, so a colour here would be discarded downstream anyway).
     *
     * Queued splats are consumed by the next [step]; kicks that arrive while
     * the field is unavailable are dropped rather than accumulated.
     */
    fun queueKick(
        clipX: Float,
        clipY: Float,
        velX: Float,
        velY: Float,
        radius: Float,
    ) {
        if (!sim.available) return
        val a = sim.aspect
        val x = clipX * a
        val y = clipY
        sim.queueSplat(
            FluidSim.Splat(
                prevX = x,
                prevY = y,
                curX = x,
                curY = y,
                radius = radius.coerceIn(0.02f, 0.4f),
                velX = velX * a * KICK_FORCE,
                velY = velY * KICK_FORCE,
                r = 0f,
                g = 0f,
                b = 0f,
            ),
        )
    }

    /**
     * Advances the field one frame from the audio features. Binds its own
     * FBOs; call before the engine binds the scene target.
     */
    fun step(
        features: AudioFeatures,
        dt: Float,
        p: SceneParams,
    ) {
        if (!sim.available) return
        sim.curlStrength = p.flowCurl.coerceIn(0f, 50f)
        emitters.forceScale = p.flowForce.coerceIn(0f, 3f)
        emitters.stirrerSpeed = p.speed.coerceIn(0.1f, 2f)
        val simDt = dt.coerceIn(0f, 1f / 30f)
        emitters.tick(features, simDt, sim.aspect, 0f, 1f, splats)
        for (i in splats.indices) sim.queueSplat(splats[i])
        sim.step(simDt)
    }

    /**
     * Downsamples [sourceTex] (this service's field, or the FLUID scene's
     * own velocity texture) into [cpuGrid] for CPU particle advection.
     * [sourceFlowScale] is that field's grid-velocity -> sim-units/s factor.
     */
    fun readback(
        sourceTex: Int,
        sourceFlowScale: Float,
        sourceAspect: Float,
    ) {
        if (!canReadback || sourceTex == 0) return
        // Callers invoke this mid-frame with their scene target already bound;
        // the binding and viewport must survive the detour into readFbo.
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(copyProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GLES30.glViewport(0, 0, CPU_GRID, CPU_GRID)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "uTexture"), 0)
        GLES30.glBindVertexArray(copyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        readBuf.clear()
        GLES30.glReadPixels(0, 0, CPU_GRID, CPU_GRID, GLES30.GL_RGBA, GLES30.GL_FLOAT, readBuf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        readFloats.clear()
        readFloats.get(cpuGrid.data)
        cpuGrid.scale = sourceFlowScale
        cpuGrid.aspect = sourceAspect
    }

    fun release() {
        sim.release()
        if (readTex != 0) GLES30.glDeleteTextures(1, intArrayOf(readTex), 0)
        if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
        if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
        if (copyVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(copyVbo), 0)
        if (copyVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(copyVao), 0)
        readTex = 0
        readFbo = 0
        copyProgram = 0
        copyVao = 0
        copyVbo = 0
        canReadback = false
    }

    private val contextRef = context.applicationContext

    /** Reads a raw shader, resolving its `//#include` directives. */
    private fun loadRaw(resId: Int): String = GlUtil.loadShader(contextRef, resId)
}
