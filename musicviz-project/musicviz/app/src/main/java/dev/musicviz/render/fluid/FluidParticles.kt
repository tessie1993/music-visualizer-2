package dev.musicviz.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.render.scene.GlUtil

/**
 * F3 GPU particle layer per FLUID_SIM v2 section 8: state lives in an RGBA
 * half-float texture (xy = position in sim space, zw = velocity), one
 * fullscreen quad advances every particle, and a static VBO of texel
 * coordinates drives a GL_POINTS render whose vertex stage fetches state.
 * Drag-based inertia (v += (flow - v) * drag) is what turns tracer dots
 * into streaming light trails. All methods run on the GL thread.
 */
internal class FluidParticles(private val context: Context) {
    var drag = 0.5f

    private var side = 0
    private var count = 0
    private var state: FluidBuffers.DoubleFbo? = null
    private var updateProgram = 0
    private var seedProgram = 0
    private var renderProgram = 0
    private val uniforms = HashMap<Int, HashMap<String, Int>>()
    private var quadVao = 0
    private var quadVbo = 0
    private var pointsVao = 0
    private var pointsVbo = 0
    private var seeded = false

    var available = false
        private set

    fun create(
        particleCount: Int,
        formats: FluidBuffers.Formats,
    ) {
        release()
        side = FluidMath.stateSide(particleCount)
        count = side * side
        // Positions in 16F quantise as particles cluster (spec 5.2): use
        // full-float state when the device can render to it, 16F otherwise.
        val stateFmt = formats.rgba32 ?: formats.rgba
        state = FluidBuffers.DoubleFbo(side, side, stateFmt, linear = false).also { it.create() }

        val vert = loadRaw(R.raw.fluid_base_vert)
        seedProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_particle_seed_frag))
        updateProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_particle_update_frag))
        renderProgram =
            GlUtil.buildProgram(loadRaw(R.raw.fluid_particle_vert), loadRaw(R.raw.fluid_particle_frag))
        uniforms[seedProgram] = HashMap()
        uniforms[updateProgram] = HashMap()
        uniforms[renderProgram] = HashMap()

        // Fullscreen triangle for the state passes.
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        quadVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, floatBuf(quad), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        // Static texel-coordinate VBO: one vec2 per particle, never changes.
        val texels = FloatArray(count * 2)
        var k = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                texels[k++] = (x + 0.5f) / side
                texels[k++] = (y + 0.5f) / side
            }
        }
        GLES30.glGenVertexArrays(1, ids, 0)
        pointsVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        pointsVbo = ids[0]
        GLES30.glBindVertexArray(pointsVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointsVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, texels.size * 4, floatBuf(texels), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        seeded = false
        available = true
    }

    /** Seeds/advances all particles; call between sim.step and drawing. */
    fun step(
        dt: Float,
        velocityTex: Int,
        aspect: Float,
        flowScale: Float,
    ) {
        val st = state ?: return
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(quadVao)
        if (!seeded) {
            GLES30.glUseProgram(seedProgram)
            GLES30.glUniform1f(loc(seedProgram, "uAspect"), aspect)
            GLES30.glUniform2f(loc(seedProgram, "uInvRes"), 1f / side, 1f / side)
            blit(st.write)
            st.swap()
            seeded = true
        }
        GLES30.glUseProgram(updateProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, st.read.tex)
        GLES30.glUniform1i(loc(updateProgram, "uState"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, velocityTex)
        GLES30.glUniform1i(loc(updateProgram, "uVelocityField"), 1)
        GLES30.glUniform1f(loc(updateProgram, "uAspect"), aspect)
        GLES30.glUniform2f(loc(updateProgram, "uInvRes"), 1f / side, 1f / side)
        GLES30.glUniform1f(loc(updateProgram, "uDt"), dt)
        GLES30.glUniform1f(loc(updateProgram, "uDrag"), drag.coerceIn(0.02f, 1f))
        GLES30.glUniform1f(loc(updateProgram, "uFlowScale"), flowScale)
        blit(st.write)
        st.swap()
        GLES30.glBindVertexArray(0)
    }

    /** Draws additively into the currently bound framebuffer/viewport. */
    fun draw(
        aspect: Float,
        pointScale: Float,
        hueBase: Float,
        hueSpan: Float,
        brightness: Float,
    ) {
        val st = state ?: return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(renderProgram)
        GLES30.glBindVertexArray(pointsVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, st.read.tex)
        GLES30.glUniform1i(loc(renderProgram, "uState"), 0)
        GLES30.glUniform1f(loc(renderProgram, "uAspect"), aspect)
        GLES30.glUniform1f(loc(renderProgram, "uPointScale"), pointScale)
        GLES30.glUniform1f(loc(renderProgram, "uHueBase"), hueBase)
        GLES30.glUniform1f(loc(renderProgram, "uHueSpan"), hueSpan)
        GLES30.glUniform1f(loc(renderProgram, "uBrightness"), brightness)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Positions are seeded in sim space; call when the aspect changes. */
    fun invalidateSeed() {
        seeded = false
    }

    fun release() {
        state?.release()
        state = null
        intArrayOf(updateProgram, seedProgram, renderProgram).forEach { if (it != 0) GLES30.glDeleteProgram(it) }
        updateProgram = 0
        seedProgram = 0
        renderProgram = 0
        uniforms.clear()
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        if (pointsVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(pointsVbo), 0)
        if (pointsVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(pointsVao), 0)
        quadVao = 0
        quadVbo = 0
        pointsVao = 0
        pointsVbo = 0
        seeded = false
        available = false
    }

    private fun blit(target: FluidBuffers.Fbo) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
        GLES30.glViewport(0, 0, target.width, target.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun loc(
        program: Int,
        name: String,
    ): Int = uniforms.getValue(program).getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun floatBuf(data: FloatArray) =
        java.nio.ByteBuffer.allocateDirect(data.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(data).apply { position(0) }

    private fun loadRaw(resId: Int): String = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
}
