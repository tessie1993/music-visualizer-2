package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures

/**
 * A visualization scene. All methods run on the GL thread. Implementations
 * must (re)create every GL resource in [init]; the context is lost on pause
 * and handles are never valid across lifecycles.
 */
interface Scene {
    val id: String

    /**
     * True when this scene draws real 3D geometry and needs a depth buffer on
     * whatever target it is rendered into.
     *
     * The engine renders scenes into a plain colour FBO, which is all a
     * fullscreen fragment pass or a point-sprite cloud has ever needed. A
     * scene that overlaps itself in depth ([CymaticsScene]'s plate) does need
     * one, and it is the render target's owner - `VisualizerRenderer` live,
     * `FxCompositor` for exports - that has to attach it, so the requirement
     * has to be visible from the interface. Attaching it for every scene would
     * cost megabytes of memory that nothing else reads.
     */
    val needsDepth: Boolean get() = false

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
