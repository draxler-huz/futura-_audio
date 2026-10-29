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
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AlarmActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var index = 0
    private lateinit var title: TextView
    private lateinit var playPause: Button

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
        player?.release()
        val volume = prefs.getInt("volume", 60) / 100f
        requestFocus()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(this@AlarmActivity, sounds[index])
            isLooping = true
            setVolume(volume, volume)
            prepare()
            start()
        }
        title.text = "Now playing: sound ${index + 1} of ${sounds.size}"
        playPause.text = "Pause"
    }

    private fun toggle() {
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

    private fun dismiss() {
        player?.release()
        player = null
        focus?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        getSystemService(NotificationManager::class.java).cancel(1)
        finish()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
