package com.example.wakeupmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The alarm screen plays the audio, so the channel itself is silent
        nm.createNotificationChannel(
            NotificationChannel("alarm", "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )

        val fullScreen = PendingIntent.getActivity(
            context, 2001,
            Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // The full-screen intent is the path that shows the alarm over the lock screen
        val notification = Notification.Builder(context, "alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wake-up music")
            .setContentText("Your alarm is ringing")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .build()

        nm.notify(1, notification)
    }
}
