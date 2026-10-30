package com.example.wakeupmusic.audio

import android.content.Context
import com.example.wakeupmusic.data.AlarmDao
import com.example.wakeupmusic.data.FuturaStorage
import com.example.wakeupmusic.data.TrackMetadata
import com.example.wakeupmusic.data.Verification
import java.io.File
import kotlin.math.pow

/** Decides at alarm time what to play. Reads only local storage. Never throws. */
object PlaybackResolver {

    sealed class Resolved {
        /** No track chosen: the Stage 0 behaviour (built-in device sounds). */
        object DeviceSounds : Resolved()

        /** A verified local track. */
        data class UserTrack(val track: TrackMetadata, val file: File) : Resolved()

        /** The chosen track cannot be used. The embedded sound plays immediately. */
        data class Fallback(val reason: String) : Resolved()
    }

    // Above this size the full hash is skipped at alarm time (pre-download already did it).
    private const val ALARM_TIME_HASH_LIMIT = 64L * 1024 * 1024

    fun resolve(context: Context): Resolved = try {
        val s = FuturaStorage.get(context)
        val trackId = s.alarms.get(AlarmDao.PRIMARY_ID)?.trackId
        if (trackId == null) {
            Resolved.DeviceSounds
        } else {
            val track = s.tracks.get(trackId)
            if (track == null) {
                Resolved.Fallback("track record is missing")
            } else {
                when (val v = s.trackStore.verify(track, ALARM_TIME_HASH_LIMIT)) {
                    is Verification.Ok -> Resolved.UserTrack(track, File(track.localPath))
                    is Verification.Bad -> {
                        s.tracks.setState(track.id, v.state)
                        Resolved.Fallback(v.reason)
                    }
                }
            }
        }
    } catch (e: Throwable) {
        Resolved.Fallback("storage error")
    }

    /** Marks a track corrupt after the decoder rejected it at alarm time. */
    fun reportDecodeFailure(context: Context, track: TrackMetadata) {
        runCatching {
            FuturaStorage.get(context).tracks.setState(track.id, com.example.wakeupmusic.data.CacheState.CORRUPT)
        }
    }

    /**
     * Applies the track's gain adjustment to the user's volume. A boost can never push the level
     * above 80% unless the user's own volume setting is already higher.
     */
    fun effectiveVolume(userVolume: Float, gainDb: Float): Float {
        val scaled = userVolume * 10f.pow(gainDb / 20f)
        val ceiling = maxOf(userVolume, 0.8f)
        return scaled.coerceIn(0f, minOf(1f, ceiling))
    }
}
