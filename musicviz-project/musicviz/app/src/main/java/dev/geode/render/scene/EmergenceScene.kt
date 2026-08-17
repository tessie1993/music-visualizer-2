package dev.geode.render.scene

import android.opengl.GLES30
import dev.geode.analysis.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class EmergenceScene(
    private val shaders: Shaders,
    private val sim: EmergenceSim = EmergenceSim(),
) : Scene {
    class Shaders(
        val spriteVertex: String,
        val spriteFragment: String,
        val echoVertex: String,
        val echoFragment: String,
    ) {
        companion object {
            fun load(context: android.content.Context): Shaders =
                Shaders(
                    GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.emergence_vert),
                    GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.emergence_frag),
                    GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.emergence_echo_vert),
                    GlUtil.loadShader(context, dev.geode.engine.scenes.R.raw.emergence_echo_frag),
                )
        }
    }

    companion object {
        private val QUAD_CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private const val TWO_PI = (2.0 * Math.PI).toFloat()
        private const val KICK_THRESHOLD = 0.55f
        private const val STRETCH_SCALE = 1.4f
        private const val STRETCH_MAX = 2.6f
    }

    override val id: String = SceneIds.EMERGENCE

    var onShaderError: (String?) -> Unit = {}

    internal var flowGrid: dev.geode.render.fluid.FlowField.CpuGrid? = null
    internal val flowKicks = FlowKicks()
    internal val requiresFlowField: Boolean get() = false

    internal class FlowKicks(
        val capacity: Int = 8,
    ) {
        val x = FloatArray(capacity)
        val y = FloatArray(capacity)
        val vx = FloatArray(capacity)
        val vy = FloatArray(capacity)
        val radius = FloatArray(capacity)
        var size = 0
            private set

        fun clear() {
            size = 0
        }

        fun add(
            x: Float,
            y: Float,
            vx: Float,
            vy: Float,
            radius: Float,
        ) {
            if (size >= capacity) return
            this.x[size] = x
            this.y[size] = y
            this.vx[size] = vx
            this.vy[size] = vy
            this.radius[size] = radius
            size++
        }
    }

    private var sceneParams: SceneParams = SceneParams.DEFAULT
    private var spriteProgram = 0
    private var echoProgram = 0
    private var programOk = false
    private var vao = 0
    private var cornerVbo = 0
    private var instanceVbo = 0
    private var echoVao = 0
    private lateinit var buffer: FloatBuffer
    private var spriteLocs = GlUtil.UniformCache(0)
    private var echoLocs = GlUtil.UniformCache(0)

    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private var fboWidth = 0
    private var fboHeight = 0
    private var readIndex = 0

    private var rotationAngle = 0f
    private var cyclePhase = 0f
    private var drawCount = 0
    private var kicked = false
    private var lastBass = 0f
    private var lastTreble = 0f
    private val centroid = FloatArray(2)
    private val viewport = IntArray(4)
    private val boundFbo = IntArray(1)

    override fun setParams(params: SceneParams) {
        sceneParams = params
        sim.field = params.emergenceField
        sim.swarm = params.emergenceSwarm
        sim.growthMu = params.emergenceGrowth
        sim.speed = params.speed
        sim.audioDrive = params.audioDrive
        sim.beatResponse = params.beatResponse
        sim.turbulence = params.turbulence
        sim.flowStrength = if (params.flowEnabled && params.flowAdvectParticles) params.flowStrength else 0f
    }

    override fun init() {
        spriteProgram = 0
        echoProgram = 0
        vao = 0
        echoVao = 0
        cornerVbo = 0
        instanceVbo = 0
        programOk = false
        fbo.fill(0)
        tex.fill(0)
        fboWidth = 0
        fboHeight = 0
        spriteLocs = GlUtil.UniformCache(0)
        echoLocs = GlUtil.UniformCache(0)
        spriteProgram =
            GlUtil.buildProgramReporting(shaders.spriteVertex, shaders.spriteFragment) {
                onShaderError("\"$id\" unavailable on this GPU: $it")
            }
        if (spriteProgram == 0) return
        echoProgram =
            GlUtil.buildProgramReporting(shaders.echoVertex, shaders.echoFragment) {
                onShaderError("\"$id\" trails unavailable on this GPU: $it")
            }
        programOk = true
        spriteLocs = GlUtil.UniformCache(spriteProgram)
        echoLocs = GlUtil.UniformCache(echoProgram)

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0)
        echoVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        cornerVbo = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        instanceVbo = ids[0]
        buffer =
            ByteBuffer
                .allocateDirect(sim.records.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        GLES30.glBindVertexArray(vao)
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
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sim.records.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val stride = EmergenceSim.FLOATS_PER_PARTICLE * 4
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

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        rotationAngle = (rotationAngle + p.rotation * dt) % TWO_PI
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        lastBass = (features.bass * p.audioDrive).coerceIn(0f, 1.5f)
        lastTreble = (features.treble * p.audioDrive).coerceIn(0f, 1.5f)
        sim.flowGrid = flowGrid
        sim.step(features, dt)
        publishKicks(features)
        applyPalette(p)
    }

    private fun publishKicks(features: AudioFeatures) {
        val strong = features.motionImpulse * sceneParams.beatResponse > KICK_THRESHOLD
        if (!strong) {
            kicked = false
            return
        }
        if (kicked) return
        kicked = true
        sim.centroid(centroid)
        flowKicks.add(centroid[0], centroid[1], 0f, 0f, sim.lastRadius * 3f)
    }

    private fun applyPalette(p: SceneParams) {
        drawCount = (sim.count * p.density).toInt().coerceIn(1, sim.count)
        val records = sim.records
        val hueBase = p.paletteBase + p.colorShift + cyclePhase
        val hueSpan = p.paletteRange * p.hueRange
        for (i in 0 until drawCount) {
            val o = i * EmergenceSim.FLOATS_PER_PARTICLE
            records[o + 3] = ((hueBase + records[o + 3] * hueSpan) % 1f + 1f) % 1f
        }
    }

    override fun draw(timeSeconds: Float) {
        if (!programOk) return
        val p = sceneParams
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, boundFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val widthPx = viewport[2].coerceAtLeast(1)
        val heightPx = viewport[3].coerceAtLeast(1)

        if (!p.trails || echoProgram == 0) {
            drawSprites(p, timeSeconds, widthPx, heightPx)
            return
        }
        ensureEcho(widthPx, heightPx)
        if (fbo[0] == 0) {
            drawSprites(p, timeSeconds, widthPx, heightPx)
        } else {
            drawWithEcho(p, timeSeconds)
        }
    }

    private fun drawWithEcho(
        p: SceneParams,
        timeSeconds: Float,
    ) {
        val write = 1 - readIndex
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[write])
        GLES30.glViewport(0, 0, fboWidth, fboHeight)
        drawEcho(
            source = tex[readIndex],
            decay = 0.82f + 0.155f * p.trailLength.coerceIn(0f, 1f),
            warp = p.emergenceAcid,
            beat = sim.beatEnvelope(),
        )
        drawSprites(p, timeSeconds, fboWidth, fboHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, boundFbo[0])
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        drawEcho(source = tex[write], decay = 1f, warp = 0f, beat = 0f)
        readIndex = write
    }

    private fun drawEcho(
        source: Int,
        decay: Float,
        warp: Float,
        beat: Float,
    ) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(echoProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glUniform1i(echoLocs.loc("uPrev"), 0)
        GLES30.glUniform1f(echoLocs.loc("uDecay"), decay.coerceIn(0f, 1f))
        val p = sceneParams
        GLES30.glUniform1f(
            echoLocs.loc("uZoomWarp"),
            warp * (0.012f + beat * 0.02f) + p.trailZoom * 0.02f * decayGate(decay),
        )
        GLES30.glUniform1f(
            echoLocs.loc("uRotWarp"),
            warp * (0.01f + lastTreble * 0.03f) + p.trailWarp * 0.02f * decayGate(decay),
        )
        GLES30.glUniform1f(echoLocs.loc("uHueRot"), warp * (0.15f + lastTreble * 0.5f))
        GLES30.glUniform1f(echoLocs.loc("uChroma"), warp * 0.35f)
        GLES30.glBindVertexArray(echoVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun decayGate(decay: Float): Float = if (decay >= 1f) 0f else 1f

    private fun drawSprites(
        p: SceneParams,
        timeSeconds: Float,
        widthPx: Int,
        heightPx: Int,
    ) {
        val dpiScale = ParticleLook.dpiScale(heightPx)
        val beat = sim.beatEnvelope()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(spriteProgram)
        GLES30.glUniform2f(spriteLocs.loc("uViewport"), widthPx.toFloat(), heightPx.toFloat())
        GLES30.glUniform1f(spriteLocs.loc("uZoom"), p.zoom * (1f + beat * p.beatResponse * 0.2f))
        GLES30.glUniform1f(spriteLocs.loc("uRotation"), rotationAngle)
        GLES30.glUniform1f(spriteLocs.loc("uSat"), p.saturation)
        GLES30.glUniform1f(spriteLocs.loc("uBright"), p.brightness * p.intensity)
        GLES30.glUniform1f(spriteLocs.loc("uContrast"), p.contrast)
        GLES30.glUniform1f(spriteLocs.loc("uGamma"), p.gamma)
        GLES30.glUniform1f(spriteLocs.loc("uShape"), p.particleShape.toFloat())
        GLES30.glUniform1f(spriteLocs.loc("uStretch"), ParticleLook.STRETCH_SECONDS * STRETCH_SCALE)
        GLES30.glUniform1f(spriteLocs.loc("uStretchMax"), STRETCH_MAX)
        GLES30.glUniform1f(spriteLocs.loc("uGlow"), ParticleLook.glow(p.bloom))
        GLES30.glUniform1f(spriteLocs.loc("uTime"), timeSeconds)
        GLES30.glUniform1f(
            spriteLocs.loc("uSize"),
            p.particleSize * dpiScale * (1f + beat * p.pulse * 0.8f),
        )
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        buffer.clear()
        buffer.put(sim.records, 0, drawCount * EmergenceSim.FLOATS_PER_PARTICLE)
        buffer.flip()
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            drawCount * EmergenceSim.FLOATS_PER_PARTICLE * 4,
            buffer,
        )
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, drawCount)
        GLES30.glBindVertexArray(0)
    }

    private fun ensureEcho(
        width: Int,
        height: Int,
    ) {
        if (fbo[0] != 0 && fboWidth == width && fboHeight == height) return
        releaseEcho()
        val ids = IntArray(1)
        for (i in 0..1) {
            GLES30.glGenTextures(1, ids, 0)
            tex[i] = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null,
            )
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo[i] = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                tex[i],
                0,
            )
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, boundFbo[0])
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            releaseEcho()
            return
        }
        fboWidth = width
        fboHeight = height
        readIndex = 0
    }

    private fun releaseEcho() {
        for (i in 0..1) {
            if (fbo[i] != 0) GLES30.glDeleteFramebuffers(1, fbo, i)
            if (tex[i] != 0) GLES30.glDeleteTextures(1, tex, i)
            fbo[i] = 0
            tex[i] = 0
        }
        fboWidth = 0
        fboHeight = 0
    }

    override fun release() {
        if (spriteProgram != 0) GLES30.glDeleteProgram(spriteProgram)
        if (echoProgram != 0) GLES30.glDeleteProgram(echoProgram)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (echoVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(echoVao), 0)
        if (instanceVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceVbo), 0)
        if (cornerVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(cornerVbo), 0)
        releaseEcho()
        spriteProgram = 0
        echoProgram = 0
        vao = 0
        echoVao = 0
        instanceVbo = 0
        cornerVbo = 0
        programOk = false
        spriteLocs = GlUtil.UniformCache(0)
        echoLocs = GlUtil.UniformCache(0)
    }

    internal fun records(): FloatArray = sim.records
}
