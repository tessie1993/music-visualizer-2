package dev.musicviz.render

import android.opengl.GLES30

/**
 * One offscreen RGBA8 colour target: a texture, its framebuffer, and the size
 * they were built at.
 *
 * The pipeline had four hand-written copies of these thirty lines - the live
 * renderer's two scene targets and its trail buffer, and the export
 * compositor's scene target and its trail buffer - which had drifted into three
 * different conventions. Two checked framebuffer completeness and two did not;
 * one cleared itself and three left the texture's contents to the driver. Those
 * are exactly the differences nobody notices until a device with a different
 * driver renders the wrong thing.
 *
 * ## The context-loss contract
 *
 * `VisualizerView` deliberately does not preserve the EGL context, so on resume
 * every handle in this object names an object that no longer exists. There are
 * two correct responses and they are not interchangeable:
 *
 *  - [release] deletes and then zeroes. Correct while the context is still
 *    alive, and harmless on dead names (GL ignores deletes of names it does not
 *    know) **provided nothing in the new context has allocated yet** - which is
 *    why the renderer's release sweep runs before it builds anything.
 *  - [forget] only zeroes. Correct when the context is already gone and the
 *    names may since have been reissued to somebody else's object, where a
 *    delete would silently destroy live state belonging to another owner.
 *
 * Callers keep whichever of the two they used before; this class just stops each
 * site from spelling it out differently.
 *
 * GL thread only, like everything else in `render/`.
 */
class RenderTarget(
    /** Names this target in failure messages; diagnostics only. */
    private val label: String,
) {
    var fbo: Int = 0
        private set
    var tex: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /**
     * True when this target can actually be rendered into. False after a failed
     * [ensure], so a caller can drop the effect that wanted it rather than bind
     * framebuffer 0 and draw a scene straight onto the screen at the wrong size.
     */
    val ok: Boolean
        get() = fbo != 0 && tex != 0

    /**
     * Allocates at [w] x [h] if not already there, and returns [ok].
     *
     * Idempotent and size-keyed, so it is safe to call once per frame as well as
     * from a resize callback - which is what makes the pipeline self-healing
     * when a resize is missed. A zero or negative size is a no-op rather than a
     * GL error: surface callbacks can legitimately report one mid-teardown.
     */
    fun ensure(
        w: Int,
        h: Int,
    ): Boolean {
        // Both cases report the current state: a degenerate size leaves whatever
        // exists alone, and an already-correct target is by definition ok.
        if (degenerate(w, h) || alreadyAt(w, h)) return ok
        release()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
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
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex,
            0,
        )
        // Completeness is checked at every site now, not two of four, and an
        // incomplete target is reported unusable rather than bound and drawn
        // into. The clear is universal too: a target sampled before it is first
        // written - a trail buffer on its allocation frame - otherwise reads
        // whatever the driver left in that memory.
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (complete) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            width = w
            height = h
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) release()
        return complete
    }

    /**
     * A size no target can be built at. Surface callbacks can legitimately
     * report one mid-teardown, so this is a no-op rather than a GL error.
     */
    private fun degenerate(
        w: Int,
        h: Int,
    ): Boolean = w <= 0 || h <= 0

    /** Already allocated, usable, and exactly this size. */
    private fun alreadyAt(
        w: Int,
        h: Int,
    ): Boolean = ok && width == w && height == h

    /** Deletes the GL objects and zeroes the handles. Needs a live context. */
    fun release() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        forget()
    }

    /**
     * Drops the handles WITHOUT deleting. For use after the context has gone,
     * where the names may already belong to another object.
     */
    fun forget() {
        fbo = 0
        tex = 0
        width = 0
        height = 0
    }

    override fun toString(): String = "RenderTarget($label ${width}x$height fbo=$fbo tex=$tex)"
}
