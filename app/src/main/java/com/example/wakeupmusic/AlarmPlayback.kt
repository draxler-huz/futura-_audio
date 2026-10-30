package com.example.wakeupmusic

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.example.wakeupmusic.audio.CompressorSettings
import com.example.wakeupmusic.audio.EmergencyTone
import com.example.wakeupmusic.audio.EqBand
import com.example.wakeupmusic.audio.EqType
import com.example.wakeupmusic.audio.FallbackAudio
import com.example.wakeupmusic.audio.NativeAudioEngine
import com.example.wakeupmusic.audio.PlaybackResolver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The alarm's sound. Owned by [AlarmService], not by any activity (Stage 2), so the alarm keeps ringing when the
 * screen is off, the alarm screen is closed, or the user has left the app.
 *
 * The playback order is unchanged from Stage 0: the native engine, then the device sound, then the embedded WAV, then
 * the synthesised tone.
 *
 * Threads: native engine calls run in order on [audioIo]. MediaPlayer, the emergency tone and the UI state are used
 * only on the main thread. [onStatus] is always called on the main thread.
 */
class AlarmPlayback(context: Context, private val onStatus: (Status) -> Unit) {

    data class Status(val ringing: Boolean, val label: String, val playing: Boolean)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // Native path. One thread, so a load, start, stop or close never overlaps another.
    private val audioIo: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: NativeAudioEngine? = null          // audioIo only
    @Volatile private var playGeneration = 0               // bumped by a newer play, or by stop

    // Main thread only.
    private var stopped = false
    private var paused = false
    private var usingNative = false
    private var player: MediaPlayer? = null                // fallback, used only if the native engine fails
    private var emergency: EmergencyTone? = null           // last-resort synthesised tone
    private var focus: AudioFocusRequest? = null
    private var index = 0
    private var label = ""

    // Prototype: device sounds. Replace with your own tracks in res/raw.
    private val sounds by lazy {
        listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_NOTIFICATION,
            RingtoneManager.TYPE_RINGTONE,
        ).map { RingtoneManager.getDefaultUri(it) }
    }

    /** Starts the alarm sound. Main thread. */
    fun start() {
        if (stopped) return
        play()
    }

    /** Moves to the next sound and plays it. Main thread. */
    fun skip() {
        if (stopped) return
        index = (index + 1) % sounds.size
        play()
    }

    /** Pauses or resumes whichever path is playing. Main thread. */
    fun togglePause() {
        if (stopped) return
        paused = !paused
        emergency?.let { if (paused) it.pause() else it.resume() }
        player?.let { if (paused) it.pause() else it.start() }
        if (usingNative) {
            val pauseNow = paused
            audioIo.execute { engine?.let { if (pauseNow) it.pause() else it.resume() } }
        }
        emit()
    }

    /** Ends the alarm and releases audio and focus. Safe to call more than once. Main thread. */
    fun stop() {
        if (stopped) return
        stopped = true
        playGeneration++                 // cancels any load still in progress
        releaseFallbacks()
        usingNative = false
        audioIo.execute {
            engine?.close()
            engine = null
        }
        audioIo.shutdown()               // runs the queued close, then stops accepting work
        focus?.let { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focus = null
        emit()
    }

    private fun play() {
        val gen = ++playGeneration
        releaseFallbacks()
        val volume = prefs.getInt("volume", 60) / 100f
        val uri = sounds[index]
        val soundNumber = index + 1
        val soundCount = sounds.size
        requestFocus()
        paused = false
        label = "Starting..."
        emit()

        audioIo.execute {
            // Local storage only. Nothing here waits on the network.
            val resolved = PlaybackResolver.resolve(app)
            var shown: String
            var started: Boolean

            when (resolved) {
                is PlaybackResolver.Resolved.UserTrack -> {
                    val vol = PlaybackResolver.effectiveVolume(volume, resolved.track.gainDb)
                    shown = resolved.track.displayName
                    started = startNative({ openFile(resolved.file) }, vol, gen)
                    if (!started && gen == playGeneration) {
                        // The decoder rejected the file. Switch to the embedded sound at once.
                        PlaybackResolver.reportDecodeFailure(app, resolved.track)
                        shown = FALLBACK_LABEL
                        started = startNative({ FallbackAudio.open(app) }, volume, gen)
                    }
                }
                is PlaybackResolver.Resolved.Fallback -> {
                    shown = FALLBACK_LABEL
                    started = startNative({ FallbackAudio.open(app) }, volume, gen)
                }
                is PlaybackResolver.Resolved.DeviceSounds -> {
                    shown = "sound $soundNumber of $soundCount"
                    started = startNative({ app.contentResolver.openAssetFileDescriptor(uri, "r") }, volume, gen)
                }
            }

            val deviceUri = if (resolved is PlaybackResolver.Resolved.DeviceSounds) uri else null
            val finalShown = shown
            val finalStarted = started
            main.post {
                if (stopped || gen != playGeneration) return@post
                usingNative = finalStarted
                paused = false
                label = if (finalStarted) finalShown else startWithoutNative(deviceUri, volume, finalShown)
                emit()
            }
        }
    }

    private fun emit() {
        onStatus(Status(ringing = !stopped, label = label, playing = !paused))
    }

    private fun openFile(file: java.io.File): AssetFileDescriptor? = try {
        AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0L, file.length(),
        )
    } catch (e: Throwable) {
        null
    }

    // Runs on audioIo. Returns true only when the native engine is playing.
    private fun startNative(open: () -> AssetFileDescriptor?, volume: Float, gen: Int): Boolean {
        engine?.close()
        engine = null
        if (gen != playGeneration) return false

        var candidate: NativeAudioEngine? = null
        return try {
            val afd = open() ?: return false
            val eng = NativeAudioEngine()
            candidate = eng
            // The engine takes ownership of the descriptor and closes it, even on failure.
            val rc = eng.loadFile(afd.parcelFileDescriptor.detachFd(), afd.startOffset, afd.declaredLength)
            afd.close()
            if (rc != NativeAudioEngine.OK) {
                eng.close()
                return false
            }
            val fadeMs = prefs.getInt("fade_seconds", 0) * 1000
            eng.setVolume(volume, fadeMs, NativeAudioEngine.CURVE_LOGARITHMIC)
            eng.setCompressor(CompressorSettings())
            eng.setEq(eqBands())
            if (gen != playGeneration || eng.start() != NativeAudioEngine.OK) {
                eng.close()
                return false
            }
            engine = eng
            true
        } catch (e: Throwable) {
            // Includes UnsatisfiedLinkError if the native library is missing. The caller falls back to MediaPlayer.
            candidate?.close()
            false
        }
    }

    // Main thread. Device sound via MediaPlayer, then the embedded WAV, then the synthesised tone.
    private fun startWithoutNative(deviceUri: Uri?, volume: Float, shown: String): String {
        if (deviceUri != null && startMediaPlayer(deviceUri, volume)) return shown
        if (startFallbackMediaPlayer(volume)) return FALLBACK_LABEL
        val tone = EmergencyTone()
        if (tone.start(volume)) {
            emergency = tone
            return FALLBACK_LABEL
        }
        return "no audio could be started"
    }

    // Flat (all 0 dB) returns an empty list, which bypasses the EQ.
    private fun eqBands(): List<EqBand> {
        val low = prefs.getInt("eq_low", 0)
        val mid = prefs.getInt("eq_mid", 0)
        val high = prefs.getInt("eq_high", 0)
        if (low == 0 && mid == 0 && high == 0) return emptyList()
        return listOf(
            EqBand(EqType.LOW_SHELF, 100f, 0.707f, low.toFloat()),
            EqBand(EqType.PEAKING, 1000f, 0.707f, mid.toFloat()),
            EqBand(EqType.HIGH_SHELF, 8000f, 0.707f, high.toFloat()),
        )
    }

    private fun newPlayer(volume: Float) = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        isLooping = true
        setVolume(volume, volume)
    }

    private fun startMediaPlayer(uri: Uri, volume: Float): Boolean {
        val p = newPlayer(volume)
        return try {
            p.setDataSource(app, uri)
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Throwable) {
            runCatching { p.release() }
            false
        }
    }

    private fun startFallbackMediaPlayer(volume: Float): Boolean {
        val afd = FallbackAudio.open(app) ?: return false
        val p = newPlayer(volume)
        return try {
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Throwable) {
            runCatching { p.release() }
            false
        } finally {
            runCatching { afd.close() }
        }
    }

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            .build()
        am.requestAudioFocus(req)
        focus = req
    }

    private fun releaseFallbacks() {
        player?.release()
        player = null
        emergency?.close()
        emergency = null
    }

    private companion object {
        const val FALLBACK_LABEL = "built-in fallback sound (your track could not be played)"
    }
}
