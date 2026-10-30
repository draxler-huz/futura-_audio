package com.futura.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val wakeLock: android.os.PowerManager.WakeLock =
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FuturaAudio:AlarmWakeLock")
        wakeLock.acquire(6000L)

        val startIntent = Intent(context, AlarmService::class.java)
        context.startForegroundService(startIntent)
        context.startService(startIntent)
    }
}