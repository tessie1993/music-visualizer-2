package dev.musicviz.render.scene

import android.opengl.GLES30
import dev.musicviz.analysis.AudioFeatures

/**
 * MilkDrop-compatible scene backed by libprojectM. Renders .milk presets
 * (the BeatDrop preset format). PCM is fed from [pcmProvider] when playing
 * live; during offline export the per-frame waveform snapshot is used, which
 * approximates the live look.
 */
class ProjectMScene(
    /** Returns the newest mono PCM window, or null if none. Called on the GL thread. */
    private val pcmProvider: () -> FloatArray?,
    /** Preset to load as soon as the scene renders; survives scene recreation. */
    initialPresetPath: String? = null,
) : Scene {
    override val id: String = SceneIds.MILKDROP

    private var handle: Long = 0
    private var width = 1
    private var height = 1

    private class PendingPreset(val path: String, val smooth: Boolean)

    @Volatile
    private var pendingPreset: PendingPreset? = initialPresetPath?.let { PendingPreset(it, smooth = false) }

    @Volatile
    private var pendingBeatSensitivity: Float? = null

    override fun setParams(params: SceneParams) {
        pendingBeatSensitivity = params.intensity
    }

    /**
     * Thread-safe: queues a .milk preset file to load on the GL thread.
     * [smooth] = false does a hard cut so the preset is visible immediately.
     */
    fun queuePreset(
        path: String,
        smooth: Boolean = false,
    ) {
        pendingPreset = PendingPreset(path, smooth)
    }

    override fun init() {
        release()
        handle = PMBridge.nativeCreate()
        if (handle != 0L) PMBridge.nativeResize(handle, width, height)
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        if (handle != 0L) PMBridge.nativeResize(handle, width, height)
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        if (handle == 0L) return
        val pcm = pcmProvider() ?: features.waveform
        PMBridge.nativeAddPcmMono(handle, pcm, pcm.size)
    }

    override fun draw(timeSeconds: Float) {
        if (handle == 0L) return
        pendingPreset?.let { preset ->
            pendingPreset = null
            PMBridge.nativeLoadPreset(handle, preset.path, preset.smooth)
            // Lock so projectM's preset-duration timer can't cut back to idle.
            PMBridge.nativeSetPresetLocked(handle, true)
        }
        pendingBeatSensitivity?.let { value ->
            pendingBeatSensitivity = null
            PMBridge.nativeSetBeatSensitivity(handle, value)
        }
        PMBridge.nativeRender(handle)
        // projectM leaves its own GL state bound; restore defaults our scenes assume.
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    override fun release() {
        if (handle != 0L) {
            PMBridge.nativeDestroy(handle)
            handle = 0
        }
    }
}
