package com.example.wakeupmusic.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.wakeupmusic.PreflightReceiver

/**
 * Runs before the wake time, never at it. Makes sure every upcoming alarm's track is fully on
 * local disk and verified. Remote tracks are downloaded here (when a fetcher is registered).
 * The alarm itself never touches the network.
 */
object Preflight {

    /** Preflight is scheduled this long before the alarm, in addition to the run at set time. */
    const val LEAD_MS = 30 * 60 * 1000L

    data class Report(val alarmId: Long, val trackId: Long?, val ok: Boolean, val message: String)

    /** Blocking. Call from a background thread. */
    fun run(context: Context): List<Report> {
        val s = FuturaStorage.get(context)
        val now = System.currentTimeMillis()
        return s.alarms.enabled()
            .filter { it.nextTriggerAtMs > now }
            .map { check(s, it) }
    }

    private fun check(s: FuturaStorage, alarm: AlarmConfig): Report {
        val trackId = alarm.trackId ?: return Report(alarm.id, null, true, "Using device sounds")
        var track = s.tracks.get(trackId)
            ?: return Report(alarm.id, trackId, false, "Track record is missing. Fallback sound will play.")

        var result = s.trackStore.verify(track)
        if (result is Verification.Bad && track.sourceUri != null) {
            val fetcher = s.trackFetcher
            if (fetcher != null) {
                s.trackStore.download(track, fetcher)?.let { track = it }
                result = s.trackStore.verify(track)
            }
        }
        return when (result) {
            is Verification.Ok -> {
                s.tracks.setState(track.id, CacheState.VERIFIED)
                Report(alarm.id, trackId, true, "Track verified offline")
            }
            is Verification.Bad -> {
                s.tracks.setState(track.id, result.state)
                Report(alarm.id, trackId, false, "Track unusable (${result.reason}). Fallback sound will play.")
            }
        }
    }

    /** Schedules a preflight [LEAD_MS] before [triggerAtMs]. Skipped if that moment has passed. */
    fun schedule(context: Context, triggerAtMs: Long) {
        val at = triggerAtMs - LEAD_MS
        if (at <= System.currentTimeMillis() + 60_000L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact on purpose: needs no exact-alarm permission and the timing is not critical.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1003, Intent(context, PreflightReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
