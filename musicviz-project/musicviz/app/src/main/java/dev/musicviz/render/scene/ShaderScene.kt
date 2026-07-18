package dev.musicviz.render.scene

import android.opengl.GLES30
import dev.musicviz.analysis.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shadertoy-style fullscreen fragment shader scene.
 *
 * Audio reaches the shader two ways: scalar uniforms (uBass/uMid/uTreble,
 * uEnergy, uBeat) and uAudioTex, a 64x2 texture whose row 0 is the band
 * spectrum and row 1 the waveform - the contract every scene type shares.
 * Customize params arrive as uniforms (see the shader prelude in res/raw).
 *
 * User-supplied fragment source is compiled at runtime; on failure the last
 * working program keeps rendering and the error is reported via [onError].
 */
class ShaderScene(
    override val id: String,
    private val vertexSrc: String,
    initialFragmentSrc: String,
    private val onError: (String?) -> Unit = {},
) : Scene {
    companion object {
        const val AUDIO_TEX_WIDTH: Int = 64
    }

    private var program = 0
    private var vao = 0
    private var audioTex = 0
    private var width = 1
    private var height = 1
    private var pendingFragment: String? = initialFragmentSrc
    private var currentFragment: String = initialFragmentSrc
    private val texData = ByteBuffer.allocateDirect(AUDIO_TEX_WIDTH * 2 * 4).order(ByteOrder.nativeOrder())
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var energy = 0f
    private var beatPulse = 0f
    private var sceneParams: SceneParams = SceneParams.DEFAULT
    private var rotationAngle = 0f
    private var zoomPhase = 0f
    private var cyclePhase = 0f

    override fun setParams(params: SceneParams) {
        sceneParams = params
    }

    /** Thread-safe: queues new fragment source for compilation on the GL thread. */
    @Synchronized
    fun setFragmentSource(src: String) {
        pendingFragment = src
    }

    override fun init() {
        program = 0
        pendingFragment = currentFragment
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenTextures(1, ids, 0)
        audioTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, audioTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, AUDIO_TEX_WIDTH, 2, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, null,
        )
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        rotationAngle += p.rotation * dt
        if (p.endlessZoom) zoomPhase = (zoomPhase + p.endlessZoomSpeed * dt) % 1f
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        bass = (features.bass * p.audioDrive).coerceIn(0f, 1.5f)
        mid = (features.mid * p.audioDrive).coerceIn(0f, 1.5f)
        treble = (features.treble * p.audioDrive).coerceIn(0f, 1.5f)
        energy = (features.rms * p.audioDrive).coerceIn(0f, 1.5f)
        beatPulse = if (features.beat) 1f else (beatPulse - dt * 3f).coerceAtLeast(0f)
        texData.clear()
        val fb = texData.asFloatBuffer()
        for (i in 0 until AUDIO_TEX_WIDTH) {
            fb.put(features.bands[i * features.bands.size / AUDIO_TEX_WIDTH])
        }
        for (i in 0 until AUDIO_TEX_WIDTH) {
            fb.put(features.waveform[i * features.waveform.size / AUDIO_TEX_WIDTH] * 0.5f + 0.5f)
        }
    }

    override fun draw(timeSeconds: Float) {
        compilePendingIfAny()
        if (program == 0) return
        val p = sceneParams
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, audioTex)
        texData.position(0)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, AUDIO_TEX_WIDTH, 2,
            GLES30.GL_RED, GLES30.GL_FLOAT, texData,
        )
        setUniform1f("uTime", timeSeconds * p.speed)
        setUniform1f("uBass", bass)
        setUniform1f("uMid", mid)
        setUniform1f("uTreble", treble)
        setUniform1f("uEnergy", energy)
        setUniform1f("uBeat", beatPulse)
        setUniform1f("uSpeed", p.speed)
        setUniform1f("uZoom", p.zoom)
        setUniform1f("uRotation", rotationAngle)
        setUniform1f("uZoomPhase", zoomPhase)
        setUniform1f("uColorShift", p.colorShift + cyclePhase)
        setUniform1f("uHueRange", p.hueRange)
        setUniform1f("uSat", p.saturation)
        setUniform1f("uBright", p.brightness)
        setUniform1f("uInvert", if (p.invert) 1f else 0f)
        setUniform1f("uIntensity", p.intensity)
        setUniform1f("uMirrorX", if (p.mirror) 1f else 0f)
        setUniform1f("uBeatResponse", p.beatResponse)
        setUniform1f("uTurbulence", p.turbulence)
        setUniform1f("uPalBase", p.paletteBase)
        setUniform1f("uPalRange", p.paletteRange)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAudioTex"), 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun setUniform1f(
        name: String,
        value: Float,
    ) {
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value)
    }

    private fun compilePendingIfAny() {
        val src = synchronized(this) { pendingFragment.also { pendingFragment = null } } ?: return
        try {
            val newProgram = GlUtil.buildProgram(vertexSrc, src)
            if (program != 0) GLES30.glDeleteProgram(program)
            program = newProgram
            currentFragment = src
            onError(null)
        } catch (e: GlUtil.ShaderCompileException) {
            onError(e.message)
        }
    }

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
    }
}
