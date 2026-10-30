package com.example.wakeupmusic.player.data

import android.content.Context
import android.content.SharedPreferences
import com.example.wakeupmusic.player.service.ButtonAction
import com.example.wakeupmusic.player.service.ClickMapping

/** Playback state saved between runs (feature 1.7). */
data class SavedPlayback(
    val uris: List<String>,
    val index: Int,
    val positionMs: Long,
    val volume: Float,
    val wasPlaying: Boolean,
    val shuffle: Boolean,
)

/** Minimal key-value access, so the store can be unit tested without Android. */
interface KeyValueBackend {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean

    /** Writes all pairs in one batch. Values are String, Int, Long, Float or Boolean. */
    fun put(values: Map<String, Any>)
}

/** [KeyValueBackend] over SharedPreferences. Every call is wrapped, so a broken store never throws. */
class SharedPrefsBackend(private val prefs: SharedPreferences) : KeyValueBackend {
    override fun getString(key: String): String? = guard(null) { prefs.getString(key, null) }
    override fun getInt(key: String, default: Int): Int = guard(default) { prefs.getInt(key, default) }
    override fun getLong(key: String, default: Long): Long = guard(default) { prefs.getLong(key, default) }
    override fun getFloat(key: String, default: Float): Float = guard(default) { prefs.getFloat(key, default) }
    override fun getBoolean(key: String, default: Boolean): Boolean = guard(default) { prefs.getBoolean(key, default) }

    override fun put(values: Map<String, Any>) {
        guard(Unit) {
            val editor = prefs.edit()
            for ((key, value) in values) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> Unit
                }
            }
            editor.apply()
        }
    }

    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        fallback
    }
}

/**
 * Saves and restores the player state (feature 1.7) and the click mapping (feature 1.6).
 *
 * Save points: pause, stop, track change, and every 5 s while playing. The "cleanExit" flag is cleared when the
 * service starts and set again on a deliberate stop or a clean destroy. So if the process died while playing,
 * the next start sees cleanExit == false and wasPlaying == true, and resumes at the saved position.
 *
 * Limits, stated plainly:
 *  - A native crash kills the whole process, including the engine. We cannot catch that. The resume depends on the
 *    system starting the service again, which a media session service usually gets, but not always.
 *  - The real fix is to run the native engine in an isolated process. That is deferred. It needs a binder
 *    interface and shared-memory audio, which Stage 1 does not include.
 *
 * Every read and write is guarded. A corrupt or unavailable store falls back to defaults and never throws.
 */
class PlayerStateStore(private val backend: KeyValueBackend) {

    /** True when the last run did not end cleanly and it was playing. Read before [cleanExit] is changed. */
    fun crashRecoveryNeeded(): Boolean = !cleanExit && readPlayback()?.wasPlaying == true

    var cleanExit: Boolean
        get() = backend.getBoolean(KEY_CLEAN_EXIT, true)
        set(value) = backend.put(mapOf(KEY_CLEAN_EXIT to value))

    fun readPlayback(): SavedPlayback? {
        val raw = backend.getString(KEY_URIS)?.takeIf { it.isNotEmpty() } ?: return null
        val uris = raw.split(URI_SEPARATOR).filter { it.isNotEmpty() }
        if (uris.isEmpty()) return null
        return SavedPlayback(
            uris = uris,
            index = backend.getInt(KEY_INDEX, 0).coerceIn(0, uris.size - 1),
            positionMs = backend.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            volume = backend.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f),
            wasPlaying = backend.getBoolean(KEY_WAS_PLAYING, false),
            shuffle = backend.getBoolean(KEY_SHUFFLE, false),
        )
    }

    fun savePlayback(state: SavedPlayback) {
        backend.put(
            mapOf(
                KEY_URIS to state.uris.joinToString(URI_SEPARATOR),
                KEY_INDEX to state.index,
                KEY_POSITION_MS to state.positionMs,
                KEY_VOLUME to state.volume,
                KEY_WAS_PLAYING to state.wasPlaying,
                KEY_SHUFFLE to state.shuffle,
            )
        )
    }

    fun readMapping(): ClickMapping {
        val d = ClickMapping()
        return ClickMapping(
            single = action(KEY_MAP_SINGLE, d.single),
            double = action(KEY_MAP_DOUBLE, d.double),
            triple = action(KEY_MAP_TRIPLE, d.triple),
        )
    }

    fun saveMapping(mapping: ClickMapping) {
        backend.put(
            mapOf(
                KEY_MAP_SINGLE to mapping.single.name,
                KEY_MAP_DOUBLE to mapping.double.name,
                KEY_MAP_TRIPLE to mapping.triple.name,
            )
        )
    }

    private fun action(key: String, default: ButtonAction): ButtonAction =
        backend.getString(key)?.let { name ->
            ButtonAction.entries.firstOrNull { it.name == name }
        } ?: default

    companion object {
        private const val URI_SEPARATOR = "\n"
        private const val KEY_CLEAN_EXIT = "clean_exit"
        private const val KEY_URIS = "queue_uris"
        private const val KEY_INDEX = "queue_index"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_VOLUME = "volume"
        private const val KEY_WAS_PLAYING = "was_playing"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_MAP_SINGLE = "map_single"
        private const val KEY_MAP_DOUBLE = "map_double"
        private const val KEY_MAP_TRIPLE = "map_triple"

        /** The store the app uses. A private SharedPreferences file named "player_state". */
        fun create(context: Context): PlayerStateStore = PlayerStateStore(
            SharedPrefsBackend(context.applicationContext.getSharedPreferences("player_state", Context.MODE_PRIVATE))
        )
    }
}

/** In-memory backend, for unit tests. */
class MapBackend(val values: MutableMap<String, Any> = mutableMapOf()) : KeyValueBackend {
    override fun getString(key: String): String? = values[key] as? String
    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun put(values: Map<String, Any>) {
        this.values.putAll(values)
    }
}
