package dev.musicviz.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The foreground service the platform requires before one app may look at
 * another app's audio.
 *
 * It exists for exactly two reasons, and does nothing else:
 *
 *  1. `getMediaProjection` must be called while a foreground service with
 *     `mediaProjection` type is already running. On Android 14 that is
 *     enforced with a `SecurityException`; on 10-13 the capture is torn down
 *     shortly after the app leaves the foreground without one.
 *  2. Screen and audio capture must be visible to the user for as long as it
 *     lasts. The notification is not decoration - it is the honest statement
 *     that this app can currently hear the device, and tapping it stops.
 *
 * The projection itself is published on [MediaProjectionHolder] rather than
 * bound to: the reader is a ViewModel that owns the ring buffer, and a bound
 * connection would add a lifecycle to get wrong for a value that is either
 * present or absent.
 *
 * Not annotated `@RequiresApi`: a manifest-declared component is instantiated
 * by the system on every API level the app runs on, so the version gate has to
 * live in [start] and in the branches below rather than on the class.
 */
class PlaybackCaptureService : Service() {
    private var projection: MediaProjection? = null

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                // The user revoked capture from the system UI. Tear down here
                // rather than waiting for the app to notice a dead projection.
                MediaProjectionHolder.publish(null)
                stopSelf()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.projectionData(it) }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp =
            runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // registerCallback is mandatory since Android 14; the handler must be
        // one with a live looper, and the main one always is.
        mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = mp
        MediaProjectionHolder.publish(mp)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaProjectionHolder.publish(null)
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Visualizing other apps",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while MusicViz is reading the audio another app is playing."
                    setShowBadge(false)
                },
            )
        }
        val open =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, dev.musicviz.ui.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            android.app.PendingIntent.getService(
                this,
                1,
                Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle("Visualizing other apps")
                .setContentText("MusicViz is reading the audio playing on this device.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Kept out of the class body so the deprecated getter has one home. */
    private object IntentCompat {
        @Suppress("DEPRECATION")
        fun projectionData(intent: Intent): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
    }

    companion object {
        private const val CHANNEL_ID = "musicviz-capture"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "dev.musicviz.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** Starts capture with the consent the user just gave. */
        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val intent =
                Intent(context, PlaybackCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackCaptureService::class.java))
        }
    }
}

/**
 * The live [MediaProjection], published for whoever needs it.
 *
 * A projection is a process-wide capability with exactly one owner (the
 * service) and one consumer (the ViewModel that holds the ring buffer), and
 * the two have unrelated lifecycles - the same shape as [AudioBus], and
 * solved the same way rather than with a binder.
 */
object MediaProjectionHolder {
    private val _projection = MutableStateFlow<MediaProjection?>(null)

    /** Non-null while the user has granted capture and the service is up. */
    val projection: StateFlow<MediaProjection?> = _projection

    fun publish(projection: MediaProjection?) {
        _projection.value = projection
    }
}
