package dev.musicviz.render.scene

/**
 * JNI bridge to libprojectM (MilkDrop-compatible engine, v4.1.7 + FBO render
 * backport). arm64-v8a only; [available] is false on other ABIs and the
 * milkdrop scene is hidden.
 *
 * ## Why this one class is not in `dev.geode`
 *
 * A JNI symbol is named after the fully-qualified Java class that declares it:
 * `nativeCreate` here resolves to `Java_dev_musicviz_render_scene_PMBridge_nativeCreate`
 * in `libprojectmjni.so`. That library is a committed prebuilt binary, so a
 * Kotlin package rename does not rename its exports — it just stops finding
 * them.
 *
 * The rebrand moved this class to `dev.geode.render.scene` and MilkDrop died on
 * the spot: every launch of the scene threw `UnsatisfiedLinkError` off the GL
 * thread, with nothing in the UI to explain it, because the crash happens
 * before any of this object's own error reporting can run.
 *
 * So the package is pinned to the ABI of the shipped library rather than to the
 * app's identity. The applicationId is `dev.geode`; this is a linkage detail,
 * and `JniAbiTest` compares these two names against the symbols the `.so`
 * actually exports so the pairing cannot drift again unnoticed. Moving this
 * class — or renaming any `external fun` below — requires rebuilding the native
 * library to match.
 */
object PMBridge {
    /**
     * Whether the native engine can actually be called.
     *
     * This used to be "did `System.loadLibrary` succeed", which is a weaker
     * question than it looks: after the rebrand the library loaded perfectly
     * and every symbol lookup failed, so `available` said yes and the first
     * `nativeCreate` took down the GL thread. Loading a library and being able
     * to call into it are two different facts.
     *
     * So a symbol is resolved here as well. [nativeGetLastError] is the probe
     * because it takes no handle and allocates nothing — calling it costs a
     * string read, and if the binding is wrong it throws `UnsatisfiedLinkError`
     * right here, where the answer is false and the scene is simply hidden.
     */
    val available: Boolean =
        try {
            System.loadLibrary("projectmjni")
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

    external fun nativeRender(handle: Long)

    external fun nativeRenderToFbo(
        handle: Long,
        fbo: Int,
    )

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
