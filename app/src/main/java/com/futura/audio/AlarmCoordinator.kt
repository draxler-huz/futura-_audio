package com.futura.audio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.os.Build

class AlarmCoordinator : Service() {

    private val channelId = "futura_audio_alarm_camouflage"
    private val foregroundService = ForegroundService()
    private val notificationManager = NotificationManagerCompat.from(this)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // Parse intent for alarm parameters
        val alarmTime = if (intent != null) {
            intent.getLongExtra("alarm_time", System.currentTimeMillis())
        } else {
            System.currentTimeMillis()
        }
        
        // Schedule the alarm
        alarmScheduler.scheduleExactAlarm(
            this,
            alarmTime
        )
        
        // Start foreground service with camouflage notification
        startForegroundService()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "FUTURA Audio Alarm",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camouflage clock interface for audio alarms"
                lightColor = android.graphics.Color.GREEN
                importance = android.app.NotificationManager.IMPORTANCE_LOW
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(
            this, channelId
        ).setContentTitle("FUTURA AUDIO")
            .setContentText("Audio engine active")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(android.graphics.Color.GREEN)
            .build()

        startForeground(1001, notification)
        foregroundService.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        notificationManager.cancel(1001)
        alarmScheduler.cancelAlarm(this)
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    // Companion for easy starts
    companion object {
        fun start(context: Context, alarmTime: Long) {
            val intent = Intent(context, AlarmCoordinator::class.java).apply {
                putExtra("alarm_time", alarmTime)
            }
            context.startForegroundService(intent)
        }
    }
}