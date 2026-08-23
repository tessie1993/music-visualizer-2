package dev.geode.render

import android.content.Context
import android.opengl.GLSurfaceView

class VisualizerView(
    context: Context,
) : GLSurfaceView(context) {
    val visualizerRenderer: VisualizerRenderer = VisualizerRenderer(context)

    init {
        setEGLContextClientVersion(3)
        setRenderer(visualizerRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
