package com.example.wakeupmusic.data

import android.content.Context

/**
 * Single access point for local storage. Two separate databases (alarms.db and
 * audio_library.db) so alarm configuration and audio metadata never share a table or a file.
 */
class FuturaStorage private constructor(context: Context) {
    val alarms = AlarmDao(AlarmDatabase(context))
    val tracks = TrackDao(AudioLibraryDatabase(context))
    val trackStore = TrackStore(context, tracks)

    /** Set by a future network module. Null means remote tracks can only be pre-downloaded by other means. */
    @Volatile var trackFetcher: TrackFetcher? = null

    companion object {
        @Volatile private var instance: FuturaStorage? = null

        fun get(context: Context): FuturaStorage =
            instance ?: synchronized(this) {
                instance ?: FuturaStorage(context.applicationContext).also { instance = it }
            }
    }
}
