package dev.geode.render.scene

/**
 * The JNI surface of the projectM 4.2 bridge (`tools/projectm_jni.c`).
 *
 * The symbol names are derived from this class's fully qualified name, which is why the rebuild
 * renamed it: a stale `libmilkdropjni.so` from the previous, framebuffer-0 integration exports
 * `Java_dev_geode_render_scene_MilkdropEngine_*` and resolves NOTHING here. That is deliberate.
 * A half-matching bridge is the failure mode that shipped a black MilkDrop twice; with the names
 * moved, an old blob makes [available] false and the scene simply does not offer itself, which
 * is a visible, diagnosable state instead of a silently wrong one.
 *
 * ---- one thing to know about projectM 4.2 on GLES ----------------------------
 *
 * `GladLoader::CheckGLRequirements()` asks for GLES 3.2 / GLSL ES 3.20, inside `projectm_create()`
 * and ahead of the laxer `CheckGLSLVersion()`. The app's own floor is ES 3.0
 * (`android:glEsVersion="0x00030000"`, every shader `#version 300 es`), so on an ES 3.0/3.1
 * device [nativeCreate] returns 0 and [ProjectMScene] reports the initialize failure it already
 * handles. That is the reporting path, not a crash. See `tools/build-projectm.md`.
 *
 * Every native function touches GL and must be called on the GL thread.
 */
object ProjectMEngine {
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

    /**
     * Sets the size projectM renders at, which must be the size of the framebuffer passed to
     * [nativeRenderToFbo] - upstream's `RenderFrame` derives its `glViewport` from this before
     * compositing, so a value that disagrees with the target crops or letterboxes the frame.
     */
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

    /**
     * Renders one frame straight into [fbo].
     *
     * This is the whole point of the 4.2 rebuild. projectM binds [fbo] itself and leaves it
     * bound with a viewport sized from [nativeResize]; the caller restores both.
     */
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
