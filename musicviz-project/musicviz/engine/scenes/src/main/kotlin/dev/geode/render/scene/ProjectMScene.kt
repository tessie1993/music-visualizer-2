package dev.geode.render.scene

import android.opengl.GLES30
import android.os.SystemClock
import dev.geode.analysis.AudioFeatures
import dev.geode.render.CompositeGrade
import dev.geode.render.LiveSignal
import dev.geode.render.RenderTarget
import java.io.File

/**
 * A MilkDrop preset rendered by projectM 4.2, straight into a framebuffer this scene owns.
 *
 * ---- what changed, and why it is the whole fix -----------------------------
 *
 * projectM up to 4.1.7 could only render to the DEFAULT framebuffer: `ProjectM::RenderFrame`
 * hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)`, with upstream's own
 * `// ToDo: Allow external apps to provide a custom target framebuffer` sitting on the line.
 * The previous integration therefore let the engine paint framebuffer 0 and copied the result
 * back with `glReadBuffer(GL_BACK)` + `glCopyTexSubImage2D`. Three things were wrong with that
 * and none of them were fixable on this side of the JNI boundary:
 *
 *  - **It painted the window.** Every frame stamped the raw, ungraded engine output onto the
 *    default framebuffer, underneath whatever the compositor drew next.
 *  - **It read a surface that need not exist.** Offscreen render and export bind an FBO while
 *    the EGL draw surface is a pbuffer, so the readback sampled something that was not the
 *    scene - the black frames this scene used to carry a 90-frame diagnostic for.
 *  - **It sized the copy from the WINDOW** while the scene rendered at the thermal governor's
 *    scaled resolution, so the two could disagree and the copy would crop.
 *
 * projectM 4.2 takes a target framebuffer, so [frame] is an ordinary [RenderTarget] this scene
 * allocates at its own render size, the engine composites into it, and [postProgram] grades it
 * into whatever the caller had bound. Nothing in the path touches framebuffer 0, and the
 * on-screen, wallpaper and export paths run identical code.
 */
class ProjectMScene(
    private val postVertexSrc: String,
    private val postFragmentSrc: String,
    private val sharedTextureDir: String?,
    private val onError: (String?) -> Unit = {},
    private val onPresetLoaded: (String) -> Unit = {},
) : Scene,
    PcmSink {
    companion object {
        private const val LOAD_DEBOUNCE_MS = 400L

        private const val PCM_CAPACITY = 8192

        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /** How many stale GL errors to drain after a frame of engine code. */
        private const val ERROR_DRAIN_LIMIT = 8
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

    private var width = 0
    private var height = 0

    private var reportedCreateFailure = false
    private var reportedAllocFailure = false

    /** Where projectM composites. Sized to the SCENE's render size, never the window's. */
    private val frame = RenderTarget("projectm")
    private var engineWidth = 0
    private var engineHeight = 0

    private var postProgram = 0
    private var postProgramOk = false

    private val postLocs = HashMap<String, Int>()
    private var postVao = 0
    private var rotationAngle = 0f
    private var zoomPhase = 0f
    private var cyclePhase = 0f
    private var beatPulse = 0f
    private var lastLoadMs = 0L
    private var sceneParams: SceneParams = SceneParams.DEFAULT

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    @Volatile
    private var pendingPresetPath: String? = null

    @Volatile
    private var lastPresetPath: String? = null

    override fun setParams(params: SceneParams) {
        sceneParams = params
    }

    fun queuePreset(path: String) {
        pendingPresetPath = path
    }

    fun reloadCurrent() {
        lastPresetPath?.let {
            pendingPresetPath = it
            lastLoadMs = 0L
        }
    }

    override fun init() {
        release()
        reportedCreateFailure = false
        try {
            postProgram = GlUtil.buildProgram(postVertexSrc, postFragmentSrc)
            postProgramOk = true
        } catch (e: GlUtil.ShaderCompileException) {
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

    /**
     * Brings the engine and its target up at the scene's current render size.
     *
     * The two sizes are set from the same pair on purpose: upstream derives the composite
     * `glViewport` from `projectm_set_window_size`, so a target that disagrees with it is
     * cropped or letterboxed. Returns false when there is nothing renderable yet.
     */
    private fun ensureEngine(): Boolean {
        if (width <= 1 || height <= 1) return false
        if (!frame.ensure(width, height)) {
            if (!reportedAllocFailure) {
                reportedAllocFailure = true
                onError("MilkDrop could not allocate a ${width}x$height frame buffer")
            }
            return false
        }
        reportedAllocFailure = false
        if (handle == 0L) {
            handle = ProjectMEngine.nativeCreate()
            if (handle == 0L) {
                if (!reportedCreateFailure) {
                    reportedCreateFailure = true
                    onError("projectM engine failed to initialize (adb logcat -s projectm-jni)")
                }
                return false
            }
            lastPresetPath?.let { pendingPresetPath = it }
            engineWidth = 0
        }
        if (engineWidth != frame.width || engineHeight != frame.height) {
            ProjectMEngine.nativeResize(handle, frame.width, frame.height)
            engineWidth = frame.width
            engineHeight = frame.height
        }
        return true
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        rotationAngle = (rotationAngle + p.rotation * dt) % TWO_PI
        zoomPhase = if (p.endlessZoom) (zoomPhase + p.endlessZoomSpeed * dt) % 1f else 0f
        if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1f
        beatPulse = maxOf(LiveSignal.hit(features), beatPulse - dt * 3f).coerceAtLeast(0f)
        if (handle == 0L) return
        if (pcmCount > 0) {
            ProjectMEngine.nativeAddPcmMono(handle, pcmBuffer, pcmCount)
            pcmCount = 0
        } else {
            ProjectMEngine.nativeAddPcmMono(handle, features.waveform, features.waveform.size)
        }
    }

    override fun draw(timeSeconds: Float) {
        if (!postProgramOk) return
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        if (!ensureEngine()) {
            // RenderTarget.ensure() binds and unbinds while it allocates, so a failure here has
            // already moved the draw binding off whatever the caller had. Put it back: the
            // renderer draws the rest of the frame into it.
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevFbo[0])
            GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
            return
        }
        loadPendingPreset()

        val p = sceneParams
        ProjectMEngine.nativeSetBeatSensitivity(handle, (0.2f + p.beatResponse).coerceIn(0.2f, 3f))
        ProjectMEngine.nativeRenderToFbo(handle, frame.fbo)
        ProjectMEngine.nativeGetLastError()?.let(onError)

        // The engine is a foreign renderer: it leaves its own program, VAO, blend and depth
        // state behind, and any error it raised would otherwise be blamed on the next scene.
        var drained = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && drained < ERROR_DRAIN_LIMIT) drained++
        GlUtil.resetFrameState()

        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        drawGraded(p)
    }

    private fun loadPendingPreset() {
        val path = pendingPresetPath ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoadMs < LOAD_DEBOUNCE_MS) return
        pendingPresetPath = null
        lastLoadMs = now
        val dir = File(path).parent ?: "/"
        // The per-preset link directory goes FIRST: it is where the app materializes this
        // preset's texture resolution (renames undone, substitutions, manual choices - see
        // MilkTextureLinks), and search order is the only precedence projectM has, so first is
        // what lets a per-preset choice beat a same-named file in the shared folder.
        val stem = File(path).nameWithoutExtension
        val dirs = mutableListOf("$dir/textures/.links/$stem", dir, "$dir/textures")
        sharedTextureDir?.let { dirs += it }
        ProjectMEngine.nativeSetTexturePaths(handle, dirs.toTypedArray())
        ProjectMEngine.nativeLoadPreset(handle, path, sceneParams.milkdropBlendPresets)
        val error = ProjectMEngine.nativeGetLastError()
        onError(error)
        if (error == null) {
            lastPresetPath = path
            onPresetLoaded(path)
        }
    }

    private fun drawGraded(p: SceneParams) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(postProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.tex)
        setUniform("uTex", 0)
        setUniform1f("uZoom", p.zoom * (1f + beatPulse * p.beatResponse * 0.08f))
        setUniform1f("uRotation", rotationAngle)
        setUniform1f("uZoomPhase", zoomPhase)
        setUniform1f("uMirrorX", if (p.mirror) 1f else 0f)
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

    override fun release() {
        if (handle != 0L) {
            ProjectMEngine.nativeDestroy(handle)
            handle = 0
        }
        engineWidth = 0
        engineHeight = 0
        frame.release()
        if (postProgram != 0) GLES30.glDeleteProgram(postProgram)
        postProgram = 0
        postProgramOk = false
        if (postVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(postVao), 0)
        postVao = 0
        postLocs.clear()
    }
}
