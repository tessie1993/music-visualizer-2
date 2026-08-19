package dev.geode

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.geode.render.scene.MilkdropEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The MilkDrop engine rendering REAL frames on a REAL GL driver — the test
 * that could never exist before, because the APK shipped arm64-only and the
 * CI emulator is x86_64, so `MilkdropEngine.available` was false everywhere a
 * suite ran and the entire pipeline was unfalsifiable off a phone. A silent
 * black MilkDrop shipped past a fully green build more than once; this is the
 * gate that makes that class of regression fail CI instead.
 *
 * What is exercised is the integration exactly as [dev.geode.render.scene.MilkdropScene]
 * runs it: the stock engine renders onto the DEFAULT framebuffer (a pbuffer
 * here, the GLSurfaceView's back buffer in the app), the frame is lifted off
 * framebuffer 0 with glCopyTexSubImage2D, and the pixels are asserted
 * NON-BLACK — first for projectM's built-in idle preset, then for a .milk
 * preset shipped in the app's starter pack, loaded through the same texture
 * search paths the scene sets.
 */
@RunWith(AndroidJUnit4::class)
class MilkdropRenderInstrumentedTest {
    private companion object {
        const val W = 320
        const val H = 240
        const val FRAMES = 90
        const val SAMPLES_PER_FRAME = 735 // one 60 fps frame of 44.1 kHz mono

        /** Channel value above which a pixel counts as "the engine painted". */
        const val LIGHT = 8
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    /**
     * Loadability is asserted, not assumed: a missing or unlinkable native
     * lib on an ABI the APK ships IS the bug (it is exactly how the rebrand
     * killed MilkDrop in a release once). Only a device whose primary ABI the
     * APK does not carry may skip.
     */
    @Test
    fun the_engine_is_loadable_on_every_abi_the_apk_ships() {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        assumeTrue("APK ships no libs for $abi", abi == "arm64-v8a" || abi == "x86_64")
        assertTrue(
            "MilkdropEngine.available is false on $abi - the JNI pairing is broken and " +
                "MilkDrop is silently hidden on every such device",
            MilkdropEngine.available,
        )
    }

    @Before
    fun setUp() {
        // EGL only - engine availability is per-test: the loadability test
        // above must FAIL (not skip) when the lib is broken on a shipped ABI,
        // so no engine assumption may run before it.
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals("no EGL display", EGL14.EGL_NO_DISPLAY, eglDisplay)
        val version = IntArray(2)
        assertTrue("eglInitialize failed", EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
        // 0x0040 is EGL_OPENGL_ES3_BIT (EGL14 predates the ES3 constant).
        val attribs =
            intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        assumeTrue(
            "no ES3 pbuffer EGL config on this device",
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0,
        )
        val config = requireNotNull(configs[0])
        eglSurface =
            EGL14.eglCreatePbufferSurface(
                eglDisplay,
                config,
                intArrayOf(EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE),
                0,
            )
        eglContext =
            EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
        assertNotEquals("pbuffer surface failed", EGL14.EGL_NO_SURFACE, eglSurface)
        assertNotEquals("ES3 context failed", EGL14.EGL_NO_CONTEXT, eglContext)
        assertTrue(
            "eglMakeCurrent failed",
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
        )
    }

    @After
    fun tearDown() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }

    private fun feedPcm(
        handle: Long,
        frame: Int,
    ) {
        val buf = FloatArray(SAMPLES_PER_FRAME)
        for (i in buf.indices) {
            val t = (frame * SAMPLES_PER_FRAME + i) / 44100.0
            buf[i] = (0.6 * sin(2 * PI * 220 * t) + 0.3 * sin(2 * PI * 880 * t)).toFloat()
        }
        MilkdropEngine.nativeAddPcmMono(handle, buf, buf.size)
    }

    /** Renders [FRAMES] frames the way MilkdropScene does and returns the lit share of fb0. */
    private fun renderAndMeasure(handle: Long): Double {
        // The scene's copy target, so the copy path is exercised too.
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, W, H, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        for (f in 0 until FRAMES) {
            feedPcm(handle, f)
            MilkdropEngine.nativeRender(handle)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glReadBuffer(GLES30.GL_BACK)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, W, H)
        }
        // Read the COPY, not fb0: it is what the post pass would sample, so a
        // copy that silently missed the frame fails here too.
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_READ_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex[0],
            0,
        )
        val px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(1, tex, 0)
        var lit = 0
        for (i in 0 until W * H) {
            val r = px.get(i * 4).toInt() and 0xFF
            val g = px.get(i * 4 + 1).toInt() and 0xFF
            val b = px.get(i * 4 + 2).toInt() and 0xFF
            if (r > LIGHT || g > LIGHT || b > LIGHT) lit++
        }
        return lit.toDouble() / (W * H)
    }

    @Test
    fun the_engine_renders_a_visible_idle_frame() {
        assumeTrue("engine not loadable on this ABI", MilkdropEngine.available)
        val handle = MilkdropEngine.nativeCreate()
        assertNotEquals("projectm_create failed on this driver", 0L, handle)
        try {
            MilkdropEngine.nativeResize(handle, W, H)
            val lit = renderAndMeasure(handle)
            assertTrue(
                "the engine painted a black frame: only ${"%.1f".format(lit * 100)}% of pixels lit " +
                    "after $FRAMES frames of the idle preset - the exact silent failure MilkDrop " +
                    "shipped with before this gate existed",
                lit > 0.05,
            )
        } finally {
            MilkdropEngine.nativeDestroy(handle)
        }
    }

    @Test
    fun a_starter_pack_preset_loads_and_renders_visibly() {
        assumeTrue("engine not loadable on this ABI", MilkdropEngine.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Through the app's own import path: assets/milk -> filesDir, the same
        // files MilkStarterPack installs for the user.
        val milkDir = File(context.filesDir, "milk-test").apply { mkdirs() }
        val name =
            context.assets.list("milk")?.firstOrNull { it.endsWith(".milk") }
                ?: error("no starter .milk shipped in assets/milk")
        val preset = File(milkDir, name)
        context.assets.open("milk/$name").use { input ->
            preset.outputStream().use { input.copyTo(it) }
        }
        val handle = MilkdropEngine.nativeCreate()
        assertNotEquals("projectm_create failed on this driver", 0L, handle)
        try {
            MilkdropEngine.nativeResize(handle, W, H)
            val dir = preset.parent ?: "/"
            MilkdropEngine.nativeSetTexturePaths(handle, arrayOf(dir, "$dir/textures"))
            MilkdropEngine.nativeLoadPreset(handle, preset.absolutePath, false)
            assertNull(
                "the starter preset $name failed to load",
                MilkdropEngine.nativeGetLastError(),
            )
            val lit = renderAndMeasure(handle)
            assertTrue(
                "preset $name rendered black: only ${"%.1f".format(lit * 100)}% of pixels lit " +
                    "after $FRAMES frames",
                lit > 0.05,
            )
            assertEquals(
                "the engine reported an error while rendering $name",
                null,
                MilkdropEngine.nativeGetLastError(),
            )
        } finally {
            MilkdropEngine.nativeDestroy(handle)
        }
    }
}
