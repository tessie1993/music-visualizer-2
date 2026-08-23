package dev.geode.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.geode.audio.AudioBus
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.PresetStore
import dev.geode.render.VisualizerRenderer
import dev.geode.ui.ThemeStore

class VisualizerWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VisualizerEngine()

    private inner class VisualizerEngine : Engine() {
        private var glView: WallpaperGlSurfaceView? = null
        private var renderer: VisualizerRenderer? = null
        private val idle = IdleFeatures()
        private var lastFrameMs = 0L
        private var feeder: Thread? = null

        @Volatile
        private var running = false

        private var surfaceAvailable = false
        private var visible = false

        private inner class WallpaperGlSurfaceView(
            context: Context,
        ) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun destroy() {
                super.onDetachedFromWindow()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            val engine = VisualizerRenderer(this@VisualizerWallpaperService)
            renderer = engine
            restoreLiveState(engine)
            engine.pcmProvider = { null }
            glView =
                WallpaperGlSurfaceView(this@VisualizerWallpaperService).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(engine)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
        }

        private fun restoreLiveState(engine: VisualizerRenderer) {
            val prefsFiles = GeodePrefsFiles(this@VisualizerWallpaperService)
            val prefs = prefsFiles.viz
            prefs.getString("live_state", null)?.let { json ->
                runCatching { PresetStore.fromJson(json) }.getOrNull()?.let { preset ->
                    engine.requestedSceneId = preset.sceneId
                    engine.sceneParams = preset.params
                }
            }
            prefs.getString("milk_path", null)?.let { path ->
                if (java.io.File(path).isFile) engine.loadMilkPreset(path)
            }
            engine.reducedMotion = ThemeStore(prefsFiles.general).loadGui().reducedMotion
        }

        private fun startFeeding(engine: VisualizerRenderer) {
            if (feeder != null) return
            AudioBus.addConsumer()
            running = true
            lastFrameMs = android.os.SystemClock.elapsedRealtime()
            feeder =
                Thread {
                    while (running) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
                        lastFrameMs = now
                        engine.features = AudioBus.features() ?: idle.tick(dt)
                        Thread.sleep(FEED_INTERVAL_MS)
                    }
                }.apply {
                    isDaemon = true
                    name = "geode-wallpaper-audio"
                    start()
                }
        }

        private fun stopFeeding() {
            if (feeder == null) return
            AudioBus.removeConsumer()
            running = false
            runCatching { feeder?.join(FEEDER_JOIN_MS) }
            feeder = null
        }

        private fun syncRunState() {
            val engine = renderer ?: return
            if (surfaceAvailable && visible) {
                glView?.onResume()
                startFeeding(engine)
            } else {
                glView?.onPause()
                stopFeeding()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            syncRunState()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAvailable = true
            syncRunState()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false
            syncRunState()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopFeeding()
            glView?.destroy()
            glView = null
            renderer = null
            super.onDestroy()
        }
    }

    private companion object {
        const val FEED_INTERVAL_MS = 16L

        const val FEEDER_JOIN_MS = 200L
    }
}
