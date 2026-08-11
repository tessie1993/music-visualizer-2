package dev.musicviz

import android.app.Application
import java.io.File

/**
 * Captures any uncaught exception's full stack trace to
 * filesDir/crash-latest.txt before the process dies, so on-device crashes
 * can be copied from the in-app banner and reported verbatim instead of
 * being lost. The previous handler still runs (system crash dialog).
 *
 * ## The threading contract
 *
 * Four lanes, and every file belongs to one:
 *
 *  - **Main**: the player, UI state flows and everything Compose touches.
 *    No disk - StrictMode below logs any regression in debug builds.
 *  - **The store writer** (`musicviz-stores`, plus [dev.musicviz.data.HistoryStore]'s
 *    own lane): every file mutation and the re-list that follows it, ordered.
 *    Store APIs carry `@WorkerThread` so a main-thread call is visible at the
 *    call site.
 *  - **Dispatchers.Default**: analysis and the export render loop.
 *  - **The GL thread(s)**: renderer and wallpaper engine; and the capture
 *    pumps' own worker threads ([dev.musicviz.audio.AudioCapturePump]), which
 *    alone touch their AudioRecords.
 */
class MusicVizApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Non-fatal failures echo into logcat; the ring itself stays pure JVM
        // so the headless store tests can exercise the callers.
        RingLog.echo = { tag, line -> android.util.Log.w(tag, line) }
        if (BuildConfig.DEBUG) {
            // The permanent guard on "no disk on the main thread": every store
            // mutation is supposed to ride its writer lane, and a regression
            // shows up in logcat instead of waiting for a slow-flash ANR
            // report. Log-only - the deliberate first-frame prefs read in
            // restoreVizState is a known, accepted hit.
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // BuildConfig, not PackageManager: the version is compiled in,
                // and a binder call into a process that is mid-crash is one
                // more thing that can fail before the trace reaches the disk.
                File(filesDir, "crash-latest.txt").writeText(
                    "MusicViz ${BuildConfig.VERSION_NAME}\n" +
                        "Thread: ${thread.name}\n\n" +
                        android.util.Log.getStackTraceString(throwable) +
                        // The non-fatal trail that led here is usually the
                        // half of the story the stack trace does not tell.
                        "\n\n-- recent non-fatal failures --\n" +
                        RingLog.dump(),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
