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

        /**
         * Distinguishes one feeder run from the next.
         *
         * A wallpaper toggles visibility every time the screen sleeps or the user switches app, so
         * over a day this runs hundreds of times. If a join ever times out, the old thread must be
         * able to tell that a newer run has taken over and exit — otherwise it keeps ticking, and
         * keeps the renderer and the service context alive, for the rest of the process.
         */
        @Volatile
        private var feedGeneration = 0

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
            val generation = ++feedGeneration
            running = true
            lastFrameMs = android.os.SystemClock.elapsedRealtime()
            feeder =
                Thread {
                    while (running && feedGeneration == generation) {
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
            val thread = feeder ?: return
            running = false
            feedGeneration++
            AudioBus.removeConsumer()
            runCatching { thread.join(FEEDER_JOIN_MS) }
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
