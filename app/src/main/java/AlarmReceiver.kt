package com.example.wakeupmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Secure a temporary WakeLock to keep the CPU awake during handoff
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WakeUpMusic:ReceiverWakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L) // 10 minutes max safeguard

        // Hand over execution to the Foreground Audio Service
        val serviceIntent = Intent(context, AlarmAudioService::class.java).apply {
            putExtras(intent)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}