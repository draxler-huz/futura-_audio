package com.example.wakeupmusic.engine

/**
 * Kotlin side of the Stage 0 tone engine (engine/src/main/cpp/tone_engine.cpp).
 *
 * Threading: every call on one instance must come from the same thread. The app uses a single controller
 * thread. The native side handles its own audio-thread synchronisation.
 *
 * Lifecycle: create -> open -> setPlaying / setFrequency / setGain / streamInfo / xRunCount -> close.
 * Calls after close() are no-ops (or return the fallback), never a crash.
 */
class ToneEngine : AutoCloseable {

    private var handle: Long = EngineBridge.nativeCreate()

    init {
        check(handle != 0L) { "Audio engine could not be created" }
    }

    /** Opens a shared, low-latency output stream and starts it silently. Returns [OK] or an ERR_* code. */
    fun open(): Int = queryOr(ERR_INVALID_STATE) { EngineBridge.nativeOpen(it) }

    fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            EngineBridge.nativeClose(h)
            EngineBridge.nativeDestroy(h)
        }
    }

    /** Turns the 440 Hz test tone on or off. The change fades over about 10 ms. */
    fun setPlaying(playing: Boolean) = withHandle { EngineBridge.nativeSetPlaying(it, playing) }

    fun setFrequency(hz: Float) = withHandle { EngineBridge.nativeSetFrequency(it, hz) }

    /** Linear gain, 0.0 to 1.0. */
    fun setGain(gain: Float) = withHandle { EngineBridge.nativeSetGain(it, gain.coerceIn(0f, 1f)) }

    /** Facts about the open stream, or null when it is closed. */
    fun streamInfo(): StreamInfo? = queryOr<StreamInfo?>(null) { h ->
        EngineBridge.nativeStreamInfo(h)?.let { a ->
            StreamInfo(
                sampleRate = a[0],
                framesPerBurst = a[1],
                audioApi = AudioApi.fromCode(a[2]),
                deviceId = a[3],
            )
        }
    }

    /** Underruns reported by the platform since the stream opened. 0 when closed or unsupported. */
    fun xRunCount(): Int = queryOr(0) { EngineBridge.nativeXRunCount(it) }

    private inline fun withHandle(block: (Long) -> Unit) {
        val h = handle
        if (h != 0L) block(h)
    }

    private inline fun <T> queryOr(fallback: T, block: (Long) -> T): T {
        val h = handle
        return if (h != 0L) block(h) else fallback
    }

    companion object {
        // Status codes. Keep in sync with futura::ErrorCode in engine/src/main/cpp/audio_types.h.
        const val OK = 0
        const val ERR_OUTPUT = -6
        const val ERR_INVALID_STATE = -7
    }
}

/** Facts about the open output stream. */
data class StreamInfo(
    val sampleRate: Int,
    val framesPerBurst: Int,
    val audioApi: AudioApi,
    val deviceId: Int,
)

/** The audio API Oboe chose for the stream. Codes match tone_engine.cpp (audioApiCode). */
enum class AudioApi(val label: String) {
    UNSPECIFIED("Unspecified"),
    AAUDIO("AAudio"),
    OPENSL_ES("OpenSL ES");

    companion object {
        fun fromCode(code: Int): AudioApi = entries.getOrElse(code) { UNSPECIFIED }
    }
}

/** Raw JNI bindings. Implemented in engine/src/main/cpp/tone_jni.cpp. */
internal object EngineBridge {
    init {
        // Same library as the alarm engine. One shared object holds all native code.
        System.loadLibrary("futura_audio")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeOpen(handle: Long): Int
    external fun nativeClose(handle: Long)
    external fun nativeSetPlaying(handle: Long, playing: Boolean)
    external fun nativeSetFrequency(handle: Long, hz: Float)
    external fun nativeSetGain(handle: Long, gain: Float)
    external fun nativeStreamInfo(handle: Long): IntArray?
    external fun nativeXRunCount(handle: Long): Int
}
