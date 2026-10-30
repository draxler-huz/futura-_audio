@file:OptIn(UnstableApi::class)

package com.example.wakeupmusic.player.service

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.wakeupmusic.player.data.PlayerStateStore

/**
 * Foreground service of type mediaPlayback (feature 1.4). It hosts the media session, so the lock screen, the
 * notification, smartwatches and Bluetooth devices all see the same title, artist, album, artwork and duration.
 *
 * The default Media3 notification is used. Seeking works from the lock screen, because the session forwards seek
 * commands to [FuturaPlayer].
 *
 * Media-button events (feature 1.6) are handled here, not by the session's default handling. This is so the
 * click mapping in settings applies to single, double and triple clicks.
 */
class PlaybackService : MediaSessionService() {

    private var player: FuturaPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = FuturaPlayer(this, PlayerStateStore.create(this))
        player = p
        session = MediaSession.Builder(this, p)
            .setCallback(mediaButtonCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // A destroy that we initiate is a clean exit, so the next start does not auto-resume.
        PlayerStateStore.create(this).cleanExit = true
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private val mediaButtonCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            // Act on key-down only, and ignore auto-repeat, so one press counts as one click.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                player?.onMediaKey(event.keyCode)
            }
            return true
        }
    }
}
