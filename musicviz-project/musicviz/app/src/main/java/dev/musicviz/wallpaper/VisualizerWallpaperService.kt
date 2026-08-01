package dev.musicviz.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.musicviz.audio.AudioBus
import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.ui.PresetStore
import dev.musicviz.ui.ThemeStore

/**
 * The visualizer as a live wallpaper.
 *
 * Runs the SAME [VisualizerRenderer] the app does, on the style and parameters
 * the app was last left showing, so setting a wallpaper is "keep what I built"
 * rather than a second, parallel set of visuals to configure. It reacts to
 * whatever the app is playing through [AudioBus], and breathes on its own
 * ([IdleFeatures]) the rest of the time - which is most of the time, because a
 * wallpaper is on screen for hours with the music app closed.
 *
 * GL comes from a [GLSurfaceView] pointed at the wallpaper's own surface
 * rather than from a hand-rolled EGL setup. A wallpaper Engine hands out a
 * SurfaceHolder and nothing else, so the usual approach is to bootstrap
 * EGLDisplay/Context/Surface and a render thread by hand - several hundred
 * lines of lifecycle that this renderer's context-loss contract already
 * assumes GLSurfaceView is handling correctly. Overriding [getHolder] makes
 * the view render into the Engine's surface and keeps all of that.
 */
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
         * The two conditions everything this Engine runs depends on.
         *
         * An Engine outlives its surface - a configuration change, the display
         * cycling off and back on, applying a wallpaper that was being
         * previewed - and it spends most of its life invisible behind another
         * app. The two events arrive independently and in either order, so
         * they are held as state and reconciled in one place ([syncRunState])
         * rather than each call site starting and stopping things itself.
         */
        private var surfaceAvailable = false
        private var visible = false

        /**
         * A GLSurfaceView that draws into the wallpaper Engine's surface.
         *
         * The whole trick is [getHolder]: GLSurfaceView asks its holder for a
         * surface, so pointing that at the Engine's holder makes its render
         * thread, EGL setup, pause/resume and context recreation all apply to
         * the wallpaper - which is exactly the machinery this renderer is
         * written against.
         */
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
            // The wallpaper shows what the app was last left showing. Loaded
            // from the app's own live-state store, so there is one place a
            // style and its parameters live rather than a second set to keep
            // in step.
            restoreLiveState(engine)
            // MilkDrop's raw-PCM path needs a live tap, which a wallpaper has
            // no access to; with no provider the scene drives itself from the
            // waveform in the features instead (the export path's behaviour).
            engine.pcmProvider = { null }
            glView =
                WallpaperGlSurfaceView(this@VisualizerWallpaperService).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(engine)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            // Nothing starts here: the surface does not exist yet, and the
            // Engine is not visible yet. Both arrive as their own callbacks,
            // and both go through [syncRunState].
        }

        /**
         * Reads the style, parameters and safety limits the app persisted.
         *
         * Read once at creation rather than watched: a wallpaper Engine is
         * recreated whenever the wallpaper is re-selected or the device
         * reboots, and polling shared preferences on a render thread to catch
         * a slider move the user made in another app is a lot of machinery for
         * a change they will see next time the wallpaper starts anyway.
         */
        private fun restoreLiveState(engine: VisualizerRenderer) {
            val prefs = getSharedPreferences("musicviz-viz", Context.MODE_PRIVATE)
            prefs.getString("live_state", null)?.let { json ->
                runCatching { PresetStore.fromJson(json) }.getOrNull()?.let { preset ->
                    engine.requestedSceneId = preset.sceneId
                    engine.sceneParams = preset.params
                }
            }
            // The .milk the app was last showing, from the same store: on the
            // milkdrop style the preset file IS the picture, so a wallpaper
            // that restored only the style and its parameters came up on
            // projectM's idle "M" logo instead of the visual it was set from.
            prefs.getString("milk_path", null)?.let { path ->
                if (java.io.File(path).isFile) engine.loadMilkPreset(path)
            }
            // The photosensitivity limits follow the user's setting here too.
            // A wallpaper is seen more often and less deliberately than the
            // app, so it is the last place those should quietly not apply.
            engine.safety = ThemeStore(this@VisualizerWallpaperService).loadGui().safety
        }

        /**
         * Feeds the renderer audio features at the analyzer's own rate.
         *
         * Its own thread rather than the GL thread: the renderer reads
         * `features` as a volatile field from whatever thread draws, and the
         * source has to keep ticking at a steady rate whether or not frames
         * are being produced, so that the idle motion stays smooth when the
         * system throttles the wallpaper.
         *
         * A no-op while a feeder is already alive: every surface and
         * visibility change funnels through [syncRunState], and several of
         * them land on "should be running" in a row, which must not leave two
         * threads writing `features` and `lastFrameMs` at once.
         */
        private fun startFeeding(engine: VisualizerRenderer) {
            if (feeder != null) return
            running = true
            lastFrameMs = android.os.SystemClock.elapsedRealtime()
            feeder =
                Thread {
                    while (running) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
                        lastFrameMs = now
                        // The app's real analysis when it is playing, our own
                        // gentle motion when it is not.
                        engine.features = AudioBus.features() ?: idle.tick(dt)
                        Thread.sleep(FEED_INTERVAL_MS)
                    }
                }.apply {
                    isDaemon = true
                    name = "musicviz-wallpaper-audio"
                    start()
                }
        }

        /**
         * Stops the feeder and waits for it, so a restart can never overlap
         * the thread it replaces.
         *
         * The loop tests `running` once per [FEED_INTERVAL_MS], so the wait is
         * that long in practice; the timeout is only there to keep a wedged
         * thread from holding up the main thread on the way out.
         */
        private fun stopFeeding() {
            running = false
            runCatching { feeder?.join(FEEDER_JOIN_MS) }
            feeder = null
        }

        /**
         * Brings both halves of the Engine in line with the state it is in.
         *
         * Feeding and drawing have exactly the same precondition - a surface
         * to draw on and a user who can see it - so they start and stop
         * together. Stopping the feeder is the half that was missing: nothing
         * reads `features` while the wallpaper is behind an app, and that is
         * the bulk of its life, so leaving the thread waking 62 times a second
         * to synthesize a spectrum for nobody was pure drain. Stopping the GL
         * view is also what releases and rebuilds the EGL context the renderer
         * expects to lose.
         */
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

        /**
         * The surface comes BACK, more than once, under a live Engine.
         *
         * Without this the Engine survives a surface destroy with its feeder
         * thread gone for good and its GL thread still paused: every
         * feature-driven motion frozen on the last idle frame, and nothing but
         * re-selecting the wallpaper to recover it.
         */
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
        /**
         * Feed period. Matches the app analyzer's ~62 Hz hop, so a scene sees
         * features arriving at the rate it was tuned against; faster would
         * only re-publish values that have not changed.
         */
        const val FEED_INTERVAL_MS = 16L

        /** Cap on how long stopping the feeder waits for it to notice. */
        const val FEEDER_JOIN_MS = 200L
    }
}
