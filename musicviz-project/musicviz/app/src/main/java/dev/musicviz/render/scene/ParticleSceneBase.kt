package dev.musicviz.render.scene

import android.opengl.GLES30
import dev.musicviz.analysis.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shared plumbing for point-sprite particle scenes: one VBO, one draw call,
 * and uniform handling of the Customize params. Subclasses implement
 * [simulate] and fill [vertexData] with raw values (hue as a 0..1 fraction);
 * palette mapping, color cycling, mirroring, density and beat pulse are
 * applied here so every particle scene behaves consistently.
 */
abstract class ParticleSceneBase(
    override val id: String,
    protected val count: Int,
    private val shaders: ShaderSources,
) : Scene {
    class ShaderSources(
        val vertex: String,
        val fragment: String,
    )

    companion object {
        const val FLOATS_PER_PARTICLE: Int = 5
    }

    protected val vertexData: FloatArray = FloatArray(count * FLOATS_PER_PARTICLE)
    protected var sceneParams: SceneParams = SceneParams.DEFAULT
        private set

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private lateinit var buffer: FloatBuffer
    private var rotationAngle = 0f
    private var cyclePhase = 0f
    private var beatPulse = 0f
    private var drawCount = 0

    override fun setParams(params: SceneParams) {
        sceneParams = params
    }

    override fun init() {
        program = GlUtil.buildProgram(shaders.vertex, shaders.fragment).also { uniformLocs.clear() }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        buffer = ByteBuffer.allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexData.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val stride = FLOATS_PER_PARTICLE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glBindVertexArray(0)
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = Unit

    /** FlowField CPU downsample; when set (and the params opt in), particles
     *  ride the shared fluid velocity field. Written by the renderer on the
     *  GL thread before [update]. */
    internal var flowGrid: dev.musicviz.render.fluid.FlowField.CpuGrid? = null

    private val flowSample = FloatArray(2)

    final override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        rotationAngle += p.rotation * dt
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        beatPulse = if (features.beat) 1f else (beatPulse - dt * 3f).coerceAtLeast(0f)
        simulate(features, dt)
        applyFlowField(p, dt)
        postProcess(p)
    }

    /** F7: advect particle positions through the shared FlowField. */
    private fun applyFlowField(
        p: SceneParams,
        dt: Float,
    ) {
        val grid = flowGrid ?: return
        if (!p.flowEnabled || !p.flowAdvectParticles) return
        val k = p.flowStrength.coerceIn(0f, 1f) * dt
        if (k <= 0f) return
        for (i in 0 until count) {
            val o = i * FLOATS_PER_PARTICLE
            val x = vertexData[o]
            val y = vertexData[o + 1]
            grid.sample(x * 0.5f + 0.5f, y * 0.5f + 0.5f, flowSample)
            vertexData[o] = (x + flowSample[0] * k).coerceIn(-1.2f, 1.2f)
            vertexData[o + 1] = (y + flowSample[1] * k).coerceIn(-1.2f, 1.2f)
        }
    }

    /** Advances the particle simulation and fills [vertexData]. */
    protected abstract fun simulate(
        features: AudioFeatures,
        dt: Float,
    )

    /** Palette/cycle/mirror/density applied uniformly after simulation. */
    private fun postProcess(p: SceneParams) {
        drawCount = (count * p.density).toInt().coerceIn(1, count)
        val hueBase = p.paletteBase + p.colorShift + cyclePhase
        val hueSpan = p.paletteRange * p.hueRange
        for (i in 0 until drawCount) {
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o + 3] = ((hueBase + vertexData[o + 3] * hueSpan) % 1f + 1f) % 1f
            if (p.mirror && i % 2 == 1) {
                vertexData[o] = -vertexData[o - FLOATS_PER_PARTICLE + 0]
                vertexData[o + 1] = vertexData[o - FLOATS_PER_PARTICLE + 1]
                vertexData[o + 2] = vertexData[o - FLOATS_PER_PARTICLE + 2]
                vertexData[o + 3] = vertexData[o - FLOATS_PER_PARTICLE + 3]
                vertexData[o + 4] = vertexData[o - FLOATS_PER_PARTICLE + 4]
            }
        }
    }

    override fun draw(timeSeconds: Float) {
        val p = sceneParams
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(loc("uZoom"), p.zoom * (1f + beatPulse * p.beatResponse * 0.2f))
        GLES30.glUniform1f(loc("uRotation"), rotationAngle)
        GLES30.glUniform1f(loc("uSat"), p.saturation)
        GLES30.glUniform1f(loc("uBright"), p.brightness * p.intensity)
        GLES30.glUniform1f(loc("uContrast"), p.contrast)
        GLES30.glUniform1f(loc("uGamma"), p.gamma)
        GLES30.glUniform1f(loc("uShape"), p.particleShape.toFloat())
        // Pulse: beat-driven size swell so the parameter works on particles
        // (it previously only affected shader scenes).
        GLES30.glUniform1f(loc("uSize"), p.particleSize * (1f + beatPulse * p.pulse * 0.8f))
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        buffer.clear()
        buffer.put(vertexData)
        buffer.flip()
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, drawCount * FLOATS_PER_PARTICLE * 4, buffer)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, drawCount)
        GLES30.glBindVertexArray(0)
    }

    private val uniformLocs = HashMap<String, Int>()

    private fun loc(name: String): Int = uniformLocs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        program = 0
        vao = 0
        vbo = 0
    }
}
