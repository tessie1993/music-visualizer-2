package dev.geode.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a video render alive while the app is not in front of the user, and
 * shows how far it has got.
 *
 * The render itself lives in [ExportRun]'s scope, not here. This service exists
 * for the one thing a coroutine cannot do on its own: tell Android the process
 * is doing visible work and must not be reclaimed. Owning the render as well
 * would mean moving an EGL context, a scene and its parameters across a service
 * boundary for no gain.
 *
 * ## The foreground service type
 *
 * `mediaProcessing` — added in Android 15 for exactly this, "time-consuming
 * operations on media assets, like converting media to different formats". It
 * carries a budget of six hours in every twenty-four across all of an app's
 * mediaProcessing services, and when that runs out the system calls
 * [onTimeout], after which there are a few seconds to stop before an ANR. A
 * render that hits a six-hour ceiling is not a render anyone is waiting for, so
 * the response is to cancel it and go, rather than to try to hold on.
 *
 * Below Android 14 the type is not supplied at all, which is what those
 * versions expect.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Posted before anything else can fail: a service started with
        // startForegroundService() that stops without ever reaching
        // startForeground() dies with RemoteServiceException.
        runCatching { startForegroundNotification(ExportRun.state.value) }
        watcher =
            scope.launch {
                ExportRun.state.collectLatest { state ->
                    if (!state.running) {
                        stopSelf()
                        return@collectLatest
                    }
                    runCatching { startForegroundNotification(state) }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Not sticky: a render cannot be resumed from nothing, so a restarted
        // service with no export behind it would be a notification standing
        // over no work.
        return START_NOT_STICKY
    }

    /**
     * The mediaProcessing budget is spent. Cancel the render and stop —
     * there are only seconds before this becomes an ANR.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        ExportRun.finish()
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(state: ExportRun.State) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(dev.geode.R.string.export_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(dev.geode.R.string.export_channel_description)
                    setShowBadge(false)
                },
            )
        }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, dev.geode.ui.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(dev.geode.R.string.export_notification_title))
                .setContentText(state.label.ifBlank { getString(dev.geode.R.string.export_notification_text) })
                .setSmallIcon(dev.geode.R.drawable.ic_stat_capture)
                .setOngoing(true)
                .setContentIntent(open)
        val progress = state.progress
        if (progress == null) {
            // Indeterminate until the length is known: a determinate bar frozen
            // at 0 reads as a hang, which is the complaint the Studio's own
            // fast-path progress bar already draws.
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, foregroundType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            // 14 has no mediaProcessing type yet; dataSync is the one it
            // accepts for long-running local work.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    companion object {
        private const val CHANNEL_ID = "geode-export"
        private const val NOTIFICATION_ID = 4711
        private const val PROGRESS_MAX = 1000

        /**
         * Starts the service for a render that is beginning.
         *
         * Call [ExportRun.begin] first: the service reads its first
         * notification straight out of that state, and a service that starts
         * against an idle run would stop itself immediately.
         *
         * The failure is swallowed for the reason the capture service swallows
         * its own: the only way to reach it is a start refused because the app
         * is in the background, and an export only ever begins from a tap.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, ExportService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
