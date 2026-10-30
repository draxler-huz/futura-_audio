package com.example.wakeupmusic.library.queue

import android.content.ContentValues
import com.example.wakeupmusic.library.db.LibraryDatabase
import com.example.wakeupmusic.library.db.LibraryRepository
import com.example.wakeupmusic.library.db.QueueRow
import com.example.wakeupmusic.library.db.TrackRow

/**
 * Named queues (F09 multi-queue). Each queue is an ordered list of track ids in library.db. Exactly one queue is
 * active at a time. Changes rewrite the whole ordered list in one transaction, which keeps positions gap-free.
 *
 * Blocking I/O. Call from a background thread.
 */
class QueueStore(private val database: LibraryDatabase, private val repo: LibraryRepository) {

    fun queues(): List<QueueRow> = database.readableDatabase.rawQuery(
        """SELECT q.id, q.name, q.is_active,
                  (SELECT COUNT(*) FROM queue_items qi WHERE qi.queue_id = q.id)
           FROM queues q ORDER BY q.created_ms, q.id""",
        null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(QueueRow(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3))) }
    }

    /** The active queue. Creates a queue named "Queue" the first time, so there is always one. */
    fun activeQueueId(): Long {
        val id = database.readableDatabase.rawQuery("SELECT id FROM queues WHERE is_active = 1 LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        return if (id >= 0) id else create("Queue", makeActive = true)
    }

    fun create(name: String, makeActive: Boolean = false): Long {
        val w = database.writableDatabase
        var id = -1L
        w.beginTransaction()
        try {
            if (makeActive) w.execSQL("UPDATE queues SET is_active = 0")
            id = w.insertOrThrow("queues", null, ContentValues().apply {
                put("name", name.trim().ifEmpty { "Queue" })
                put("is_active", if (makeActive) 1 else 0)
                put("created_ms", System.currentTimeMillis())
            })
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return id
    }

    fun rename(queueId: Long, name: String) {
        database.writableDatabase.update(
            "queues",
            ContentValues().apply { put("name", name.trim().ifEmpty { "Queue" }) },
            "id = ?",
            arrayOf(queueId.toString()),
        )
    }

    fun setActive(queueId: Long) {
        database.writableDatabase.execSQL(
            "UPDATE queues SET is_active = CASE WHEN id = ? THEN 1 ELSE 0 END",
            arrayOf<Any?>(queueId),
        )
    }

    fun delete(queueId: Long) {
        database.writableDatabase.delete("queues", "id = ?", arrayOf(queueId.toString()))
        val remaining = queues()
        if (remaining.isEmpty()) {
            create("Queue", makeActive = true)
        } else if (remaining.none { it.isActive }) {
            setActive(remaining.first().id)
        }
    }

    fun trackIds(queueId: Long): List<Long> = database.readableDatabase.rawQuery(
        "SELECT track_id FROM queue_items WHERE queue_id = ? ORDER BY position",
        arrayOf(queueId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun items(queueId: Long): List<TrackRow> = repo.tracksInOrder(trackIds(queueId))

    fun append(queueId: Long, ids: List<Long>) = write(queueId) { it + ids }

    /** Inserts [ids] after the item at [index]. */
    fun insertAfter(queueId: Long, index: Int, ids: List<Long>) = write(queueId) { list ->
        val at = (index + 1).coerceIn(0, list.size)
        list.subList(0, at) + ids + list.subList(at, list.size)
    }

    fun removeAt(queueId: Long, index: Int) = write(queueId) { list ->
        list.filterIndexed { i, _ -> i != index }
    }

    fun move(queueId: Long, from: Int, to: Int) = write(queueId) { list ->
        val m = list.toMutableList()
        val moved = m.removeAt(from)
        m.add(to.coerceIn(0, m.size), moved)
        m
    }

    /** Saves the queue as a manual playlist and returns the playlist id. The queue itself is kept. */
    fun saveAsPlaylist(queueId: Long, name: String): Long = repo.createPlaylist(name, trackIds(queueId))

    private fun write(queueId: Long, transform: (List<Long>) -> List<Long>) {
        val updated = transform(trackIds(queueId))
        repo.rewriteOrdered("queue_items", "queue_id", queueId, updated)
    }
}
