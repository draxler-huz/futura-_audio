package com.example.wakeupmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Fired by the alarm clock. It starts [AlarmService], which rings in the foreground. The alarm keeps going after the
 * user leaves the alarm screen, because the service, not the activity, owns the sound.
 *
 * Android allows this foreground start because the broadcast comes from an exact alarm (setAlarmClock), which is
 * one of the exemptions from background-start limits.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_START),
        )
    }
}
