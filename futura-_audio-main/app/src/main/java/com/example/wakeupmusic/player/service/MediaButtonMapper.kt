package com.example.wakeupmusic.player.service

/**
 * Maps headset and Bluetooth media-button clicks to player actions (feature 1.6).
 *
 * Pure Kotlin with no Android dependencies, so it can be unit tested with a fake clock.
 *
 * Rules:
 *  - Play/pause, play and pause keys are counted. A burst ends [windowMs] after its LAST click.
 *    1 click maps to [ClickMapping.single], 2 to [ClickMapping.double], 3 or more to [ClickMapping.triple].
 *  - The dedicated next and previous keys act at once, without counting.
 *  - A burst is resolved in one of two ways. A later key press after the window has closed resolves it. Otherwise
 *    the caller schedules a check at [pendingDeadlineMs] and calls [resolveExpired] there.
 *
 * Threading: call from one thread only (the service's main thread).
 */
class MediaButtonMapper(
    var mapping: ClickMapping = ClickMapping(),
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var clicks = 0
    private var lastClickMs = 0L

    /**
     * Handles one key-down event. Returns the actions to run now, in order. The list can hold up to two
     * entries: an earlier burst that has just expired, then the action for a direct key.
     */
    fun onKey(keyCode: Int): List<ButtonAction> {
        val now = clock()
        val out = ArrayList<ButtonAction>(2)
        resolveExpiredAt(now)?.let { out.add(it) }
        when (keyCode) {
            KEYCODE_MEDIA_NEXT -> out.add(ButtonAction.NEXT)
            KEYCODE_MEDIA_PREVIOUS -> out.add(ButtonAction.PREVIOUS)
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE -> {
                clicks++
                lastClickMs = now
            }
            else -> Unit
        }
        return out
    }

    /** Resolves the open burst if its window has closed. Returns null while it is still open or when none is pending. */
    fun resolveExpired(): ButtonAction? = resolveExpiredAt(clock())

    /** The time at which the open burst closes, or null when no burst is open. */
    fun pendingDeadlineMs(): Long? = if (clicks > 0) lastClickMs + windowMs else null

    /** The number of clicks in the open burst. Exposed for tests and diagnostics. */
    fun pendingClicks(): Int = clicks

    private fun resolveExpiredAt(now: Long): ButtonAction? {
        if (clicks == 0 || now - lastClickMs < windowMs) return null
        val action = mapping.actionFor(clicks)
        clicks = 0
        return action
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 350L

        // android.view.KeyEvent key codes. Kept as literals so this class has no Android dependency.
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_MEDIA_PLAY = 126
    }
}

/** What a button burst can do. The name is the stored value, so do not rename entries. */
enum class ButtonAction { PLAY_PAUSE, NEXT, PREVIOUS, SHUFFLE, NONE }

/** The action for each click count. Defaults: single = play/pause, double = next, triple = previous. */
data class ClickMapping(
    val single: ButtonAction = ButtonAction.PLAY_PAUSE,
    val double: ButtonAction = ButtonAction.NEXT,
    val triple: ButtonAction = ButtonAction.PREVIOUS,
) {
    fun actionFor(clicks: Int): ButtonAction = when {
        clicks <= 1 -> single
        clicks == 2 -> double
        else -> triple
    }
}
