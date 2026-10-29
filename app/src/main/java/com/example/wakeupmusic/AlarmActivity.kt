package com.example.wakeupmusic

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.wakeupmusic.audio.CompressorSettings
import com.example.wakeupmusic.audio.NativeAudioEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AlarmActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var player: MediaPlayer? = null   // fallback path, used only if the native engine fails
    private var focus: AudioFocusRequest? = null
    private var index = 0
    private lateinit var title: TextView
    private lateinit var playPause: Button

    // Native path. Engine calls run on this single thread, in order, so a load, start,
    // stop, or close never overlaps another one.
    private val audioIo: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: NativeAudioEngine? = null   // touched only on audioIo
    @Volatile private var playGeneration = 0        // bumped whenever a newer play/dismiss supersedes a load
    private var usingNative = false                 // UI thread only
    private var nativePaused = false                // UI thread only

    // Prototype: uses built-in device sounds. Replace with your own tracks in res/raw.
    private val sounds by lazy {
        listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_NOTIFICATION,
            RingtoneManager.TYPE_RINGTONE
        ).map { RingtoneManager.getDefaultUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val driving = prefs.getBoolean("driving", false)
        val big = if (driving) 32f else 20f
        val minH = if (driving) 200 else 120

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }

        title = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        playPause = Button(this).apply {
            textSize = big
            minHeight = minH
            setOnClickListener { toggle() }
        }
        root.addView(playPause)

        root.addView(Button(this).apply {
            text = "Skip"
            textSize = big
            minHeight = minH
            setOnClickListener {
                index = (index + 1) % sounds.size
                play()
            }
        })

        // Dismiss is always visible, in both normal and driving mode
        root.addView(Button(this).apply {
            text = "DISMISS"
            textSize = big
            minHeight = minH
            setOnClickListener { dismiss() }
        })

        setContentView(root)
        play()
    }

    private fun play() {
        val gen = ++playGeneration
        // Stop the fallback player now. The native engine is replaced on audioIo.
        player?.release()
        player = null

        val uri = sounds[index]
        val volume = prefs.getInt("volume", 60) / 100f
        requestFocus()
        title.text = "Now playing: sound ${index + 1} of ${sounds.size}"
        playPause.text = "Pause"

        audioIo.execute {
            val started = startNative(uri, volume, gen)
            runOnUiThread {
                if (isFinishing || gen != playGeneration) return@runOnUiThread
                usingNative = started
                nativePaused = false
                playPause.text = "Pause"
                if (!started) startMediaPlayer(uri, volume)
            }
        }
    }

    // Runs on audioIo. Returns true only when the native engine is playing.
    private fun startNative(uri: Uri, volume: Float, gen: Int): Boolean {
        engine?.close()
        engine = null
        if (gen != playGeneration) return false

        var candidate: NativeAudioEngine? = null
        return try {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r") ?: return false
            val eng = NativeAudioEngine()
            candidate = eng
            // The engine takes ownership of the descriptor and closes it, even on failure.
            val rc = eng.loadFile(afd.parcelFileDescriptor.detachFd(), afd.startOffset, afd.declaredLength)
            afd.close()
            if (rc != NativeAudioEngine.OK) {
                eng.close()
                return false
            }
            eng.setVolume(volume)
            eng.setCompressor(CompressorSettings())
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

    private fun startMediaPlayer(uri: Uri, volume: Float) {
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(this@AlarmActivity, uri)
            isLooping = true
            setVolume(volume, volume)
            prepare()
            start()
        }
    }

    private fun toggle() {
        if (usingNative) {
            nativePaused = !nativePaused
            val pauseNow = nativePaused
            audioIo.execute {
                engine?.let { if (pauseNow) it.pause() else it.resume() }
            }
            playPause.text = if (pauseNow) "Play" else "Pause"
            return
        }
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playPause.text = "Play"
        } else {
            p.start()
            playPause.text = "Pause"
        }
    }

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            ).build()
        am.requestAudioFocus(req)
        focus = req
    }

    private fun releaseNativeAsync() {
        audioIo.execute {
            engine?.close()
            engine = null
        }
    }

    private fun dismiss() {
        playGeneration++          // cancels any load still in progress
        player?.release()
        player = null
        usingNative = false
        releaseNativeAsync()
        focus?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        getSystemService(NotificationManager::class.java).cancel(1)
        finish()
    }

    override fun onDestroy() {
        playGeneration++
        player?.release()
        player = null
        releaseNativeAsync()
        audioIo.shutdown()
        super.onDestroy()
    }
}
