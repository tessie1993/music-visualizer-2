package dev.geode.render

import android.opengl.GLES30

class RenderTarget(
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

    val ok: Boolean
        get() = fbo != 0 && tex != 0

    fun ensure(
        w: Int,
        h: Int,
    ): Boolean {
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

    private fun degenerate(
        w: Int,
        h: Int,
    ): Boolean = w <= 0 || h <= 0

    private fun alreadyAt(
        w: Int,
        h: Int,
    ): Boolean = ok && width == w && height == h

    fun release() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        forget()
    }

    fun forget() {
        fbo = 0
        tex = 0
        width = 0
        height = 0
    }

    override fun toString(): String = "RenderTarget($label ${width}x$height fbo=$fbo tex=$tex)"
}
