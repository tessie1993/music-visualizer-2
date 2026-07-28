package dev.musicviz.render

import android.content.Context
import android.opengl.GLSurfaceView

/**
 * GLSurfaceView configured for ES 3.0 with a continuous render loop.
 *
 * The EGL context is deliberately NOT preserved on pause: the app's contract
 * is that everything is recreated in onSurfaceCreated (context-loss rule),
 * and preserved-context resume is a known source of device-specific GL
 * hangs. Scenes restore their own state (including the last .milk preset)
 * after recreation.
 */
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
