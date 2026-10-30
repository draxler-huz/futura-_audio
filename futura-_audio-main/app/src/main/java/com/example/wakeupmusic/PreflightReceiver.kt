package com.example.wakeupmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.wakeupmusic.data.Preflight

/** Fires shortly before the alarm and re-verifies the track while the device is idle and unhurried. */
class PreflightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Preflight.run(context.applicationContext)
            } catch (_: Throwable) {
                // Nothing to do. The alarm falls back to the embedded sound if the track is bad.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
