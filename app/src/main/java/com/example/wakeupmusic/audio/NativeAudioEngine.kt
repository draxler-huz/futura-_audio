package com.example.wakeupmusic.audio

/**
 * Kotlin side of the native engine (app/src/main/cpp).
 *
 * Threading: every call on one instance must come from the same thread (AlarmPlayback uses a
 * single background executor). The native side handles its own audio-thread synchronisation.
 *
 * Lifecycle: create -> loadFile -> setVolume / setCompressor / setEq -> start -> ... -> close.
 * loadFile takes ownership of the file descriptor it is given, even when it fails.
 */
class NativeAudioEngine : AutoCloseable {

    private var handle: Long = NativeBridge.nativeCreate()

    init {
        check(handle != 0L) { "Native audio engine could not be created" }
    }

    /** Decodes a file and makes it the current track. Returns [OK] or an ERR_* code. */
    fun loadFile(fd: Int, offset: Long, length: Long): Int =
        queryOr(ERR_INVALID_STATE) { NativeBridge.nativeLoadFile(it, fd, offset, length) }

    /** Starts the current track from its beginning. Returns [OK] or an ERR_* code. */
    fun start(): Int = queryOr(ERR_INVALID_STATE) { NativeBridge.nativeStart(it) }

    fun pause() = withHandle { NativeBridge.nativePause(it) }

    fun resume() = withHandle { NativeBridge.nativeResume(it) }

    fun stop() = withHandle { NativeBridge.nativeStop(it) }

    /**
     * Sets the output gain, 0.0 to 1.0. [rampMs] = 0 applies it immediately.
     * [curve] is [CURVE_LINEAR] or [CURVE_LOGARITHMIC].
     */
    fun setVolume(target: Float, rampMs: Int = 0, curve: Int = CURVE_LINEAR) =
        withHandle { NativeBridge.nativeSetVolume(it, target.coerceIn(0f, 1f), rampMs.coerceAtLeast(0), curve) }

    fun setCompressor(settings: CompressorSettings) = withHandle {
        NativeBridge.nativeSetCompressor(
            it,
            settings.enabled,
            settings.thresholdDb,
            settings.ratio,
            settings.attackMs,
            settings.releaseMs,
            settings.makeupDb,
            settings.ceilingDb,
        )
    }

    /** Replaces the parametric EQ. Only the first [MAX_EQ_BANDS] bands are used. Empty list = bypass. */
    fun setEq(bands: List<EqBand>) {
        val n = minOf(bands.size, MAX_EQ_BANDS)
        val types = IntArray(n) { bands[it].type.code }
        val freqs = FloatArray(n) { bands[it].freqHz }
        val qs = FloatArray(n) { bands[it].q }
        val gains = FloatArray(n) { bands[it].gainDb }
        withHandle { NativeBridge.nativeSetEq(it, types, freqs, qs, gains) }
    }

    fun isPlaying(): Boolean = queryOr(false) { NativeBridge.nativeIsPlaying(it) }

    /** Output stream sample rate, or 0 before the stream has been opened. */
    fun sampleRate(): Int = queryOr(0) { NativeBridge.nativeSampleRate(it) }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            NativeBridge.nativeDestroy(h)
        }
    }

    // Calls after close() are no-ops (or return the fallback), never a crash.
    private inline fun withHandle(block: (Long) -> Unit) {
        val h = handle
        if (h != 0L) block(h)
    }

    private inline fun <T> queryOr(fallback: T, block: (Long) -> T): T {
        val h = handle
        return if (h != 0L) block(h) else fallback
    }

    companion object {
        // Status codes. Keep in sync with futura::ErrorCode in audio_types.h.
        const val OK = 0
        const val ERR_IO = -1
        const val ERR_UNSUPPORTED = -2
        const val ERR_DECODE = -3
        const val ERR_TOO_LONG = -4
        const val ERR_EMPTY = -5
        const val ERR_OUTPUT = -6
        const val ERR_INVALID_STATE = -7

        const val CURVE_LINEAR = 0
        const val CURVE_LOGARITHMIC = 1

        const val MAX_EQ_BANDS = 8
    }
}

/**
 * Dynamic range control and output ceiling.
 * The ceiling is always applied, so a sudden loud passage cannot exceed it even when compression is off.
 */
data class CompressorSettings(
    val enabled: Boolean = true,
    val thresholdDb: Float = -12f,
    val ratio: Float = 4f,
    val attackMs: Float = 5f,
    val releaseMs: Float = 150f,
    val makeupDb: Float = 0f,
    val ceilingDb: Float = -1f,
)

enum class EqType(val code: Int) {
    PEAKING(0),
    LOW_SHELF(1),
    HIGH_SHELF(2),
}

data class EqBand(
    val type: EqType,
    val freqHz: Float,
    val q: Float = 0.707f,
    val gainDb: Float = 0f,
)

/**
 * Raw JNI bindings. Implemented in app/src/main/cpp/jni_bridge.cpp.
 * The library loads on first use of this object.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("futura_audio")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeLoadFile(handle: Long, fd: Int, offset: Long, length: Long): Int
    external fun nativeStart(handle: Long): Int
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeSetVolume(handle: Long, target: Float, rampMs: Int, curve: Int)
    external fun nativeSetCompressor(
        handle: Long,
        enabled: Boolean,
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupDb: Float,
        ceilingDb: Float,
    )
    external fun nativeSetEq(handle: Long, types: IntArray, freqs: FloatArray, qs: FloatArray, gains: FloatArray)
    external fun nativeIsPlaying(handle: Long): Boolean
    external fun nativeSampleRate(handle: Long): Int
}
