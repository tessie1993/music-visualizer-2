package dev.musicviz.render.scene

import android.opengl.GLES30
import dev.musicviz.analysis.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shared plumbing for the particle scenes: one static quad, one instance
 * buffer, one instanced draw call, and uniform handling of the Customize
 * params. Subclasses implement [simulate] and fill [vertexData] with raw
 * values (hue as a 0..1 fraction); palette mapping, color cycling, mirroring,
 * density and beat pulse are applied here so every particle scene behaves
 * consistently.
 *
 * Geometry is a velocity-stretched billboard per particle rather than a
 * GL_POINTS sprite - see `particle_vert.glsl` for why - so [simulate] must
 * also publish each particle's velocity in NDC per second. Scenes that have no
 * meaningful velocity can leave it at zero: the quad then stays square and the
 * sprite reads round, exactly as a point sprite did.
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
        /** Per particle: x, y, size (px), hue (0..1), energy (0..1), vx, vy. */
        const val FLOATS_PER_PARTICLE: Int = 7

        /** Offset of the velocity pair inside one particle's record. */
        const val VELOCITY_OFFSET: Int = 5

        /** Triangle-strip corners of the unit billboard, [-1,1]^2. */
        private val QUAD_CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        /**
         * Seconds of travel folded into the streak length: a particle moving
         * 400 px/s stretches to 1.4x. Deliberately small - the streak is a
         * motion cue, not a smear - and hard-capped in the shader.
         */
        private const val STRETCH_SECONDS = 0.0025f

        /**
         * Aura weight at Bloom 0, and how far the Bloom slider lifts it. The
         * composite bloom pass blurs what the scene already drew, so tying the
         * per-sprite glow to the same slider keeps the two moving together
         * instead of fighting.
         */
        private const val GLOW_BASE = 0.85f
        private const val GLOW_PER_BLOOM = 1.2f
    }

    protected val vertexData: FloatArray = FloatArray(count * FLOATS_PER_PARTICLE)
    protected var sceneParams: SceneParams = SceneParams.DEFAULT
        private set

    private var program = 0
    private var instanceVbo = 0
    private var cornerVbo = 0
    private var vao = 0
    private lateinit var buffer: FloatBuffer
    private var rotationAngle = 0f
    private var cyclePhase = 0f
    private var beatPulse = 0f
    private var drawCount = 0
    private val viewport = IntArray(4)

    override fun setParams(params: SceneParams) {
        sceneParams = params
    }

    override fun init() {
        program = GlUtil.buildProgram(shaders.vertex, shaders.fragment).also { uniformLocs.clear() }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        cornerVbo = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        instanceVbo = ids[0]
        buffer = ByteBuffer.allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        GLES30.glBindVertexArray(vao)
        // Location 0: the shared quad, one copy for every instance.
        val corners =
            ByteBuffer
                .allocateDirect(QUAD_CORNERS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(QUAD_CORNERS)
                .also { it.flip() }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_CORNERS.size * 4, corners, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        // Locations 1..5: per-particle state, advanced once per instance.
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexData.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val stride = FLOATS_PER_PARTICLE * 4
        // aPos, aSize, aHue, aEnergy, aVel as `location to components`, packed
        // in that order - ParticleStyleTest reads this list back and compares
        // it against particle_vert.glsl's own attribute declarations.
        val layout = listOf(1 to 2, 2 to 1, 3 to 1, 4 to 1, 5 to 2)
        var offset = 0
        for ((location, components) in layout) {
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, components, GLES30.GL_FLOAT, false, stride, offset)
            GLES30.glVertexAttribDivisor(location, 1)
            offset += components * 4
        }
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
        // Graded: a soft hit nudges the envelope, a hard one snaps it high,
        // and budgeted off-grid transients add texture between beats.
        beatPulse = maxOf(features.motionImpulse, beatPulse - dt * 3f).coerceAtLeast(0f)
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
        val strength = p.flowStrength.coerceIn(0f, 1f)
        val k = strength * dt
        if (k <= 0f) return
        for (i in 0 until count) {
            val o = i * FLOATS_PER_PARTICLE
            val x = vertexData[o]
            val y = vertexData[o + 1]
            grid.sample(x * 0.5f + 0.5f, y * 0.5f + 0.5f, flowSample)
            vertexData[o] = (x + flowSample[0] * k).coerceIn(-1.2f, 1.2f)
            vertexData[o + 1] = (y + flowSample[1] * k).coerceIn(-1.2f, 1.2f)
            // The field moves the sprite, so it has to move the streak too, or
            // flow-advected particles would sit still-looking inside a current.
            vertexData[o + VELOCITY_OFFSET] += flowSample[0] * strength
            vertexData[o + VELOCITY_OFFSET + 1] += flowSample[1] * strength
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
                val src = o - FLOATS_PER_PARTICLE
                vertexData[o] = -vertexData[src]
                vertexData[o + 1] = vertexData[src + 1]
                vertexData[o + 2] = vertexData[src + 2]
                vertexData[o + 3] = vertexData[src + 3]
                vertexData[o + 4] = vertexData[src + 4]
                // Mirrored across x, so the streak has to lean the other way.
                vertexData[o + VELOCITY_OFFSET] = -vertexData[src + VELOCITY_OFFSET]
                vertexData[o + VELOCITY_OFFSET + 1] = vertexData[src + VELOCITY_OFFSET + 1]
            }
        }
    }

    override fun draw(timeSeconds: Float) {
        val p = sceneParams
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val widthPx = viewport[2].coerceAtLeast(1)
        val heightPx = viewport[3].coerceAtLeast(1)
        // Sizes are authored in px against a 1080p-tall target; without this
        // the whole system shrank to specks on a 1440p panel and bloated on a
        // downscaled render target. Same convention as the fluid point layer.
        val dpiScale = (heightPx / 1080f).coerceIn(0.75f, 2.5f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(loc("uViewport"), widthPx.toFloat(), heightPx.toFloat())
        GLES30.glUniform1f(loc("uZoom"), p.zoom * (1f + beatPulse * p.beatResponse * 0.2f))
        GLES30.glUniform1f(loc("uRotation"), rotationAngle)
        GLES30.glUniform1f(loc("uSat"), p.saturation)
        GLES30.glUniform1f(loc("uBright"), p.brightness * p.intensity)
        GLES30.glUniform1f(loc("uContrast"), p.contrast)
        GLES30.glUniform1f(loc("uGamma"), p.gamma)
        GLES30.glUniform1f(loc("uShape"), p.particleShape.toFloat())
        GLES30.glUniform1f(loc("uStretch"), STRETCH_SECONDS)
        GLES30.glUniform1f(loc("uGlow"), GLOW_BASE + p.bloom.coerceIn(0f, 1f) * GLOW_PER_BLOOM)
        GLES30.glUniform1f(loc("uTime"), timeSeconds)
        // Pulse: beat-driven size swell so the parameter works on particles
        // (it previously only affected shader scenes).
        GLES30.glUniform1f(loc("uSize"), p.particleSize * dpiScale * (1f + beatPulse * p.pulse * 0.8f))
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        buffer.clear()
        buffer.put(vertexData)
        buffer.flip()
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, drawCount * FLOATS_PER_PARTICLE * 4, buffer)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, drawCount)
        GLES30.glBindVertexArray(0)
    }

    private val uniformLocs = HashMap<String, Int>()

    private fun loc(name: String): Int = uniformLocs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (instanceVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceVbo), 0)
        if (cornerVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(cornerVbo), 0)
        program = 0
        vao = 0
        instanceVbo = 0
        cornerVbo = 0
    }
}
