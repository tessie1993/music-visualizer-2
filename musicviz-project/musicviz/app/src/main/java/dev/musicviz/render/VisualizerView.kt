package dev.musicviz.render

import android.content.Context
import android.opengl.GLSurfaceView

/** GLSurfaceView configured for ES 3.0 with a continuous render loop. */
class VisualizerView(context: Context) : GLSurfaceView(context) {
    val visualizerRenderer: VisualizerRenderer = VisualizerRenderer(context)

    init {
        setEGLContextClientVersion(3)
        setRenderer(visualizerRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
