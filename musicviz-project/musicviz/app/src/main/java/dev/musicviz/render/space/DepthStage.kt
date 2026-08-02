package dev.musicviz.render.space

import android.opengl.GLES30

/**
 * A depth buffer for the styles that rasterise geometry, borrowed onto a
 * framebuffer that somebody else owns.
 *
 * Every FBO in this app is colour-only - `FluidBuffers.Fbo.create` attaches
 * `GL_COLOR_ATTACHMENT0` and nothing else, and so does the renderer's own
 * scene target - and the only mentions of `GL_DEPTH_TEST` in the tree are
 * `glDisable` calls, one of them inside [dev.musicviz.render.scene.GlUtil
 * .resetFrameState]. A style that draws overlapping solid geometry needs a
 * depth buffer, and it needs one on the target the renderer has ALREADY bound
 * by the time `draw` runs. That is what this is: a `GL_DEPTH_COMPONENT24`
 * renderbuffer that [attach]es to a caller's FBO for the length of one pass
 * and leaves no trace.
 *
 * ### Why it is a renderbuffer and not a texture
 *
 * Nothing here reads depth back. A renderbuffer is the cheaper object - the
 * driver is free to keep it in tile memory and never allocate a linear
 * surface for it at all, which is the whole point of the invalidate below. A
 * style that wants to SAMPLE depth (the depth-aware upsample the two
 * volumetric styles will want) cannot use this and must write linear depth
 * into a colour channel it owns; see [ResTarget].
 *
 * ### Why [detach] invalidates before it unbinds
 *
 * A depth attachment that is not invalidated is resolved out of tile memory
 * and written to main memory at end-of-pass, every frame, because the driver
 * has no way to know nobody will read it. At 1080x2400 that is about 10 MB per
 * frame - roughly 500 MB/s at 50 fps, on a part with about 25 GB/s of total
 * bandwidth. The write is pure waste and it is invisible: no GL error, no
 * validation warning, just a fifth of the memory system gone. `glInvalidate-
 * Framebuffer` on the still-bound FBO is what tells the driver to drop it,
 * and it has to happen while that FBO is bound - hence the ordering in
 * [detach], which [dev.musicviz.SpaceFoundationTest] pins.
 *
 * ### State
 *
 * The renderer has a target bound and its own state contract when a scene
 * draws, so everything this touches - the framebuffer binding, depth test,
 * depth write, depth func, the depth clear value and blend - is snapshotted
 * into preallocated fields on [attach] and put back exactly as found on
 * [detach]. Preallocated because this runs every frame: the fields are the
 * `HotPathReuseTest` convention, the same one `WaterScene.kt:79-81` follows.
 *
 * A driver that refuses the attachment degrades the stage to unavailable and
 * says so once; it never throws. Depth-less is a style that draws in the wrong
 * order, which is a bad frame - a crash on the GL thread takes the whole app
 * down with it, from inside `onSurfaceCreated`, before the user has chosen
 * anything.
 */
internal class DepthStage {
    /**
     * False once the driver has refused a depth renderbuffer. Callers keep
     * drawing without depth rather than going dark: [attach] returns false and
     * every call after it is a no-op.
     */
    var available = true
        private set

    /** True between a successful [attach] and its [detach]. */
    var attached = false
        private set

    /** Set by the owning scene; reported once, never per frame. */
    var onShaderError: (String?) -> Unit = {}

    private var renderbuffer = 0
    private var storageWidth = 0
    private var storageHeight = 0
    private var reported = false

    /** The FBO [attach] hung the renderbuffer on, so [detach] can find it. */
    private var attachedFbo = 0

    // Snapshots. Preallocated: attach/detach run once per frame per style.
    private val prevFbo = IntArray(1)
    private val prevDepthFunc = IntArray(1)
    private val prevDepthWrite = IntArray(1)
    private val prevBlendFunc = IntArray(4)
    private val prevDepthClear = FloatArray(1)
    private var prevDepthTest = false
    private var prevBlend = false

    /**
     * The attachment list handed to `glInvalidateFramebuffer`, held rather
     * than built: `intArrayOf(...)` at the call site is an allocation on the
     * draw path, which is exactly what a recent commit removed from the fluid
     * scenes.
     */
    private val depthAttachment = intArrayOf(GLES30.GL_DEPTH_ATTACHMENT)

    /**
     * Hangs the depth buffer on [fbo] and turns depth testing on for the pass
     * that follows. Returns false when there is no depth to be had, in which
     * case nothing was changed and [detach] need not be called.
     *
     * [width] and [height] are the attachment's size in pixels - the caller's
     * viewport, or [ResTarget]'s reduced size. ES 3.0 allows attachments of
     * different sizes and renders into their intersection, so a stale size
     * would silently clip the frame rather than fail; the storage is
     * reallocated whenever it does not match.
     */
    fun attach(
        fbo: Int,
        width: Int,
        height: Int,
    ): Boolean {
        // The default framebuffer's depth buffer is whatever EGL gave the
        // surface and cannot be attached to; every scene in this app draws
        // into the renderer's FBO, so this is a caller bug, not a device.
        if (!available || attached || fbo == 0 || width <= 0 || height <= 0) return false
        if (!ensureStorage(width, height)) return false

        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_DEPTH_FUNC, prevDepthFunc, 0)
        GLES30.glGetIntegerv(GLES30.GL_DEPTH_WRITEMASK, prevDepthWrite, 0)
        GLES30.glGetFloatv(GLES30.GL_DEPTH_CLEAR_VALUE, prevDepthClear, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_RGB, prevBlendFunc, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_RGB, prevBlendFunc, 1)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, prevBlendFunc, 2)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, prevBlendFunc, 3)
        prevDepthTest = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST)
        prevBlend = GLES30.glIsEnabled(GLES30.GL_BLEND)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER,
            renderbuffer,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_RENDERBUFFER,
                0,
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
            fail("this GPU refused a depth attachment (${width}x$height)")
            return false
        }
        attachedFbo = fbo
        attached = true

        // Depth writes have to be on for the clear to reach the buffer, and
        // LEQUAL rather than LESS so a second pass may re-draw coplanar
        // geometry (the additive halves of the two-pass styles) without
        // z-fighting against the depth its own opaque pass just wrote.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        // Opaque is what a depth pass is for, and the previous scene or pass
        // is free to have left blending on. A style that wants a blended
        // depth-tested pass turns it back on itself; leaving it as found would
        // mean the FIRST pass of every depth style silently blended.
        GLES30.glDisable(GLES30.GL_BLEND)
        return true
    }

    /**
     * Ends the depth pass: drops the depth contents before they can be written
     * out, takes the renderbuffer off the caller's FBO, and restores every
     * piece of state [attach] touched.
     *
     * The order is the contract. Invalidate while still bound, THEN detach,
     * THEN restore the binding - an invalidate after the unbind names the
     * attachment of whatever framebuffer happens to be bound instead.
     */
    fun detach() {
        if (!attached) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, attachedFbo)
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, depthAttachment, 0)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER,
            0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        // Exactly as found, in every branch: `GlUtil.resetFrameState()` asserts
        // depth test off and depth writes on at the top of the next frame, but
        // the renderer's own passes run BEFORE that, in this frame, and a
        // leaked depth test would reject every one of them against a depth
        // buffer that no longer exists.
        if (prevDepthTest) GLES30.glEnable(GLES30.GL_DEPTH_TEST) else GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(prevDepthFunc[0])
        GLES30.glDepthMask(prevDepthWrite[0] != 0)
        GLES30.glClearDepthf(prevDepthClear[0])
        if (prevBlend) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3])
        attached = false
        attachedFbo = 0
    }

    /**
     * Frees the renderbuffer. Also called for a lost EGL context, where the
     * name is already dead - hence the zeroing, so a later [attach] allocates
     * a new one instead of hanging a stale name on a live FBO.
     */
    fun release() {
        if (renderbuffer != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(renderbuffer), 0)
        renderbuffer = 0
        storageWidth = 0
        storageHeight = 0
        attached = false
        attachedFbo = 0
        available = true
        reported = false
    }

    /** Allocates or resizes the renderbuffer; only ever on a size change. */
    private fun ensureStorage(
        width: Int,
        height: Int,
    ): Boolean {
        if (renderbuffer != 0 && storageWidth == width && storageHeight == height) return true
        if (renderbuffer != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(renderbuffer), 0)
        val ids = IntArray(1)
        GLES30.glGenRenderbuffers(1, ids, 0)
        renderbuffer = ids[0]
        if (renderbuffer == 0) {
            fail("this GPU refused a depth renderbuffer")
            return false
        }
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderbuffer)
        // DEPTH_COMPONENT24 is one of the three required renderbuffer depth
        // formats in ES 3.0. 16 bits is not enough for the near/far pair
        // SpaceCamera uses - the geometry styles put the subject at 2-8 world
        // units with a far plane at 60, where 16-bit depth quantises to visible
        // stair-stepping on any surface seen at a glancing angle.
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        storageWidth = width
        storageHeight = height
        return true
    }

    /** Goes unavailable and says why - once, not once per frame. */
    private fun fail(message: String) {
        available = false
        if (reported) return
        reported = true
        android.util.Log.w("DepthStage", message)
        onShaderError("Depth-tested geometry unavailable: $message")
    }
}
