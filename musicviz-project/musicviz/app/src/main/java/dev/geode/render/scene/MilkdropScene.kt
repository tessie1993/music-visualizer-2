package dev.geode.render.scene

import android.opengl.GLES30
import android.os.SystemClock
import dev.geode.analysis.AudioFeatures
import dev.geode.render.CompositeGrade
import java.io.File

/**
 * MilkDrop-compatible scene backed by STOCK libprojectM v4.1.7 — no engine
 * patches. Upstream ends every frame on the DEFAULT framebuffer (the only
 * target where its `glDrawBuffers(GL_BACK)` is legal), so this scene lets it:
 * the engine renders onto framebuffer 0 at the real surface size, the frame
 * is copied off with `glCopyTexSubImage2D` into a scene-owned texture, and
 * the texture is drawn through the post-processing shader into whatever
 * framebuffer the renderer had bound. The mid-frame scribble on the window's
 * back buffer is invisible: the composite pass repaints the whole surface
 * before it is ever swapped.
 *
 * The previous integration instead backported a render-to-FBO API onto the
 * engine, and the patch went stale twice — once linking against an undefined
 * symbol, once leaving `GL_BACK` set on a framebuffer object — each time
 * shipping a permanently black MilkDrop. A copy off framebuffer 0 costs one
 * screen-size blit and has no patch to go stale.
 *
 * The post pass is what makes the whole Customize panel (zoom, rotation,
 * mirror, endless zoom, hue/saturation/brightness/contrast/gamma/invert,
 * intensity) work on .milk presets too. Beat response maps to projectM's own
 * beat sensitivity. The same pass is where the Palettes card reaches
 * MilkDrop: see [draw] for why the palette can only ever TINT a .milk
 * preset, never replace its colours.
 *
 * "Audio drive" is deliberately NOT wired here — see [update] for why.
 *
 * Preset loads are debounced on the GL thread; file I/O happens off-thread
 * in the ViewModel before paths reach this class.
 */
class MilkdropScene(
    private val postVertexSrc: String,
    private val postFragmentSrc: String,
    /** Extra shared texture directory (e.g. filesDir/milk/textures). */
    private val sharedTextureDir: String?,
    private val onError: (String?) -> Unit = {},
    /**
     * Fires on the GL thread with the path of a preset the engine actually
     * accepted. The ViewModel records the active .milk from here rather than
     * at pick time, because a preset that failed to parse used to be noted as
     * active anyway — and then copied verbatim into every preset the user
     * saved, persisting the broken file forever.
     */
    private val onPresetLoaded: (String) -> Unit = {},
) : Scene,
    PcmSink {
    companion object {
        private const val LOAD_DEBOUNCE_MS = 400L

        /** Frames the black-frame diagnostic watches before giving a verdict. */
        private const val DIAG_FRAMES = 90

        /** Early frames it ignores while the engine fades its preset in. */
        private const val DIAG_WARMUP = 20

        /** RGB byte offsets of the two probe pixels in [diagPixels]. */
        private val PROBE_BYTES = intArrayOf(0, 1, 2, 4, 5, 6)

        /** Enough for two frames of 48 kHz audio; overflow keeps the newest. */
        private const val PCM_CAPACITY = 8192

        /** [rotationAngle] wrap; pm_post_frag only reads it through cos/sin. */
        private const val TWO_PI = (2.0 * Math.PI).toFloat()
    }

    override val id: String = SceneIds.MILKDROP

    private val pcmBuffer = FloatArray(PCM_CAPACITY)
    private var pcmCount = 0

    override fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) {
        val n = count.coerceAtMost(PCM_CAPACITY)
        if (n <= 0) return
        if (pcmCount + n > PCM_CAPACITY) pcmCount = 0
        System.arraycopy(samples, count - n, pcmBuffer, pcmCount, n)
        pcmCount += n
    }

    private var handle: Long = 0

    /** Output size: the framebuffer the renderer hands [draw] (supersampled
     *  live, export-sized on the encoder surface). From [resize]. */
    private var width = 0
    private var height = 0

    /** The DEFAULT framebuffer's real size — the engine renders at this and
     *  the copy reads exactly this many pixels off framebuffer 0. Set by the
     *  renderer via [setWindowSize]; 0 until then, falling back to the
     *  [resize] size, which is correct wherever the two coincide (export
     *  renders scenes at the encoder surface's own size). */
    private var windowWidth = 0
    private var windowHeight = 0

    private var reportedCreateFailure = false
    private var frameTex = 0
    private var texWidth = 0
    private var texHeight = 0
    private var engineWidth = 0
    private var engineHeight = 0
    private var postProgram = 0
    private var postProgramOk = false

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
    private var diagFrames = 0
    private var diagDone = false

    /** Two RGBA probe pixels for [diagnoseBlackFrame]; allocated once. */
    private val diagPixels: java.nio.ByteBuffer =
        java.nio.ByteBuffer
            .allocateDirect(8)
            .order(java.nio.ByteOrder.nativeOrder())

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

    /**
     * The surface's true pixel size, i.e. framebuffer 0's. The live renderer
     * calls this from onSurfaceChanged, because what [resize] receives there
     * is the SUPERSAMPLED scene-FBO size — larger than the window, and pixels
     * outside the window do not exist on framebuffer 0 to be copied.
     */
    fun setWindowSize(
        width: Int,
        height: Int,
    ) {
        windowWidth = width
        windowHeight = height
    }

    override fun init() {
        release()
        reportedCreateFailure = false
        diagFrames = 0
        diagDone = false
        // A driver-rejected shader must degrade the style to "unavailable",
        // never crash the GL thread: this runs while every scene is built, so
        // throwing here would take the whole visualizer down before the user
        // has even chosen MilkDrop.
        try {
            postProgram = GlUtil.buildProgram(postVertexSrc, postFragmentSrc)
            postProgramOk = true
        } catch (e: GlUtil.ShaderCompileException) {
            // Silent black is the worst failure mode: say why instead.
            onError("MilkDrop unavailable on this GPU: ${e.message}")
            return
        }
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
    }

    private fun effectiveWindowWidth(): Int = if (windowWidth > 1) windowWidth else width

    private fun effectiveWindowHeight(): Int = if (windowHeight > 1) windowHeight else height

    private fun ensureEngine() {
        val w = effectiveWindowWidth()
        val h = effectiveWindowHeight()
        if (w <= 1 || h <= 1) return
        if (handle == 0L) {
            handle = MilkdropEngine.nativeCreate()
            if (handle == 0L) {
                if (!reportedCreateFailure) {
                    reportedCreateFailure = true
                    onError("projectM engine failed to initialize (adb logcat -s milkdrop-jni)")
                }
                return
            }
            lastPresetPath?.let { pendingPresetPath = it }
            engineWidth = 0
        }
        if (engineWidth != w || engineHeight != h) {
            MilkdropEngine.nativeResize(handle, w, h)
            engineWidth = w
            engineHeight = h
        }
    }

    /** The texture the frame is copied into: plain RGBA8, engine-sized. */
    private fun ensureFrameTexture() {
        val w = engineWidth
        val h = engineHeight
        if (w <= 1 || h <= 1) return
        if (frameTex != 0 && texWidth == w && texHeight == h) return
        releaseFrameTexture()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        frameTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            w,
            h,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        texWidth = w
        texHeight = h
    }

    /**
     * Advances the Customize transforms and feeds the engine raw PCM.
     *
     * WHY "Audio drive" HAS NO READER HERE (and should not get one): every
     * other style consumes the analysed features, so a master gain on them is
     * meaningful. MilkDrop does not — the ONLY audio that reaches a .milk
     * preset is the mono PCM below, from which libprojectM runs its own FFT
     * and its own beat detector. That detector is ratio-based (instantaneous
     * band energy against its running average), so a constant gain on the
     * samples cancels out of exactly the quantities presets react to, and the
     * one thing it would NOT cancel out of is the waveform many presets draw
     * directly — which would clip against the preset's own scaling. The
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
        // Wrapped to one turn: pm_post_frag only reads uRotation through its
        // cos/sin mat2, so 2pi is an exact period, and an unwrapped `+= dt`
        // on a days-long wallpaper is float mush (TIME_WRAP convention).
        rotationAngle = (rotationAngle + p.rotation * dt) % TWO_PI
        zoomPhase = if (p.endlessZoom) (zoomPhase + p.endlessZoomSpeed * dt) % 1f else 0f
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        // Graded: a soft hit nudges the envelope, a hard one snaps it high,
        // and budgeted off-grid transients add texture between beats.
        beatPulse = maxOf(features.motionImpulse, beatPulse - dt * 3f).coerceAtLeast(0f)
        if (handle == 0L) return
        if (pcmCount > 0) {
            MilkdropEngine.nativeAddPcmMono(handle, pcmBuffer, pcmCount)
            pcmCount = 0
        } else {
            MilkdropEngine.nativeAddPcmMono(handle, features.waveform, features.waveform.size)
        }
    }

    /**
     * Renders the engine onto framebuffer 0, copies the frame into
     * [frameTex], and draws it through the Customize post pass.
     *
     * WHY THE PALETTE ONLY TINTS HERE: every other style GENERATES its
     * colour, so `paletteBase`/`paletteRange` simply decide what it emits. A
     * .milk preset arrives already coloured by its author, so the only honest
     * thing the palette can do is steer those colours (`uPalTint`, a blend
     * that is 0 — an exact no-op — until the user asks for it). Replacing
     * them outright would repaint every saved preset and make the whole
     * format read as one look, which is the opposite of what a MilkDrop
     * collection is for. The split of labour matches the fluid family's:
     * this stage owns palette IDENTITY (base hue + span) and runs BEFORE
     * `uHue`, which owns rotation ("Hue shift" + the colour cycle), so one
     * slider unit turns the wheel exactly once.
     */
    override fun draw(timeSeconds: Float) {
        // The post pass below is the ONLY path the engine's frame takes to
        // the screen, so without it there is nothing to show: skip the native
        // render too rather than pay for a frame that cannot be composited.
        if (!postProgramOk) return
        // The renderer's target, captured BEFORE anything below can bind:
        // the engine ends its frame on framebuffer 0 and the post pass must
        // land back on whatever the renderer was filling (its scene FBO, or
        // a transition target).
        val prevFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevFbo, 0)
        ensureEngine()
        ensureFrameTexture()
        if (handle == 0L || frameTex == 0) return
        val now = SystemClock.elapsedRealtime()
        pendingPresetPath?.let { path ->
            if (now - lastLoadMs >= LOAD_DEBOUNCE_MS) {
                pendingPresetPath = null
                lastLoadMs = now
                val dir = File(path).parent ?: "/"
                val dirs = mutableListOf(dir, "$dir/textures")
                sharedTextureDir?.let { dirs += it }
                MilkdropEngine.nativeSetTexturePaths(handle, dirs.toTypedArray())
                // The soft-cut flag libprojectM has always accepted and this
                // call has always passed as false. Its duration is configured
                // natively at create time.
                MilkdropEngine.nativeLoadPreset(handle, path, sceneParams.milkdropBlendPresets)
                val error = MilkdropEngine.nativeGetLastError()
                onError(error)
                if (error == null) {
                    lastPresetPath = path
                    onPresetLoaded(path)
                }
            }
        }
        val p = sceneParams
        MilkdropEngine.nativeSetBeatSensitivity(handle, (0.2f + p.beatResponse).coerceIn(0.2f, 3f))

        // The engine's whole frame, exactly as upstream runs it: preset
        // passes into its own internal FBOs, then the final copy onto the
        // DEFAULT framebuffer at the window size set in ensureEngine().
        MilkdropEngine.nativeRender(handle)
        MilkdropEngine.nativeGetLastError()?.let(onError)

        // Lift the frame off framebuffer 0. GL_BACK is framebuffer 0's
        // default read buffer, but the state is set explicitly so nothing an
        // earlier pass did to it this frame can redirect the copy. A missing
        // alpha channel on the surface reads as 1.0 by specification. The
        // renderer's READ binding is captured and restored: the persistence
        // pass and the field sims read through GL_READ_FRAMEBUFFER later this
        // frame, and leaving it on 0 would point them at the window.
        val prevReadFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, prevReadFbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glReadBuffer(GLES30.GL_BACK)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, texWidth, texHeight)
        diagnoseBlackFrame()
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, prevReadFbo[0])

        // Drain latched GL errors: a preset can push the engine down paths
        // that raise recoverable errors, and a latched one is
        // indistinguishable from one raised by whatever this frame checks
        // next. Bounded, because glGetError can queue several.
        var drained = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && drained < 8) drained++
        // The native preset pipeline can leave scissor/masks/blend-equation
        // dirty; re-establish the contract before anything else draws this
        // frame (post pass here, plus any transition co-scene + composite).
        GlUtil.resetFrameState()
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(0, 0, width, height)

        // Post pass: draw the projectM frame through the Customize transforms.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(postProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
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

    /**
     * One-shot black-frame detector, because MilkDrop's historical failure
     * mode is a SILENT black: the engine returns no error, the copy raises no
     * error, and the user sees nothing with nothing to report. For a few
     * early frames this reads a handful of pixels off framebuffer 0 (already
     * bound as READ by the caller); if the engine painted literally nothing
     * across all of them, that fact is reported through the same channel as
     * every other scene failure. Runs [DIAG_FRAMES] times per [init] and
     * never again — steady-state frames pay no readback.
     */
    private fun diagnoseBlackFrame() {
        if (diagDone || diagFrames >= DIAG_FRAMES) return
        // Skip the very first frames: the engine fades its idle preset in.
        diagFrames++
        if (diagFrames <= DIAG_WARMUP) return
        val px = diagPixels
        // Centre plus an off-centre probe: many presets are dark at the rim.
        // glReadPixels writes at the buffer's position without advancing it,
        // so each read gets its own explicit 4-byte slot.
        px.position(0)
        GLES30.glReadPixels(texWidth / 2, texHeight / 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        px.position(4)
        GLES30.glReadPixels(texWidth / 3, texHeight / 3, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        val sawLight = PROBE_BYTES.any { (px.get(it).toInt() and 0xFF) > 8 }
        if (sawLight) {
            diagDone = true // the pipeline is alive; never probe again
        } else if (diagFrames >= DIAG_FRAMES) {
            diagDone = true
            onError(
                "MilkDrop diagnostic: the engine painted a black frame for " +
                    "$DIAG_FRAMES frames at ${texWidth}x$texHeight " +
                    "(preset=${lastPresetPath?.substringAfterLast('/') ?: "idle"}). " +
                    "adb logcat -s milkdrop-jni for the native side.",
            )
        }
    }

    private fun releaseFrameTexture() {
        if (frameTex != 0) GLES30.glDeleteTextures(1, intArrayOf(frameTex), 0)
        frameTex = 0
        texWidth = 0
        texHeight = 0
    }

    override fun release() {
        if (handle != 0L) {
            MilkdropEngine.nativeDestroy(handle)
            handle = 0
        }
        engineWidth = 0
        engineHeight = 0
        releaseFrameTexture()
        if (postProgram != 0) GLES30.glDeleteProgram(postProgram)
        postProgram = 0
        postProgramOk = false
        if (postVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(postVao), 0)
        postVao = 0
        postLocs.clear()
    }
}
