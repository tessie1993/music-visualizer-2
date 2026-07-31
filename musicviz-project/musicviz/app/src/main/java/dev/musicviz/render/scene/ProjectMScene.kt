package dev.musicviz.render.scene

import android.opengl.GLES30
import android.os.SystemClock
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.CompositeGrade
import java.io.File

/** A chunk of fresh mono PCM samples; [count] entries of [data] are valid. */
class PcmChunk(
    val data: FloatArray,
    val count: Int,
)

/**
 * MilkDrop-compatible scene backed by libprojectM (v4.1.7 with a backported
 * render-to-FBO API). The engine renders into a scene-owned texture, which is
 * then drawn through a post-processing shader - this is what makes the whole
 * Customize panel (zoom, rotation, mirror, endless zoom, hue/saturation/
 * brightness/contrast/gamma/invert, intensity) work on .milk presets too.
 * Beat response maps to projectM's own beat sensitivity.
 *
 * The same pass is where the Palettes card reaches MilkDrop: see [draw] for
 * why the palette can only ever TINT a .milk preset, never replace its
 * colours.
 *
 * "Audio drive" is deliberately NOT wired here - see [update] for why.
 *
 * Preset loads are debounced on the GL thread; file I/O happens off-thread in
 * the ViewModel before paths reach this class.
 */
class ProjectMScene(
    private val postVertexSrc: String,
    private val postFragmentSrc: String,
    /** Extra shared texture directory (e.g. filesDir/milk/textures). */
    private val sharedTextureDir: String?,
    /** Returns fresh mono PCM since the last call, or null. GL thread only. */
    private val pcmProvider: () -> PcmChunk?,
    private val onError: (String?) -> Unit = {},
) : Scene {
    companion object {
        private const val LOAD_DEBOUNCE_MS = 400L
    }

    override val id: String = SceneIds.MILKDROP

    private var handle: Long = 0
    private var width = 0
    private var height = 0
    private var reportedCreateFailure = false
    private var pmFbo = 0
    private var pmTex = 0
    private var fboWidth = 0
    private var fboHeight = 0
    private var postProgram = 0

    /** Uniform locations cached per program link: glGetUniformLocation every
     *  frame for every uniform is measurable driver overhead on mobile. */
    private val postLocs = HashMap<String, Int>()
    private var postVao = 0
    private var rotationAngle = 0f
    private var zoomPhase = 0f
    private var cyclePhase = 0f
    private var beatPulse = 0f
    private var lastLoadMs = 0L
    private var sceneParams: SceneParams = SceneParams.DEFAULT

    @Volatile
    private var pendingPresetPath: String? = null

    /** Last successfully queued preset; re-applied when the engine is recreated. */
    @Volatile
    private var lastPresetPath: String? = null

    override fun setParams(params: SceneParams) {
        sceneParams = params
    }

    /** Thread-safe: queues a .milk preset file to load on the GL thread. */
    fun queuePreset(path: String) {
        pendingPresetPath = path
    }

    /** Re-queues the currently loaded preset (e.g. after textures change). */
    fun reloadCurrent() {
        lastPresetPath?.let {
            pendingPresetPath = it
            // Bypass the load debounce: a texture change is an explicit user
            // action, and we must re-parse the preset so it re-binds textures.
            lastLoadMs = 0L
        }
    }

    override fun init() {
        release()
        reportedCreateFailure = false
        postProgram = GlUtil.buildProgram(postVertexSrc, postFragmentSrc)
        postLocs.clear()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        postVao = ids[0]
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        if (handle != 0L) PMBridge.nativeResize(handle, width, height) else ensureCreated()
        ensureFbo()
    }

    private fun ensureCreated() {
        if (handle != 0L || width <= 1 || height <= 1) return
        handle = PMBridge.nativeCreate()
        if (handle == 0L) {
            if (!reportedCreateFailure) {
                reportedCreateFailure = true
                onError("projectM engine failed to initialize (adb logcat -s projectM-jni)")
            }
            return
        }
        PMBridge.nativeResize(handle, width, height)
        lastPresetPath?.let { pendingPresetPath = it }
    }

    private fun ensureFbo() {
        if (width <= 1 || height <= 1) return
        if (pmFbo != 0 && fboWidth == width && fboHeight == height) return
        releaseFbo()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        pmTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pmTex)
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
        pmFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pmFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            pmTex,
            0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        fboWidth = width
        fboHeight = height
    }

    /**
     * Advances the Customize transforms and feeds the engine raw PCM.
     *
     * WHY "Audio drive" HAS NO READER HERE (and should not get one): every
     * other style consumes the analysed features, so a master gain on them is
     * meaningful. MilkDrop does not - the ONLY audio that reaches a .milk
     * preset is the mono PCM below, from which libprojectM runs its own FFT
     * and its own beat detector. That detector is ratio-based (instantaneous
     * band energy against its running average), so a constant gain on the
     * samples cancels out of exactly the quantities presets react to, and the
     * one thing it would NOT cancel out of is the waveform many presets draw
     * directly - which would clip against the preset's own scaling. The
     * slider's honest counterpart on this style is projectM's own beat
     * sensitivity, and "Beat response" is already mapped onto it in [draw].
     * Scaling [PcmChunk.data] in place would also corrupt a buffer the tap
     * owns, and copying it every frame would allocate for no visible effect.
     */
    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        rotationAngle += p.rotation * dt
        zoomPhase = if (p.endlessZoom) (zoomPhase + p.endlessZoomSpeed * dt) % 1f else 0f
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        // Graded: a soft hit nudges the envelope, a hard one snaps it high,
        // and budgeted off-grid transients add texture between beats.
        beatPulse = maxOf(features.motionImpulse, beatPulse - dt * 3f).coerceAtLeast(0f)
        if (handle == 0L) return
        val chunk = pcmProvider()
        if (chunk != null && chunk.count > 0) {
            PMBridge.nativeAddPcmMono(handle, chunk.data, chunk.count)
        } else {
            PMBridge.nativeAddPcmMono(handle, features.waveform, features.waveform.size)
        }
    }

    /**
     * Renders the engine into [pmTex] and draws it through the Customize post
     * pass.
     *
     * WHY THE PALETTE ONLY TINTS HERE: every other style GENERATES its colour,
     * so `paletteBase`/`paletteRange` simply decide what it emits. A .milk
     * preset arrives already coloured by its author, so the only honest thing
     * the palette can do is steer those colours (`uPalTint`, a blend that is 0
     * - an exact no-op - until the user asks for it). Replacing them outright
     * would repaint every saved preset and make the whole format read as one
     * look, which is the opposite of what a MilkDrop collection is for. The
     * split of labour matches the fluid family's: this stage owns palette
     * IDENTITY (base hue + span) and runs BEFORE `uHue`, which owns rotation
     * ("Hue shift" + the colour cycle), so one slider unit turns the wheel
     * exactly once.
     */
    override fun draw(timeSeconds: Float) {
        ensureCreated()
        ensureFbo()
        if (handle == 0L || pmFbo == 0) return
        val now = SystemClock.elapsedRealtime()
        pendingPresetPath?.let { path ->
            if (now - lastLoadMs >= LOAD_DEBOUNCE_MS) {
                pendingPresetPath = null
                lastLoadMs = now
                val dir = File(path).parent ?: "/"
                val dirs = mutableListOf(dir, "$dir/textures")
                sharedTextureDir?.let { dirs += it }
                PMBridge.nativeSetTexturePaths(handle, dirs.toTypedArray())
                PMBridge.nativeLoadPreset(handle, path, false)
                val error = PMBridge.nativeGetLastError()
                onError(error)
                if (error == null) lastPresetPath = path
            }
        }
        val p = sceneParams
        PMBridge.nativeSetBeatSensitivity(handle, (0.2f + p.beatResponse).coerceIn(0.2f, 3f))

        // Render projectM into our texture, preserving whatever framebuffer the
        // renderer had bound (the transition pipeline may be targeting an FBO).
        val prevFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevFbo, 0)
        PMBridge.nativeRenderToFbo(handle, pmFbo)
        PMBridge.nativeGetLastError()?.let(onError)
        // The native preset pipeline can leave scissor/masks/blend-equation
        // dirty; re-establish the contract before anything else draws this
        // frame (post pass here, plus any transition co-scene + composite).
        dev.musicviz.render.scene.GlUtil
            .resetFrameState()
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(0, 0, width, height)

        // Post pass: draw the projectM frame through the Customize transforms.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(postProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pmTex)
        setUniform("uTex", 0)
        setUniform1f("uZoom", p.zoom * (1f + beatPulse * p.beatResponse * 0.08f))
        setUniform1f("uRotation", rotationAngle)
        setUniform1f("uZoomPhase", zoomPhase)
        setUniform1f("uMirrorX", if (p.mirror) 1f else 0f)
        // paletteBase/paletteRange resolve a user-made palette from the
        // palette maker transparently (SceneParams' override fields), so the
        // maker works here with no custom-palette branch of its own.
        setUniform1f("uPalBase", p.paletteBase)
        setUniform1f("uPalSpan", CompositeGrade.paletteSpan(p.hueRange, p.paletteRange))
        setUniform1f("uPalTint", CompositeGrade.paletteTintAmount(p.milkdropPaletteTint))
        setUniform1f("uHue", p.colorShift + cyclePhase)
        setUniform1f("uSat", p.saturation)
        setUniform1f("uBright", p.brightness)
        setUniform1f("uContrast", p.contrast)
        setUniform1f("uGamma", p.gamma)
        setUniform1f("uInvert", if (p.invert) 1f else 0f)
        setUniform1f("uIntensity", p.intensity)
        GLES30.glBindVertexArray(postVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    private fun setUniform(
        name: String,
        value: Int,
    ) {
        GLES30.glUniform1i(postLocs.getOrPut(name) { GLES30.glGetUniformLocation(postProgram, name) }, value)
    }

    private fun setUniform1f(
        name: String,
        value: Float,
    ) {
        GLES30.glUniform1f(postLocs.getOrPut(name) { GLES30.glGetUniformLocation(postProgram, name) }, value)
    }

    private fun releaseFbo() {
        if (pmFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(pmFbo), 0)
        if (pmTex != 0) GLES30.glDeleteTextures(1, intArrayOf(pmTex), 0)
        pmFbo = 0
        pmTex = 0
        fboWidth = 0
        fboHeight = 0
    }

    override fun release() {
        if (handle != 0L) {
            PMBridge.nativeDestroy(handle)
            handle = 0
        }
        releaseFbo()
        if (postProgram != 0) GLES30.glDeleteProgram(postProgram)
        postProgram = 0
        if (postVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(postVao), 0)
        postVao = 0
        postLocs.clear()
    }
}
