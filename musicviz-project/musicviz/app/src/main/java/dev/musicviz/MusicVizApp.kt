package dev.musicviz

import android.app.Application
import java.io.File

/**
 * Captures any uncaught exception's full stack trace to
 * filesDir/crash-latest.txt before the process dies, so on-device crashes
 * can be copied from the in-app banner and reported verbatim instead of
 * being lost. The previous handler still runs (system crash dialog).
 */
class MusicVizApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // BuildConfig, not PackageManager: the version is compiled in,
                // and a binder call into a process that is mid-crash is one
                // more thing that can fail before the trace reaches the disk.
                File(filesDir, "crash-latest.txt").writeText(
                    "MusicViz ${BuildConfig.VERSION_NAME}\n" +
                        "Thread: ${thread.name}\n\n" +
                        android.util.Log.getStackTraceString(throwable),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
