package dev.musicviz.render.space

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.fluid.FluidBuffers
import dev.musicviz.render.scene.GlUtil
import kotlin.math.roundToInt

/**
 * The only way a scene in this app can render below `renderWidth` /
 * `renderHeight`.
 *
 * `onSurfaceChanged` hands every scene the SUPERSAMPLED size and the scene
 * renders at it - which is right for the styles that exist, and wrong for a
 * marched volume or a heavy shading stack, several of which are only
 * affordable at half that. This owns an RGBA16F colour target at a fraction of
 * the caller's size, plus (when asked) the [DepthStage] that goes with it, and
 * resolves back up with a five-tap Catmull-Rom and a mild clamped unsharp.
 *
 * ### The two modes are one call
 *
 * [begin] and [resolve] bracket the style's drawing either way:
 *
 *  - **Reduced** ([scale] < 1). Colour and depth are this object's, and the
 *    resolve blit is the pass that scales the result back up.
 *  - **Attached** ([scale] == 1, and whenever the reduced path is unavailable
 *    on this GPU). Nothing is allocated and no blit happens: the depth buffer
 *    hangs directly off the FBO the renderer already bound, the style draws
 *    into it at full size, and [resolve] does nothing but end the depth pass.
 *    Zero extra passes, zero extra allocations.
 *
 * A style therefore never branches on which mode it is in, and a device that
 * refuses half-float render targets or the resolve shader falls back to
 * full-resolution rendering rather than to a dead style.
 *
 * ### Where [scale] comes from
 *
 * From the quality tier, DIVIDED by the supersample factor - see [scaleFor].
 * That division is the whole point and it is easy to get backwards.
 */
internal class ResTarget(
    private val context: Context,
    /**
     * False for the styles that are entirely non-opaque: nothing in them can
     * be rejected by a depth test against itself, so a depth renderbuffer
     * would be allocated, cleared and never read.
     */
    private val useDepth: Boolean = true,
) {
    companion object {
        /**
         * The scale a style asks for, corrected for the fact that its caller's
         * pixels are already inflated.
         *
         * `VisualizerRenderer.supersampleFactor` returns 1.4x per axis on a
         * small screen, 1.25x in the middle and 1.0x on a large one, so
         * `renderWidth` is 1.4x the window's width on a budget phone and
         * exactly the window's width on a flagship. A style that then applied
         * a flat 0.7 would render at 0.98x of the actual display on the budget
         * part - the one that throttles - and at 0.70x on the flagship, which
         * has the headroom. Backwards, and by a factor of two in pixels.
         *
         * Dividing by the factor makes [baseScale] mean what a performance
         * budget actually needs it to mean: a fraction of the pixels the
         * DISPLAY has, the same fraction on every device.
         */
        fun scaleFor(
            baseScale: Float,
            supersample: Float,
        ): Float = (baseScale / supersample.coerceAtLeast(0.25f)).coerceIn(MIN_SCALE, 1f)

        /**
         * Floor on [scaleFor]. At 0.1 a 1080x2400 frame is 108x240, which is
         * about as far as the depth-aware styles can be pushed before the
         * upsample stops carrying a silhouette at all.
         */
        const val MIN_SCALE: Float = 0.1f

        /** Default unsharp gain for [resolve]. Acutance, not an effect. */
        const val DEFAULT_SHARPEN: Float = 0.35f
    }

    /** The depth buffer for whichever target is in use. Public: styles toggle
     *  depth writes between an opaque and an additive pass themselves. */
    val depth = DepthStage()

    /**
     * The scale actually in force this frame: the requested scale in reduced
     * mode, 1 in attached mode. Read it for anything that has to agree with
     * the pixel grid - a blue-noise dither, a screen-space step length.
     */
    var scale: Float = 1f
        private set

    /** Size being rendered at this frame, in pixels. */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    /** Set by the owning scene; see the note on [DepthStage.onShaderError]. */
    var onShaderError: (String?) -> Unit = {}
        set(value) {
            field = value
            depth.onShaderError = value
        }

    private var color: FluidBuffers.Fbo? = null
    private var formats: FluidBuffers.Formats? = null
    private var requestedScale = 1f

    private var program = 0
    private var uSource = -1
    private var uSharpen = -1
    private var emptyVao = 0
    private var programOk = false

    /** True while [begin]/[resolve] are working on this object's own target. */
    private var reduced = false

    // Snapshots of the caller's state. Preallocated per HotPathReuseTest;
    // DepthStage keeps its own copies because it is usable without this class.
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)
    private val prevBlendFunc = IntArray(4)
    private var prevBlend = false

    /**
     * Builds the resolve program. [reuseFormats] lets a scene that already
     * owns a `FluidSim` pass on its probe rather than paying for a second one.
     */
    fun init(reuseFormats: FluidBuffers.Formats? = null) {
        release()
        formats = reuseFormats ?: FluidBuffers.probeFormats()
        // A driver-rejected shader must degrade the style, never crash the GL
        // thread - and here it degrades only as far as full-resolution
        // rendering, because the attached mode below needs no program at all.
        try {
            program =
                GlUtil.buildProgram(
                    GlUtil.loadShader(context, R.raw.quad_vert),
                    GlUtil.loadShader(context, R.raw.space_resolve_frag),
                )
            uSource = GLES30.glGetUniformLocation(program, "uSource")
            uSharpen = GLES30.glGetUniformLocation(program, "uSharpen")
            programOk = true
        } catch (e: GlUtil.ShaderCompileException) {
            android.util.Log.w("ResTarget", "resolve shader rejected by driver: ${e.message}")
            onShaderError("Reduced-resolution rendering unavailable on this GPU; drawing at full size")
            return
        }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        emptyVao = ids[0]
    }

    /**
     * Sizes the reduced target. [renderWidth] and [renderHeight] are the
     * scene's own size - what `resize` was handed - and [scale] is [scaleFor]'s
     * result. A scale of 1 (or a GPU that could not give us a target) releases
     * the colour buffer and puts the object in attached mode.
     *
     * Call from `resize` or on a quality-tier change, never per frame: it
     * allocates, and it binds a framebuffer of its own.
     */
    fun ensure(
        renderWidth: Int,
        renderHeight: Int,
        scale: Float,
    ) {
        if (renderWidth <= 0 || renderHeight <= 0) return
        val fmt = formats
        val want = scale.coerceIn(MIN_SCALE, 1f)
        val w = (renderWidth * want).roundToInt().coerceAtLeast(2)
        val h = (renderHeight * want).roundToInt().coerceAtLeast(2)
        if (!programOk || fmt == null || !fmt.ok || want >= 1f) {
            color?.release()
            color = null
            requestedScale = 1f
            return
        }
        val existing = color
        if (existing != null && existing.width == w && existing.height == h) {
            requestedScale = want
            return
        }
        // ensure() runs outside the draw snapshot but Fbo.create binds a
        // framebuffer and leaves the binding at 0, so the caller's target and
        // viewport are restored here for the same reason FluidSim.allocGrids
        // restores them: the engine's next pass would otherwise render into
        // this texture.
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        existing?.release()
        // LINEAR: the resolve's Catmull-Rom taps are bilinear fetches at
        // non-integer offsets, so NEAREST here would collapse the kernel to
        // three of its five taps.
        val next = FluidBuffers.Fbo(w, h, fmt.rgba, linear = true).also { it.create() }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        if (!next.ok) {
            next.release()
            color = null
            requestedScale = 1f
            android.util.Log.w("ResTarget", "reduced target ${w}x$h refused; drawing at full size")
            return
        }
        color = next
        requestedScale = want
    }

    /**
     * Binds the target the style should draw into and starts the depth pass.
     * The caller's framebuffer, viewport and blend state are snapshotted here
     * and restored by [resolve]; between the two, the style owns them.
     */
    fun begin() {
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_RGB, prevBlendFunc, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_RGB, prevBlendFunc, 1)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, prevBlendFunc, 2)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, prevBlendFunc, 3)
        prevBlend = GLES30.glIsEnabled(GLES30.GL_BLEND)
        val target = color
        reduced = target != null && requestedScale < 1f
        if (reduced && target != null) {
            scale = requestedScale
            width = target.width
            height = target.height
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
            GLES30.glViewport(0, 0, target.width, target.height)
            // The renderer clears its own target, not this one, and a style
            // that does not cover every pixel would otherwise resolve last
            // frame's contents into the gaps.
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (useDepth) depth.attach(target.fbo, target.width, target.height)
        } else {
            scale = 1f
            width = prevViewport[2]
            height = prevViewport[3]
            if (useDepth) depth.attach(prevFbo[0], prevViewport[2], prevViewport[3])
        }
    }

    /**
     * Ends the depth pass and, in reduced mode, blits the result back over the
     * caller's target at its own size. [sharpen] is the unsharp gain; pass 0
     * for a style whose own output is already high-contrast.
     *
     * The blit is opaque by construction - it REPLACES the region it covers,
     * which is what makes it the same pass the style would have drawn anyway
     * rather than an extra composite.
     */
    fun resolve(sharpen: Float = DEFAULT_SHARPEN) {
        depth.detach()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        val target = color
        if (reduced && target != null && programOk) {
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.tex)
            GLES30.glUniform1i(uSource, 0)
            GLES30.glUniform1f(uSharpen, sharpen.coerceIn(0f, 1f))
            GLES30.glBindVertexArray(emptyVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)
        }
        reduced = false
        if (prevBlend) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3])
    }

    fun release() {
        depth.release()
        color?.release()
        color = null
        if (program != 0) GLES30.glDeleteProgram(program)
        if (emptyVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(emptyVao), 0)
        program = 0
        emptyVao = 0
        programOk = false
        reduced = false
        scale = 1f
        requestedScale = 1f
    }
}

/**
 * How a scene learns the supersample factor its own size was inflated by.
 *
 * [ResTarget.scaleFor] needs it and a scene cannot recover it from the size it
 * was given: `renderWidth` alone is ambiguous, because a 1600px window at
 * 1.25x and a 1400px window at 1.4x both arrive as 2000 px. So the renderer,
 * which computed the factor, pushes it - once per `onSurfaceChanged`, before
 * `resize`, so a scene that sizes a [ResTarget] in `resize` already has it.
 *
 * @see VisualizerRenderer.supersampleFactor
 */
internal interface SupersampleAware {
    fun setSupersample(factor: Float)
}
