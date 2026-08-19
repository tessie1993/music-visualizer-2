package dev.geode.render.scene

/**
 * JNI bridge to libprojectM (MilkDrop-compatible engine, stock v4.1.7).
 * arm64-v8a only; [available] is false on other ABIs and the MilkDrop scene
 * is hidden.
 *
 * ## The contract with the shipped binaries
 *
 * A JNI symbol is named after the fully-qualified Java class that declares
 * it: `nativeCreate` here resolves to
 * `Java_dev_geode_render_scene_MilkdropEngine_nativeCreate` in
 * `libmilkdropjni.so`, a committed prebuilt built by CI from
 * `tools/milkdrop_jni.c`. Renaming this class, its package, or any
 * `external fun` does not rename those exports — it just stops finding them,
 * as an `UnsatisfiedLinkError` on the GL thread with no UI to explain it.
 * `JniAbiTest` compares this file against the symbols the `.so` actually
 * exports so the pairing cannot drift unnoticed; a rename requires
 * rebuilding the native library (`.github/workflows/native-libs.yml`).
 *
 * ## Why there is no render-to-FBO call
 *
 * The engine is built STOCK, with no patches: `nativeRender` ends its frame
 * on the DEFAULT framebuffer, where upstream's `glDrawBuffers(GL_BACK)` is
 * legal, and [MilkdropScene] copies the result off framebuffer 0 into its
 * own texture. The previous integration patched the engine to render into a
 * caller framebuffer object, and a stale or incomplete patch shipped a
 * permanently black MilkDrop twice; the stock engine plus a copy has no
 * patch to go stale.
 */
object MilkdropEngine {
    /**
     * Whether the native engine can actually be called.
     *
     * Loading a library and being able to call into it are two different
     * facts: after a package rename the library loads perfectly and every
     * symbol lookup fails. So a symbol is resolved here as well —
     * [nativeGetLastError] is the probe because it takes no handle and
     * allocates nothing; if the binding is wrong it throws
     * `UnsatisfiedLinkError` right here, where the answer is false and the
     * scene is simply hidden.
     */
    val available: Boolean =
        try {
            System.loadLibrary("milkdropjni")
            nativeGetLastError()
            true
        } catch (t: Throwable) {
            false
        }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeResize(
        handle: Long,
        width: Int,
        height: Int,
    )

    external fun nativeAddPcmMono(
        handle: Long,
        samples: FloatArray,
        count: Int,
    )

    /** Renders one frame onto the DEFAULT framebuffer at the resized size. */
    external fun nativeRender(handle: Long)

    external fun nativeSetTexturePaths(
        handle: Long,
        dirs: Array<String>,
    )

    external fun nativeLoadPreset(
        handle: Long,
        path: String,
        smooth: Boolean,
    )

    external fun nativeGetLastError(): String?

    external fun nativeSetBeatSensitivity(
        handle: Long,
        value: Float,
    )

    external fun nativeSetPresetLocked(
        handle: Long,
        locked: Boolean,
    )
}
