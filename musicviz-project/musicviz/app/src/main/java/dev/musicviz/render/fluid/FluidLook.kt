package dev.musicviz.render.fluid

// Ported from WebGL-Fluid-Simulation - MIT License,
// Copyright (c) 2017 Pavel Dobryakov (bloom / sunrays / shading / dither
// look chain, restructured for GLES3 and MusicViz's FBO plumbing).

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.render.scene.GlUtil

/**
 * F4 look chain for the FLUID scene: soft-knee HDR bloom through a mip
 * up/down chain (the additive ONE,ONE upsample is what makes it glow),
 * 16-step screen-space sunrays, pseudo-normal shading and noise dither,
 * composited by a keyword-variant display program (SHADING/BLOOM/SUNRAYS
 * #defines, program cache keyed by the flag set - no uniform branching in
 * the hot shader). All methods run on the GL thread.
 */
internal class FluidLook(
    private val context: Context,
) {
    companion object {
        private const val BLOOM_BASE_RES = 256
        private const val BLOOM_MAX_LEVELS = 8
        private const val SUNRAYS_RES = 196
        private const val DITHER_SIZE = 64
    }

    var bloomIntensity = 0.8f
    var bloomThreshold = 0.6f
    var bloomKnee = 0.7f
    var sunraysWeight = 1.0f

    private lateinit var formats: FluidBuffers.Formats
    private var prefilterProgram = 0
    private var bloomBlurProgram = 0
    private var bloomFinalProgram = 0
    private var sunraysMaskProgram = 0
    private var sunraysProgram = 0
    private var blurProgram = 0

    /** Display program per keyword set (bit0 SHADING, bit1 BLOOM, bit2 SUNRAYS). */
    private val displayPrograms = HashMap<Int, Int>()
    private val uniforms = HashMap<Int, HashMap<String, Int>>()

    private var bloomMips = ArrayList<FluidBuffers.Fbo>()
    private var bloomResult: FluidBuffers.Fbo? = null
    private var sunraysMask: FluidBuffers.Fbo? = null
    private var sunrays: FluidBuffers.Fbo? = null
    private var sunraysTemp: FluidBuffers.Fbo? = null
    private var ditherTex = 0
    private var vao = 0
    private var vbo = 0
    private var targetW = 1
    private var targetH = 1

    var available = false
        private set

    fun create(fmts: FluidBuffers.Formats) {
        release()
        formats = fmts
        // Driver-rejected look shaders must not crash the GL thread: on
        // failure the scene falls back to the sim's plain dye display.
        try {
            val vert = loadRaw(R.raw.fluid_base_vert)
            prefilterProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_bloom_prefilter_frag))
            bloomBlurProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_bloom_blur_frag))
            bloomFinalProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_bloom_final_frag))
            sunraysMaskProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_sunrays_mask_frag))
            sunraysProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_sunrays_frag))
            blurProgram = GlUtil.buildProgram(vert, loadRaw(R.raw.fluid_blur_frag))
            val displaySrc = loadRaw(R.raw.fluid_display_frag)
            for (flags in 0 until 8) {
                displayPrograms[flags] = GlUtil.buildProgram(vert, withKeywords(displaySrc, flags))
            }
        } catch (e: GlUtil.ShaderCompileException) {
            android.util.Log.w("FluidSim", "look shader rejected by driver: ${e.message}")
            release()
            return
        }
        listOf(
            prefilterProgram,
            bloomBlurProgram,
            bloomFinalProgram,
            sunraysMaskProgram,
            sunraysProgram,
            blurProgram,
        ).forEach { uniforms[it] = HashMap() }
        displayPrograms.values.forEach { uniforms[it] = HashMap() }

        // Ordered-noise dither kills banding in the dark bloom gradients. The
        // MIT blue-noise PNG (LDR_LLL1_0.png) is the upstream asset; a hashed
        // white-noise texture is generated here instead so no binary asset is
        // required - visually equivalent at +-1/255 amplitude.
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        ditherTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ditherTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        val noise = ByteArray(DITHER_SIZE * DITHER_SIZE * 3)
        val rnd = java.util.Random(0x0D17_4E12L)
        rnd.nextBytes(noise)
        val buf =
            java.nio.ByteBuffer
                .allocateDirect(noise.size)
                .put(noise)
                .apply { position(0) }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB8,
            DITHER_SIZE,
            DITHER_SIZE,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            buf,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)

        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val qbuf =
            java.nio.ByteBuffer
                .allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(quad)
                .apply { position(0) }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qbuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        available = true
    }

    fun resize(
        w: Int,
        h: Int,
    ) {
        if (!available) return
        if (w == targetW && h == targetH && bloomResult != null) return
        targetW = w
        targetH = h
        releaseTargets()
        val (bw, bh) = FluidBuffers.resolution(BLOOM_BASE_RES, w, h)
        bloomResult = FluidBuffers.Fbo(bw, bh, formats.rgba, linear = true).also { it.create() }
        bloomMips = ArrayList()
        var mw = bw
        var mh = bh
        // >= 2px stop: bloom needs at least 2 mip levels or it early-outs.
        for (i in 0 until BLOOM_MAX_LEVELS) {
            mw = mw shr 1
            mh = mh shr 1
            if (mw < 2 || mh < 2) break
            bloomMips.add(FluidBuffers.Fbo(mw, mh, formats.rgba, linear = true).also { it.create() })
        }
        val (sw, sh) = FluidBuffers.resolution(SUNRAYS_RES, w, h)
        sunraysMask = FluidBuffers.Fbo(sw, sh, formats.rgba, linear = true).also { it.create() }
        sunrays = FluidBuffers.Fbo(sw, sh, formats.r, linear = true).also { it.create() }
        sunraysTemp = FluidBuffers.Fbo(sw, sh, formats.r, linear = true).also { it.create() }
        val allOk =
            bloomResult?.ok == true &&
                bloomMips.all { it.ok } &&
                sunraysMask?.ok == true &&
                sunrays?.ok == true &&
                sunraysTemp?.ok == true
        if (!allOk) {
            android.util.Log.w("FluidSim", "look target allocation failed - bloom/sunrays disabled")
            releaseTargets()
        }
    }

    /**
     * Runs the offscreen bloom + sunrays passes from [dyeTex]. Call while the
     * sim is in its offscreen phase (caller restores the engine FBO after).
     */
    fun process(
        dyeTex: Int,
        bloomOn: Boolean,
        sunraysOn: Boolean,
    ) {
        if (!available) return
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)
        if (bloomOn) applyBloom(dyeTex)
        if (sunraysOn) applySunrays(dyeTex)
        GLES30.glBindVertexArray(0)
    }

    private fun applyBloom(dyeTex: Int) {
        val result = bloomResult ?: return
        if (bloomMips.size < 2) return
        // Prefilter into the largest mip.
        val knee = bloomThreshold * bloomKnee + 1e-4f
        var dst = bloomMips[0]
        use(prefilterProgram, 1f / dst.width, 1f / dst.height)
        bindTex(prefilterProgram, "uTexture", dyeTex, 0)
        GLES30.glUniform3f(
            loc(prefilterProgram, "uCurve"),
            bloomThreshold - knee,
            knee * 2f,
            0.25f / knee,
        )
        GLES30.glUniform1f(loc(prefilterProgram, "uThreshold"), bloomThreshold)
        blit(dst)
        // Downsample the chain.
        var last = dst
        for (i in 1 until bloomMips.size) {
            dst = bloomMips[i]
            use(bloomBlurProgram, 1f / last.width, 1f / last.height)
            bindTex(bloomBlurProgram, "uTexture", last.tex, 0)
            blit(dst)
            last = dst
        }
        // Additive upsample: this ONE,ONE accumulation is the glow.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glEnable(GLES30.GL_BLEND)
        for (i in bloomMips.size - 2 downTo 0) {
            dst = bloomMips[i]
            use(bloomBlurProgram, 1f / last.width, 1f / last.height)
            bindTex(bloomBlurProgram, "uTexture", last.tex, 0)
            blit(dst)
            last = dst
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        use(bloomFinalProgram, 1f / last.width, 1f / last.height)
        bindTex(bloomFinalProgram, "uTexture", last.tex, 0)
        GLES30.glUniform1f(loc(bloomFinalProgram, "uIntensity"), bloomIntensity)
        blit(result)
    }

    private fun applySunrays(dyeTex: Int) {
        val mask = sunraysMask ?: return
        val rays = sunrays ?: return
        val temp = sunraysTemp ?: return
        use(sunraysMaskProgram, 1f / mask.width, 1f / mask.height)
        bindTex(sunraysMaskProgram, "uTexture", dyeTex, 0)
        blit(mask)
        use(sunraysProgram, 1f / rays.width, 1f / rays.height)
        bindTex(sunraysProgram, "uTexture", mask.tex, 0)
        GLES30.glUniform1f(loc(sunraysProgram, "uWeight"), sunraysWeight)
        blit(rays)
        // One separable 3-fetch blur iteration (a 5-tap Gaussian).
        use(blurProgram, 1f / rays.width, 1f / rays.height)
        bindTex(blurProgram, "uTexture", rays.tex, 0)
        GLES30.glUniform2f(loc(blurProgram, "uDirection"), 1.33333f / rays.width, 0f)
        blit(temp)
        bindTex(blurProgram, "uTexture", temp.tex, 0)
        GLES30.glUniform2f(loc(blurProgram, "uDirection"), 0f, 1.33333f / rays.height)
        blit(rays)
    }

    /**
     * Draws the shaded/bloomed/sunlit dye into the currently bound
     * framebuffer + viewport with ONE, ONE_MINUS_SRC_ALPHA blending.
     */
    fun drawDisplay(
        dyeTex: Int,
        shadingOn: Boolean,
        bloomOn: Boolean,
        sunraysOn: Boolean,
        viewportW: Int,
        viewportH: Int,
    ) {
        if (!available) return
        val flags =
            (if (shadingOn) 1 else 0) or
                (if (bloomOn && bloomMips.size >= 2 && bloomResult != null) 2 else 0) or
                (if (sunraysOn && sunrays != null) 4 else 0)
        val program = displayPrograms.getValue(flags)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        use(program, 1f / viewportW, 1f / viewportH)
        bindTex(program, "uDye", dyeTex, 0)
        if (flags and 2 != 0) bindTex(program, "uBloom", bloomResult!!.tex, 1)
        if (flags and 4 != 0) bindTex(program, "uSunrays", sunrays!!.tex, 2)
        bindTex(program, "uDither", ditherTex, 3)
        GLES30.glUniform2f(
            loc(program, "uDitherScale"),
            viewportW.toFloat() / DITHER_SIZE,
            viewportH.toFloat() / DITHER_SIZE,
        )
        GLES30.glUniform2f(loc(program, "uTexelSize"), 1f / viewportW, 1f / viewportH)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        releaseTargets()
        (
            listOf(
                prefilterProgram,
                bloomBlurProgram,
                bloomFinalProgram,
                sunraysMaskProgram,
                sunraysProgram,
                blurProgram,
            ) + displayPrograms.values
        ).forEach { if (it != 0) GLES30.glDeleteProgram(it) }
        prefilterProgram = 0
        bloomBlurProgram = 0
        bloomFinalProgram = 0
        sunraysMaskProgram = 0
        sunraysProgram = 0
        blurProgram = 0
        displayPrograms.clear()
        uniforms.clear()
        if (ditherTex != 0) GLES30.glDeleteTextures(1, intArrayOf(ditherTex), 0)
        ditherTex = 0
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        vbo = 0
        vao = 0
        targetW = 1
        targetH = 1
        available = false
    }

    private fun releaseTargets() {
        bloomMips.forEach { it.release() }
        bloomMips = ArrayList()
        bloomResult?.release()
        bloomResult = null
        sunraysMask?.release()
        sunraysMask = null
        sunrays?.release()
        sunrays = null
        sunraysTemp?.release()
        sunraysTemp = null
    }

    private fun withKeywords(
        src: String,
        flags: Int,
    ): String {
        val defines = StringBuilder()
        if (flags and 1 != 0) defines.append("#define SHADING\n")
        if (flags and 2 != 0) defines.append("#define BLOOM\n")
        if (flags and 4 != 0) defines.append("#define SUNRAYS\n")
        if (defines.isEmpty()) return src
        // #defines must land AFTER the #version directive (GLSL ES requires
        // #version first); find that line rather than assuming it is line 1.
        val vIdx = src.indexOf("#version")
        val nl = src.indexOf('\n', if (vIdx >= 0) vIdx else 0)
        return src.substring(0, nl + 1) + defines + src.substring(nl + 1)
    }

    private fun use(
        program: Int,
        invW: Float,
        invH: Float,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(loc(program, "uInvRes"), invW, invH)
    }

    private fun blit(target: FluidBuffers.Fbo) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
        GLES30.glViewport(0, 0, target.width, target.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun loc(
        program: Int,
        name: String,
    ): Int = uniforms.getValue(program).getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun bindTex(
        program: Int,
        name: String,
        tex: Int,
        unit: Int,
    ) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc(program, name), unit)
    }

    private fun loadRaw(resId: Int): String =
        context.resources
            .openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
}
