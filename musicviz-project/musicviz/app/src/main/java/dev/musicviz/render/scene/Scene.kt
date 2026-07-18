package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures

/**
 * A visualization scene. All methods run on the GL thread. Implementations
 * must (re)create every GL resource in [init]; the context is lost on pause
 * and handles are never valid across lifecycles.
 */
interface Scene {
    val id: String

    fun init()

    /** Applies user parameters; default implementation ignores them. */
    fun setParams(params: SceneParams) {}

    fun resize(
        width: Int,
        height: Int,
    )

    fun update(
        features: AudioFeatures,
        dt: Float,
    )

    fun draw(timeSeconds: Float)

    fun release()
}
