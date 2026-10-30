package com.example.wakeupmusic.library.ui

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.example.wakeupmusic.library.db.LibraryRepository
import com.example.wakeupmusic.library.db.TrackRow
import com.example.wakeupmusic.library.queue.QueueStore
import com.example.wakeupmusic.library.tags.TagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the library screens can do. Controller calls run on the caller's (main) thread. Database work moves to the IO
 * dispatcher inside each function.
 *
 * Play-queue sync: when a stored queue is loaded into the player, [loadedQueueId] is set. Play next, add to queue,
 * remove and move then change both the stored queue and the player, so playback is not interrupted.
 */
class LibraryActions(
    val repo: LibraryRepository,
    val queues: QueueStore,
    val editor: TagEditor,
) {
    @Volatile var loadedQueueId: Long? = null
    @Volatile var lastBatchId: Long? = null

    /** Replaces the player's list with [tracks] and starts at [start]. */
    fun play(controller: MediaController?, tracks: List<TrackRow>, start: Int, queueId: Long?) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.setMediaItems(tracks.map { it.toMediaItem() }, start.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        c.play()
        loadedQueueId = queueId
    }

    suspend fun playNext(controller: MediaController?, track: TrackRow) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(c, listOf(track), 0, null)
            return
        }
        val current = c.currentMediaItemIndex
        c.addMediaItem(current + 1, track.toMediaItem())
        loadedQueueId?.let { q ->
            withContext(Dispatchers.IO) { queues.insertAfter(q, current, listOf(track.id)) }
        }
    }

    /** Adds to the loaded queue when one is playing, otherwise to the active stored queue. */
    suspend fun addToEnd(controller: MediaController?, track: TrackRow) {
        val loaded = loadedQueueId
        withContext(Dispatchers.IO) {
            queues.append(loaded ?: queues.activeQueueId(), listOf(track.id))
        }
        if (loaded != null) controller?.addMediaItem(track.toMediaItem())
    }

    suspend fun removeFromQueue(controller: MediaController?, queueId: Long, index: Int) {
        withContext(Dispatchers.IO) { queues.removeAt(queueId, index) }
        if (loadedQueueId == queueId) controller?.removeMediaItem(index)
    }

    suspend fun moveInQueue(controller: MediaController?, queueId: Long, from: Int, to: Int) {
        withContext(Dispatchers.IO) { queues.move(queueId, from, to) }
        if (loadedQueueId == queueId) controller?.moveMediaItem(from, to)
    }

    suspend fun setRating(trackId: Long, rating: Int) = withContext(Dispatchers.IO) { repo.setRating(trackId, rating) }

    suspend fun toggleFavorite(track: TrackRow) = withContext(Dispatchers.IO) { repo.setFavorite(track.id, !track.favorite) }

    suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>) =
        withContext(Dispatchers.IO) { repo.appendToPlaylist(playlistId, trackIds) }
}

/** A library row as a player item. Only the URI and display fields are set, so the player builds its own metadata. */
fun TrackRow.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(Uri.parse(uri))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build(),
    )
    .build()
