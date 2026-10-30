package com.example.wakeupmusic

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The alarm screen (Stage 2). A view of [AlarmService]: it shows the state and sends commands. It does not own any
 * audio, so closing or leaving it does not stop the alarm. Dismiss ends the alarm from here or from the notification.
 */
class AlarmActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var title: TextView
    private lateinit var playPause: Button

    private val listener: (AlarmPlayback.Status) -> Unit = { render(it) }

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
            setOnClickListener { send(AlarmService.ACTION_TOGGLE) }
        }
        root.addView(playPause)

        root.addView(Button(this).apply {
            text = "Skip"
            textSize = big
            minHeight = minH
            setOnClickListener { send(AlarmService.ACTION_SKIP) }
        })

        // Dismiss is always visible, in both normal and driving mode.
        root.addView(Button(this).apply {
            text = "DISMISS"
            textSize = big
            minHeight = minH
            setOnClickListener { send(AlarmService.ACTION_DISMISS) }
        })

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        AlarmService.addListener(listener)
        render(AlarmService.status)
    }

    override fun onStop() {
        AlarmService.removeListener(listener)
        super.onStop()
    }

    private fun render(s: AlarmPlayback.Status) {
        if (!s.ringing) {
            // The alarm has ended (dismissed here or from the notification). Nothing to show.
            if (!isFinishing) finish()
            return
        }
        title.text = "Now playing: ${s.label}"
        playPause.text = if (s.playing) "Pause" else "Play"
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
    }
}
