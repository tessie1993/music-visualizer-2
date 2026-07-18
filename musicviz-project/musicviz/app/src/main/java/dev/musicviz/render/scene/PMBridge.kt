package dev.musicviz.render.scene

/**
 * JNI bridge to libprojectM (MilkDrop-compatible engine). arm64-v8a only;
 * [available] is false on other ABIs and the milkdrop scene is hidden.
 */
object PMBridge {
    val available: Boolean =
        try {
            System.loadLibrary("projectmjni")
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

    external fun nativeLoadPreset(
        handle: Long,
        path: String,
        smooth: Boolean,
    )

    external fun nativeSetBeatSensitivity(
        handle: Long,
        value: Float,
    )

    external fun nativeSetPresetLocked(
        handle: Long,
        locked: Boolean,
    )
}
