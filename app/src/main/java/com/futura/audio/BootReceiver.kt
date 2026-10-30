package com.futura.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            // Reschedule persisted alarms on boot
        }
    }
}