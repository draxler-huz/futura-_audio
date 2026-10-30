package com.example.wakeupmusic.player.data

import android.content.Context
import android.net.Uri

/**
 * Opens audio sources for the native engine (feature 1.2: files are opened from descriptors, not paths).
 *
 * OWNERSHIP: the descriptor returned by [openNativeOwnedFd] belongs to the native side. The engine closes it.
 * Never close it on the Kotlin side, and never use it after passing it to the engine.
 */
object SourceOpener {

    /**
     * Opens [uri] read-only and detaches the raw descriptor. Returns null if the URI cannot be opened.
     * Blocking I/O: call from a background thread, or from the control thread.
     */
    fun openNativeOwnedFd(context: Context, uri: Uri): Int? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
    } catch (e: Exception) {
        null
    }

    /**
     * Keeps read access to [uri] across process restarts, so a saved queue can be reopened. The picker grants
     * temporary access only, so this has to be called when the user picks a file. Failures are ignored, because
     * a file that cannot be persisted still plays this session.
     */
    fun persistReadAccess(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Not persistable (some providers refuse). Nothing to do.
        }
    }
}
