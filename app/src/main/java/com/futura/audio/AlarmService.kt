package com.futura.audio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.futura.engine.AudioEngine

class AlarmService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "futura_audio_alarm"
        val notification = android.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Alarm")
            .setContentText("Audio alarm triggered")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(false)
            .build()

        startForeground(1002, notification)

        AudioEngine.Companion.getInstance().playTone(440.0, 1000L)

        stopForeground(true)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}