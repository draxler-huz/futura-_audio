package com.example.wakeupmusic.engine

/**
 * Kotlin side of the Stage 1 playback engine (engine/src/main/cpp/player/player_engine.cpp).
 *
 * This is separate from [ToneEngine] (Stage 0 test tone) and from the alarm engine. It has its own Oboe stream
 * (usage Media, content type Music), so music and alarms never share a stream.
 *
 * Ownership of file descriptors: every `fd` passed in is OWNED by the native side and is closed there, exactly
 * once, whether or not the call succeeds. Pass `ParcelFileDescriptor.detachFd()` and never close it yourself.
 *
 * Threading: all calls on one instance must come from the same thread (the service's main thread). The native
 * side handles its own audio-thread synchronisation. Calls after [close] are no-ops or return fallbacks.
 *
 * Lifecycle: create -> open -> loadCurrent [-> loadNext] -> start / pause / resume / seekTo / stop -> close.
 */
class PlayerEngine : AutoCloseable {

    private var handle: Long = PlayerBridge.nativeCreate()

    init {
        check(handle != 0L) { "Player engine could not be created" }
    }

    /** Opens the output stream. Safe to call again while open. */
    fun open(): Int = queryOr(PlayerCode.ERR_INVALID_STATE) { PlayerBridge.nativeOpen(it) }

    /** Stops playback and closes the output stream. */
    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            PlayerBridge.nativeClose(h)
            PlayerBridge.nativeDestroy(h)
        }
    }

    /**
     * Stops anything playing, decodes [fd] (offset and length select a range, length < 0 means to end of file),
     * and seeks to [startMs]. The track is then ready for [start]. Returns [PlayerCode.OK] or an error code.
     */
    fun loadCurrent(fd: Int, offset: Long = 0L, length: Long = -1L, startMs: Long = 0L): Int =
        queryOr(PlayerCode.ERR_INVALID_STATE) { PlayerBridge.nativeLoadCurrent(it, fd, offset, length, startMs) }

    /**
     * Prepares the track that plays right after the current one. It is decoded ahead into a second buffer, and
     * the switch happens on an exact sample when the current track ends. Call again after each [switchCount]
     * change to queue the following track.
     */
    fun loadNext(fd: Int, offset: Long = 0L, length: Long = -1L): Int =
        queryOr(PlayerCode.ERR_INVALID_STATE) { PlayerBridge.nativeLoadNext(it, fd, offset, length) }

    /** Drops the queued next track, if playback has not already moved on to it. Used when the queue changes. */
    fun clearNext() = withHandle { PlayerBridge.nativeClearNext(it) }

    fun start(): Int = queryOr(PlayerCode.ERR_INVALID_STATE) { PlayerBridge.nativeStart(it) }

    /** Silences output and holds the position. The stream stays open, so [resume] is immediate. */
    fun pause() = withHandle { PlayerBridge.nativePause(it) }

    fun resume() = withHandle { PlayerBridge.nativeResume(it) }

    /** Stops output and rewinds the current track to its beginning. */
    fun stop() = withHandle { PlayerBridge.nativeStop(it) }

    /** Moves the current track to [ms]. Output is briefly interrupted. Returns [PlayerCode.OK] or an error. */
    fun seekTo(ms: Long): Int = queryOr(PlayerCode.ERR_INVALID_STATE) { PlayerBridge.nativeSeekMs(it, ms) }

    /** Target gain in [0, 1], reached over [rampMs] milliseconds (0 applies it at once). */
    fun setVolume(target: Float, rampMs: Int = 20) =
        withHandle { PlayerBridge.nativeSetVolume(it, target.coerceIn(0f, 1f), rampMs) }

    fun isPlaying(): Boolean = queryOr(false) { PlayerBridge.nativeIsPlaying(it) }

    /** True when the current track finished and no next track was queued. */
    fun isEnded(): Boolean = queryOr(false) { PlayerBridge.nativeEnded(it) }

    /** Position within the current logical track (after gapless trimming), in milliseconds. */
    fun positionMs(): Long = queryOr(0L) { PlayerBridge.nativePositionMs(it) }

    /** Length of the current logical track in milliseconds, or -1 when unknown. */
    fun durationMs(): Long = queryOr(-1L) { PlayerBridge.nativeDurationMs(it) }

    /** Increases by one each time playback moves on to the queued next track (gapless switch). */
    fun switchCount(): Int = queryOr(0) { PlayerBridge.nativeSwitchCount(it) }

    /** Facts about the current track, or null if none is loaded. */
    fun trackInfo(): TrackFacts? = queryOr<TrackFacts?>(null) { h ->
        PlayerBridge.nativeTrackInfo(h).let { parseKeyValues(it) }?.let { m ->
            TrackFacts(
                codec = m["codec"] ?: "Unknown",
                sampleRate = m["rate"]?.toIntOrNull() ?: 0,
                channels = m["channels"]?.toIntOrNull() ?: 0,
                bitDepth = m["bitDepth"]?.toIntOrNull() ?: 0,
                lossless = m["lossless"] == "1",
            )
        }
    }

    /** Engine statistics for the status display. Zeros when closed. */
    fun stats(): EngineStats = queryOr(EngineStats.EMPTY) { h ->
        val a = PlayerBridge.nativeStats(h)
        if (a.size < EngineStats.FIELD_COUNT) EngineStats.EMPTY else EngineStats(
            xruns = a[0].toInt(),
            bufferSizeFrames = a[1].toInt(),
            framesPerBurst = a[2].toInt(),
            outputSampleRate = a[3].toInt(),
            callbackMicrosLast = a[4],
            callbackMicrosAvg = a[5],
            callbackMicrosMax = a[6],
            underrunFrames = a[7],
            callbackCount = a[8],
            decodeErrors = a[9].toInt(),
            audioApi = AudioApi.fromCode(a[10].toInt()),
        )
    }

    private inline fun withHandle(block: (Long) -> Unit) {
        val h = handle
        if (h != 0L) block(h)
    }

    private inline fun <T> queryOr(fallback: T, block: (Long) -> T): T {
        val h = handle
        return if (h != 0L) block(h) else fallback
    }

    companion object {
        /**
         * Decodes a file without playing it and reports what the format is. Used by the format self-test screen.
         * Decodes at most [maxSeconds] of audio. Runs on the calling thread, so call it from a background dispatcher.
         * Owns and closes [fd], as above.
         */
        fun probe(fd: Int, offset: Long = 0L, length: Long = -1L, maxSeconds: Int = 5): ProbeResult {
            val m = parseKeyValues(PlayerBridge.nativeProbe(fd, offset, length, maxSeconds))
                ?: return ProbeResult.failed(PlayerCode.ERR_UNSUPPORTED, "No answer from the decoder")
            val code = m["error"]?.toIntOrNull() ?: 0
            return ProbeResult(
                ok = m["ok"] == "1",
                errorCode = code,
                codec = m["codec"] ?: "Unknown",
                sampleRate = m["rate"]?.toIntOrNull() ?: 0,
                channels = m["channels"]?.toIntOrNull() ?: 0,
                bitDepth = m["bitDepth"]?.toIntOrNull() ?: 0,
                lossless = m["lossless"] == "1",
                durationMs = m["durationMs"]?.toLongOrNull() ?: -1L,
                framesDecoded = m["framesDecoded"]?.toLongOrNull() ?: 0L,
                decodeErrors = m["decodeErrors"]?.toIntOrNull() ?: 0,
            )
        }

        /** Parses "key=value;key=value" as produced by the native side. Returns null for an empty string. */
        internal fun parseKeyValues(s: String?): Map<String, String>? {
            if (s.isNullOrEmpty()) return null
            return s.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
        }
    }
}

/** Status codes. Keep in sync with futura::ErrorCode in engine/src/main/cpp/audio_types.h. */
object PlayerCode {
    const val OK = 0
    const val ERR_IO = -1
    const val ERR_UNSUPPORTED = -2
    const val ERR_DECODE = -3
    const val ERR_EMPTY = -5
    const val ERR_OUTPUT = -6
    const val ERR_INVALID_STATE = -7
}

/** Facts about the current track. [codec] is the display name, for example "FLAC" or "AAC (MediaCodec)". */
data class TrackFacts(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val lossless: Boolean,
)

/** Result of [PlayerEngine.probe]. When [ok] is false, [errorCode] says why and [codec] may still be known. */
data class ProbeResult(
    val ok: Boolean,
    val errorCode: Int,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val lossless: Boolean,
    val durationMs: Long,
    val framesDecoded: Long,
    val decodeErrors: Int,
) {
    companion object {
        fun failed(code: Int, @Suppress("UNUSED_PARAMETER") message: String) = ProbeResult(
            ok = false, errorCode = code, codec = "Unknown", sampleRate = 0, channels = 0, bitDepth = 0,
            lossless = false, durationMs = -1L, framesDecoded = 0L, decodeErrors = 0,
        )
    }
}

/** Engine statistics. Callback times are in microseconds. */
data class EngineStats(
    val xruns: Int,
    val bufferSizeFrames: Int,
    val framesPerBurst: Int,
    val outputSampleRate: Int,
    val callbackMicrosLast: Long,
    val callbackMicrosAvg: Long,
    val callbackMicrosMax: Long,
    val underrunFrames: Long,
    val callbackCount: Long,
    val decodeErrors: Int,
    val audioApi: AudioApi,
) {
    companion object {
        /** Number of fields in the native array. Keep in sync with PlayerEngine::stats() in player_jni.cpp. */
        const val FIELD_COUNT = 11

        val EMPTY = EngineStats(
            xruns = 0, bufferSizeFrames = 0, framesPerBurst = 0, outputSampleRate = 0,
            callbackMicrosLast = 0, callbackMicrosAvg = 0, callbackMicrosMax = 0, underrunFrames = 0,
            callbackCount = 0, decodeErrors = 0, audioApi = AudioApi.UNSPECIFIED,
        )
    }
}

/**
 * Raw JNI bindings for the player. Implemented in engine/src/main/cpp/player/player_jni.cpp.
 * Strings use the "key=value;key=value" form, parsed by [PlayerEngine.Companion].
 */
internal object PlayerBridge {
    init {
        // Same library as every other native engine. One shared object holds all native code.
        System.loadLibrary("futura_audio")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeOpen(handle: Long): Int
    external fun nativeClose(handle: Long)
    external fun nativeLoadCurrent(handle: Long, fd: Int, offset: Long, length: Long, startMs: Long): Int
    external fun nativeLoadNext(handle: Long, fd: Int, offset: Long, length: Long): Int
    external fun nativeClearNext(handle: Long)
    external fun nativeStart(handle: Long): Int
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeSeekMs(handle: Long, ms: Long): Int
    external fun nativeSetVolume(handle: Long, target: Float, rampMs: Int)
    external fun nativeIsPlaying(handle: Long): Boolean
    external fun nativeEnded(handle: Long): Boolean
    external fun nativePositionMs(handle: Long): Long
    external fun nativeDurationMs(handle: Long): Long
    external fun nativeSwitchCount(handle: Long): Int
    external fun nativeStats(handle: Long): LongArray
    external fun nativeTrackInfo(handle: Long): String?
    external fun nativeProbe(fd: Int, offset: Long, length: Long, maxSeconds: Int): String?
}
