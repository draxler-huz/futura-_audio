package com.example.wakeupmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.app.ServiceCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The alarm, as a foreground service (Stage 2).
 *
 * It starts from [AlarmReceiver] and owns the sound until the alarm is dismissed. The alarm screen is only a view of
 * this service. Closing it, leaving the app or pressing Home does not stop the alarm. The notification stays until
 * the user dismisses the alarm, from the notification or from the screen.
 *
 * Commands: ACTION_START (from the receiver), ACTION_TOGGLE, ACTION_SKIP and ACTION_DISMISS (from the screen or the
 * notification). The service is not sticky. If the process is killed, the alarm does not restart by itself.
 */
class AlarmService : Service() {

    private var playback: AlarmPlayback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging()
            ACTION_TOGGLE -> playback?.togglePause()
            ACTION_SKIP -> playback?.skip()
            ACTION_DISMISS -> endRinging()
        }
        // A command that reaches a service that is not ringing (for example a late tap) must not leave it running.
        if (playback == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startRinging() {
        if (playback != null) return   // already ringing. A second alarm does not restart the sound.
        ensureChannel()
        // Publish before the foreground start, so an alarm screen opened by the full-screen intent sees "ringing".
        publish(AlarmPlayback.Status(ringing = true, label = "Starting...", playing = false))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Your alarm is ringing"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        val p = AlarmPlayback(this) { status -> onPlaybackStatus(status) }
        playback = p
        p.start()
    }

    private fun endRinging() {
        val p = playback ?: return
        playback = null
        p.stop()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onPlaybackStatus(status: AlarmPlayback.Status) {
        publish(status)
        if (status.ringing && playback != null) {
            val text = if (status.playing) "Playing: ${status.label}" else "Paused: ${status.label}"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getService(
            this, REQ_DISMISS,
            Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wake-up music")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dismiss",
                    dismiss,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // The sound comes from the playback engine, so the channel itself is silent.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                },
            )
        }
    }

    override fun onDestroy() {
        // Destroyed without a dismiss (for example the system stopped the service). Release the audio anyway.
        playback?.stop()
        playback = null
        publish(AlarmPlayback.Status(ringing = false, label = "", playing = false))
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.wakeupmusic.alarm.START"
        const val ACTION_TOGGLE = "com.example.wakeupmusic.alarm.TOGGLE"
        const val ACTION_SKIP = "com.example.wakeupmusic.alarm.SKIP"
        const val ACTION_DISMISS = "com.example.wakeupmusic.alarm.DISMISS"

        const val CHANNEL_ID = "alarm"
        const val NOTIFICATION_ID = 1
        private const val REQ_OPEN = 2001
        private const val REQ_DISMISS = 2002

        @Volatile var status: AlarmPlayback.Status = AlarmPlayback.Status(ringing = false, label = "", playing = false)
            private set

        private val listeners = CopyOnWriteArrayList<(AlarmPlayback.Status) -> Unit>()

        /** Adds a listener. Listeners are called on the main thread. */
        fun addListener(listener: (AlarmPlayback.Status) -> Unit) {
            listeners += listener
        }

        fun removeListener(listener: (AlarmPlayback.Status) -> Unit) {
            listeners -= listener
        }

        private fun publish(s: AlarmPlayback.Status) {
            status = s
            listeners.forEach { it(s) }
        }
    }
}
