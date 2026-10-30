package com.example.wakeupmusic.player.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for music playback (feature 1.5).
 *
 * Requests AUDIOFOCUS_GAIN with USAGE_MEDIA and CONTENT_TYPE_MUSIC, so an alarm (USAGE_ALARM) takes focus
 * from us and we pause. Focus changes are reported to [Listener]. The decisions stay in the player.
 *
 * Also registers a BECOMING_NOISY receiver while the listener wants it. The system sends that broadcast on
 * wired unplug and on Bluetooth disconnect, and it has to be registered at run time.
 *
 * Threading: main thread only. Android delivers focus callbacks on the handler given here, which is the main looper.
 */
class AudioFocusController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Focus lost. [transient] means it will probably come back (a call, a navigation prompt). */
        fun onFocusPause(transient: Boolean)

        /** Ducking on (a short prompt) or off. */
        fun onFocusDuck(enabled: Boolean)

        /** Focus returned after a transient loss. */
        fun onFocusRegained()

        /** The output device went away (wired unplug or Bluetooth disconnect). */
        fun onBecomingNoisy()
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                listener.onFocusPause(transient = false)
                abandon()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener.onFocusPause(transient = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onFocusDuck(enabled = true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                listener.onFocusDuck(enabled = false)
                listener.onFocusRegained()
            }
            else -> Unit
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener.onBecomingNoisy()
        }
    }

    /** Requests music focus. Returns true when granted. */
    fun request(): Boolean {
        val existing = request
        if (existing != null) {
            return audioManager.requestAudioFocus(existing) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val built = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener, handler)
            .setWillPauseWhenDucked(false)
            .build()
        request = built
        return audioManager.requestAudioFocus(built) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Gives focus back so other apps can resume. Safe to call when we do not hold it. */
    fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /** Registers or unregisters the BECOMING_NOISY receiver. Register while playing, unregister when idle. */
    fun setNoisyReceiverActive(active: Boolean) {
        if (active == noisyRegistered) return
        if (active) {
            appContext.registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            runCatching { appContext.unregisterReceiver(noisyReceiver) }
        }
        noisyRegistered = active
    }
}
