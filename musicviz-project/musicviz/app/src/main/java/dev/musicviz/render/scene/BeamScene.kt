package dev.musicviz.render.scene

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidHue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * The BEAM style: an oscilloscope trace drawn the way a real one is made.
 *
 * The app already had two scope-ish scenes and neither is a scope. `scope` is
 * an `exp()` falloff around a line; `liss` searches 64 taps per pixel for the
 * nearest point of a curve built from ONE waveform plotted against a
 * phase-shifted copy of itself. Both draw a curve of even brightness, which is
 * the one thing a scope trace never is.
 *
 * This draws the beam instead: one quad per waveform segment, with the fragment
 * stage integrating a Gaussian beam along it analytically (`beam_frag.glsl`,
 * ported from woscope). Because a CRT beam deposits energy per unit TIME, the
 * integral is divided by the distance travelled - so the trace brightens
 * exactly where the signal slows and turns, and dims through fast sweeps. That
 * relationship is the whole look, and no distance falloff reproduces it.
 *
 * It is also cheaper than what it sits beside: cost is O(segments) geometry
 * rather than a 64-iteration search at every pixel of the screen.
 *
 * ### Two readings of the same samples
 *
 * - **Sweep** (`cymaticsGeometry`-style toggle, `beamXy = false`): time along
 *   x, amplitude up - the classic waveform.
 * - **XY** (`beamXy = true`): the sample against another a quarter cycle later,
 *   which draws the Lissajous figure of the signal against its own quadrature.
 *   A true XY scope wants two CHANNELS, and the audio path is mono end to end
 *   (`PcmRingBuffer` downmixes at ingest), so this is honest about being a
 *   phase plot rather than pretending to be stereo. Stereo would mean threading
 *   a second channel through the tap, the ring buffer, the offline analyzer and
 *   the exporter - worth doing, but not hidden inside a scene.
 */
internal class BeamScene(
    private val context: Context,
) : Scene {
    override val id: String = SceneIds.BEAM

    private companion object {
        /** Waveform samples uploaded per frame; segments = this - 1. */
        const val SAMPLES = 512

        /** Beam width at "Particle size" 1, in normalized units. */
        const val BASE_SIGMA = 0.006f

        /** How far ahead the XY mode reads for its second axis, in samples. */
        const val QUADRATURE = SAMPLES / 4

        /** Trace gain at "Audio drive" 1; the analyzer's waveform is -1..1. */
        const val BASE_GAIN = 0.8f
    }

    private var program = 0
    private var uniforms = GlUtil.UniformCache(0)
    private var programOk = false
    private var vao = 0
    private var waveTex = 0

    private var params = SceneParams.DEFAULT
    private var width = 1
    private var height = 1

    /** Interleaved sample store, resampled from whatever the analyzer sends. */
    private val samples = FloatArray(SAMPLES)
    private val upload = ByteBuffer.allocateDirect(SAMPLES * 4).order(ByteOrder.nativeOrder())

    /**
     * Typed view of [upload], made once instead of once per frame:
     * `asFloatBuffer()` allocates a fresh DirectFloatBufferU on every call and
     * this ran in [draw]. Safe to keep because the view is created while
     * [upload] is at position 0 (so it spans the whole buffer), [upload] is
     * never re-allocated, and only the GL thread touches either of them.
     */
    private val uploadFloats = upload.asFloatBuffer()

    /** Scratch for the one HSV->RGB conversion per frame; see [draw]. */
    private val beamRgb = FloatArray(3)

    /** Smoothed peak, so a quiet passage still fills the screen sensibly. */
    private var autoGain = 1f

    private var beatPulse = 0f

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        program = 0
        vao = 0
        waveTex = 0
        uniforms = GlUtil.UniformCache(0)
        programOk = false
        program =
            GlUtil.buildProgramReporting(
                GlUtil.loadShader(context, R.raw.beam_vert),
                GlUtil.loadShader(context, R.raw.beam_frag),
            ) { onShaderError("Beam unavailable on this GPU: $it") }
        if (program == 0) return
        programOk = true
        uniforms = GlUtil.UniformCache(program)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenTextures(1, ids, 0)
        waveTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, waveTex)
        // texelFetch only, so filtering never applies - but an incomplete
        // texture samples as zero, and NEAREST is what makes it complete.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, SAMPLES, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
    }

    override fun setParams(params: SceneParams) {
        this.params = params
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = max(width, 1)
        this.height = max(height, 1)
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        val wave = features.waveform
        // Resample whatever length the analyzer produced onto the beam's own
        // sample count: more segments than samples would draw a polygon, fewer
        // would throw away detail the waveform actually carries.
        if (wave.isEmpty()) {
            samples.fill(0f)
        } else {
            var peak = 0f
            for (i in samples.indices) {
                val raw = wave[i * wave.size / samples.size]
                // Scrubbed at ingest, the fluid pipeline's isnan hygiene:
                // max() propagates NaN, so one non-finite sample from an
                // upstream glitch would poison peak - stalling the auto-gain
                // - and land in the waveform texture as garbage beam
                // geometry. A bad sample reads as silence.
                val v = if (raw.isFinite()) raw else 0f
                samples[i] = v
                peak = max(peak, abs(v))
            }
            // Auto-gain: a scope with no vertical control is unreadable on
            // quiet material and clipped on loud. Rises slowly, falls slower,
            // and never amplifies silence into noise.
            val target = if (peak > 0.02f) (0.85f / peak).coerceIn(0.5f, 6f) else autoGain
            autoGain += (target - autoGain) * (if (target < autoGain) 0.06f else 0.02f)
        }
        beatPulse = max(features.motionImpulse, beatPulse - dt * 3f).coerceIn(0f, 1.5f)
    }

    override fun draw(timeSeconds: Float) {
        if (!programOk) return
        GlUtil.resetFrameState()
        val p = params

        uploadFloats.clear()
        uploadFloats.put(samples)
        upload.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, waveTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, SAMPLES, 1, GLES30.GL_RED, GLES30.GL_FLOAT, upload)

        GLES30.glUseProgram(program)
        GLES30.glUniform1i(loc("uWave"), 0)
        GLES30.glUniform1i(loc("uCount"), SAMPLES - 1)
        GLES30.glUniform1f(loc("uMode"), if (p.beamXy) 1f else 0f)
        GLES30.glUniform1i(loc("uPhaseOffset"), QUADRATURE)
        GLES30.glUniform1f(loc("uAspect"), width.toFloat() / height.toFloat())
        GLES30.glUniform1f(loc("uSigma"), BASE_SIGMA * p.beamWidth.coerceIn(0.2f, 4f))
        GLES30.glUniform1f(loc("uGain"), BASE_GAIN * p.audioDrive.coerceIn(0f, 4f) * autoGain)
        GLES30.glUniform1f(loc("uTail"), p.beamTail.coerceIn(0f, 1f))
        GLES30.glUniform1f(
            loc("uIntensity"),
            p.beamIntensity.coerceIn(0f, 3f) * (1f + beatPulse * p.beatResponse.coerceIn(0f, 2f) * 0.4f),
        )
        // Out-param form: the Triple the pure [FluidHue.rgb] returns boxes all
        // three floats, once per frame, for a value read immediately here.
        FluidHue.rgb(FluidHue.base(p.paletteBase), 1f, beamRgb)
        GLES30.glUniform3f(loc("uColor"), beamRgb[0], beamRgb[1], beamRgb[2])

        // Additive, like light landing on phosphor: overlapping passes of the
        // beam sum instead of replacing, which is what makes a dense turning
        // point read as brighter than a single crossing.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, (SAMPLES - 1) * 6)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun loc(name: String): Int = uniforms.loc(name)

    override fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (waveTex != 0) GLES30.glDeleteTextures(1, intArrayOf(waveTex), 0)
        program = 0
        vao = 0
        waveTex = 0
        programOk = false
        uniforms = GlUtil.UniformCache(0)
    }
}
