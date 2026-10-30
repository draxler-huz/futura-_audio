package com.example.wakeupmusic.player.ui

/**
 * What each Stage 1 permission is for. Shown on screen BEFORE anything asks for it (spec 1.4).
 * Keep these in step with ci/guards/allowed_permissions.txt and the manifest.
 */
object PermissionExplanations {

    const val READ_MEDIA_AUDIO_TITLE = "Audio files (READ_MEDIA_AUDIO)"
    const val READ_MEDIA_AUDIO_TEXT =
        "Lets the library list the audio files on this device, so you can browse, search and build playlists. " +
            "It is asked for the first time you open the library, and only then. The file picker in the player " +
            "does not need it. Say no and the library still shows folders you add yourself."

    const val LIBRARY_TITLE = "Your music library"

    const val POST_NOTIFICATIONS_TITLE = "Notifications (POST_NOTIFICATIONS)"
    const val POST_NOTIFICATIONS_TEXT =
        "Shows the playback notification with play, pause and next, so you can control music " +
            "from the notification shade and the lock screen. You will be asked the first time you press play. " +
            "Playback still works if you say no, but the controls will not show."

    const val FOREGROUND_SERVICE_TITLE = "Foreground service (FOREGROUND_SERVICE)"
    const val FOREGROUND_SERVICE_TEXT =
        "Lets playback keep running while the screen is off or another app is open. Granted at install. " +
            "No prompt."

    const val FOREGROUND_SERVICE_MEDIA_PLAYBACK_TITLE = "Media playback service (FOREGROUND_SERVICE_MEDIA_PLAYBACK)"
    const val FOREGROUND_SERVICE_MEDIA_PLAYBACK_TEXT =
        "Declares that the foreground service is for media playback, which Android requires for it to keep " +
            "playing in the background. Granted at install. No prompt."

    const val NOTIFICATION_RATIONALE_TITLE = "Show playback controls?"
    const val NOTIFICATION_RATIONALE_CONFIRM = "Continue"
    const val NOTIFICATION_RATIONALE_DISMISS = "Not now"
    const val NOTIFICATION_RATIONALE_TEXT = POST_NOTIFICATIONS_TEXT
}
