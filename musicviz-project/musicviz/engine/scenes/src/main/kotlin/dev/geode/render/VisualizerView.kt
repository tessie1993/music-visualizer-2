package dev.geode.render

import android.content.Context
import android.opengl.GLSurfaceView

class VisualizerView(
    context: Context,
) : GLSurfaceView(context) {
    val visualizerRenderer: VisualizerRenderer = VisualizerRenderer(context)

    init {
        setEGLContextClientVersion(3)
        // Asked for before the renderer is attached: the host stops this view whenever it goes
        // invisible, and rebuilding every scene, shader and FBO on each resume would cost a visible
        // black flash. The driver is free to refuse, which is why onSurfaceCreated stays re-entrant.
        preserveEGLContextOnPause = true
        setRenderer(visualizerRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
