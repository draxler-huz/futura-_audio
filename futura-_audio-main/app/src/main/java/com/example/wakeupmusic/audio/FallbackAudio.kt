package com.example.wakeupmusic.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.wakeupmusic.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The fail-safe sound. Two layers, both inside the app binary:
 *  1. res/raw/futura_fallback.wav, packaged uncompressed in the APK, played by the native engine
 *     (or MediaPlayer if the native engine is unavailable).
 *  2. [EmergencyTone], generated from code with no file, no resource and no native library.
 */
object FallbackAudio {

    /** Opens the packaged fallback WAV, or null if the resource cannot be opened. */
    fun open(context: Context): AssetFileDescriptor? =
        try {
            context.resources.openRawResourceFd(R.raw.futura_fallback)
        } catch (e: Throwable) {
            null
        }
}

/** Last resort: a looping three-beep tone synthesised in memory and played through AudioTrack. */
class EmergencyTone : AutoCloseable {

    private var track: AudioTrack? = null

    /** Returns true if the tone is playing. */
    fun start(volume: Float): Boolean = try {
        val pcm = synthesize()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        true
    } catch (e: Throwable) {
        close()
        false
    }

    fun pause() { runCatching { track?.pause() } }

    fun resume() { runCatching { track?.play() } }

    override fun close() {
        val t = track
        track = null
        runCatching { t?.stop() }
        runCatching { t?.release() }
    }

    private fun synthesize(): ShortArray {
        val freqs = intArrayOf(660, 880, 1100)
        val beep = (RATE * 0.22).toInt()
        val gap = (RATE * 0.08).toInt()
        val tail = (RATE * 0.5).toInt()
        val out = ShortArray(freqs.size * (beep + gap) + tail)
        var pos = 0
        for (f in freqs) {
            for (i in 0 until beep) {
                val env = min(1.0, min(i / (RATE * 0.01), (beep - i) / (RATE * 0.02)))
                out[pos + i] = (0.6 * env * sin(2.0 * PI * f * i / RATE) * 32767).toInt().toShort()
            }
            pos += beep + gap
        }
        return out
    }

    private companion object {
        const val RATE = 22050
    }
}
