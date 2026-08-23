package dev.geode.render.scene

import android.opengl.GLES30
import dev.geode.analysis.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShaderScene(
    override val id: String,
    private val vertexSrc: String,
    initialFragmentSrc: String,
    private val onError: (String?) -> Unit = {},
    private val onUserSourceCompiled: (String) -> Unit = {},
) : Scene,
    PcmSink {
    companion object {
        const val AUDIO_TEX_WIDTH: Int = 512

        private const val TIME_WRAP_SECONDS = 7100f

        private const val TWO_PI = (2.0 * Math.PI).toFloat()
    }

    private var program = 0
    private var vao = 0
    private var audioTex = 0
    private var width = 1
    private var height = 1
    private var pendingFragment: String? = initialFragmentSrc
    private var currentFragment: String = initialFragmentSrc

    private var pendingIsUserSource: Boolean = false
    private val texData = ByteBuffer.allocateDirect(AUDIO_TEX_WIDTH * 2 * 4).order(ByteOrder.nativeOrder())

    private val texFloats = texData.asFloatBuffer()
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var energy = 0f
    private var beatPulse = 0f
    private var beatPhase = 0f
    private var sceneParams: SceneParams = SceneParams.DEFAULT
    private var rotationAngle = 0f
    private var zoomPhase = 0f
    private var cyclePhase = 0f

    private var shaderTime = 0f

    private val pcm = FloatArray(AUDIO_TEX_WIDTH * 8)
    private var pcmCount = 0
    private val waveRow = FloatArray(AUDIO_TEX_WIDTH)

    override fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) {
        val n = count.coerceAtMost(pcm.size)
        if (n <= 0) return
        System.arraycopy(samples, count - n, pcm, 0, n)
        pcmCount = n
    }

    override fun setParams(params: SceneParams) {
        sceneParams = params
    }

    private var flowTex = 0
    private var flowStrength = 0f

    private var paletteLutTex = 0

    fun setPaletteLut(tex: Int) {
        paletteLutTex = tex
    }

    fun setFlow(
        tex: Int,
        strength: Float,
    ) {
        flowTex = tex
        flowStrength = strength
    }

    @Synchronized
    fun setFragmentSource(src: String) {
        pendingFragment = src
        pendingIsUserSource = true
    }

    override fun init() {
        program = 0
        uniformLocs = GlUtil.UniformCache(0)
        pendingFragment = currentFragment
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenTextures(1, ids, 0)
        audioTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, audioTex)
        val floatLinear =
            (GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: "").contains("OES_texture_float_linear")
        val audioFilter = if (floatLinear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, audioFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, audioFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R32F,
            AUDIO_TEX_WIDTH,
            2,
            0,
            GLES30.GL_RED,
            GLES30.GL_FLOAT,
            null,
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
        shaderTime = (shaderTime + p.speed * dt) % TIME_WRAP_SECONDS
        rotationAngle = (rotationAngle + p.rotation * dt) % TWO_PI
        zoomPhase = if (p.endlessZoom) (zoomPhase + p.endlessZoomSpeed * dt) % 1f else 0f
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        bass = (features.bass * p.audioDrive).coerceIn(0f, 1.5f)
        mid = (features.mid * p.audioDrive).coerceIn(0f, 1.5f)
        treble = (features.treble * p.audioDrive).coerceIn(0f, 1.5f)
        energy = (features.rms * p.audioDrive).coerceIn(0f, 1.5f)
        beatPulse = maxOf(features.motionImpulse, beatPulse - dt * 3f).coerceAtLeast(0f)
        val bpm = features.bpm
        if (bpm > 40f) {
            beatPhase = (beatPhase + dt * bpm / 60f) % 1f
            if (features.beat) {
                beatPhase = if (beatPhase > 0.5f) beatPhase * 0.5f + 0.5f else beatPhase * 0.5f
                if (beatPhase >= 0.999f) beatPhase = 0f
            }
        } else {
            beatPhase = (beatPhase + dt) % 1f
        }
        val drive = p.audioDrive
        texFloats.clear()
        for (i in 0 until AUDIO_TEX_WIDTH) {
            texFloats.put((features.bands[i * features.bands.size / AUDIO_TEX_WIDTH] * drive).coerceIn(0f, 1.5f))
        }
        if (pcmCount > 0) {
            PcmRow.fill(waveRow, pcm, pcmCount)
            pcmCount = 0
        } else {
            PcmRow.fill(waveRow, features.waveform, features.waveform.size)
        }
        for (i in 0 until AUDIO_TEX_WIDTH) {
            texFloats.put((waveRow[i] * drive * 0.5f + 0.5f).coerceIn(0f, 1f))
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
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            AUDIO_TEX_WIDTH,
            2,
            GLES30.GL_RED,
            GLES30.GL_FLOAT,
            texData,
        )
        setUniform1f("uTime", shaderTime)
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
        setUniform1f("uPal2Base", p.palette2Base)
        setUniform1f("uPal2Range", p.palette2Range)
        setUniform1f("uPaletteMix", p.paletteMix)
        setUniform1f("uDuotone", if (p.duotone) 1f else 0f)
        setUniform1f("uBloom", p.bloom)
        setUniform1f("uWarp", p.warp)
        setUniform1f("uRipple", p.ripple)
        setUniform1f("uSymmetry", p.symmetry.toFloat())
        setUniform1f("uKaleido", if (p.kaleidoscope) 1f else 0f)
        setUniform1f("uMorph", p.morph)
        setUniform1f("uPixelate", p.pixelate)
        setUniform1f("uPosterize", p.posterize)
        setUniform1f("uSway", p.sway)
        setUniform1f("uPulse", p.pulse)
        setUniform1f("uBeatPhase", beatPhase)
        setUniform1f("uDriftX", p.driftX)
        setUniform1f("uDriftY", p.driftY)
        setUniform1f("uShake", p.shake)
        setUniform1f("uTile", p.tile)
        setUniform1f("uTwist", p.twist)
        setUniform1f("uTemperature", p.temperature)
        setUniform1f("uSolarize", if (p.solarize) 1f else 0f)
        setUniform1f("uFlash", p.flash)
        setUniform1f("uContrast", p.contrast)
        setUniform1f("uGamma", p.gamma)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uResolution"), width.toFloat(), height.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAudioTex"), 0)
        if (flowTex != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flowTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFlow"), 1)
            setUniform1f("uFlowStrength", flowStrength)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        val lutSelected = p.paletteLut >= 0 && paletteLutTex != 0
        if (paletteLutTex != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paletteLutTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uPalLut"), 2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        setUniform1f("uPalLutMix", if (lutSelected) 1f else 0f)
        setUniform1f("uPalLutRow", dev.geode.render.CyclicPalettes.rowCoordinate(p.paletteLut.coerceAtLeast(0)))
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private var uniformLocs = GlUtil.UniformCache(0)

    private fun setUniform1f(
        name: String,
        value: Float,
    ) {
        GLES30.glUniform1f(uniformLocs.loc(name), value)
    }

    private fun compilePendingIfAny() {
        val pending =
            synchronized(this) {
                val queued = pendingFragment
                val fromUser = pendingIsUserSource
                pendingFragment = null
                pendingIsUserSource = false
                queued?.let { it to fromUser }
            } ?: return
        val (src, fromUser) = pending
        val newProgram = GlUtil.buildProgramReporting(vertexSrc, src, onError)
        if (newProgram == 0) return
        if (program != 0) GLES30.glDeleteProgram(program)
        program = newProgram
        uniformLocs = GlUtil.UniformCache(newProgram)
        currentFragment = src
        onError(null)
        if (fromUser) onUserSourceCompiled(src)
    }

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (audioTex != 0) GLES30.glDeleteTextures(1, intArrayOf(audioTex), 0)
        program = 0
        vao = 0
        audioTex = 0
    }
}
