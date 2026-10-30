package com.example.wakeupmusic.library.tags

import android.content.Context
import android.net.Uri
import com.example.wakeupmusic.library.db.LibraryRepository
import com.example.wakeupmusic.library.db.TrackRow
import org.json.JSONObject

/**
 * Tag values to write. A null field means "leave it as it is", which is what a batch edit needs.
 *
 * Comment, lyrics and album artist are not here yet. They need the TagLib build (see STATUS.md).
 */
data class TagFields(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
) {
    fun toJson(): String = JSONObject()
        .put("title", title ?: "")
        .put("artist", artist ?: "")
        .put("album", album ?: "")
        .put("genre", genre ?: "")
        .put("track_no", trackNo ?: 0)
        .put("disc_no", discNo ?: 0)
        .put("year", year ?: 0)
        .toString()

    companion object {
        /** Every field of a track as it is in the index. Used for the undo backup. */
        fun from(t: TrackRow): TagFields = TagFields(
            title = t.title,
            artist = t.artist,
            album = t.album,
            genre = t.genre,
            trackNo = t.trackNo,
            discNo = t.discNo,
            year = t.year,
        )

        fun fromJson(json: String): TagFields {
            val o = JSONObject(json)
            return TagFields(
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                genre = o.optString("genre"),
                trackNo = o.optInt("track_no"),
                discNo = o.optInt("disc_no"),
                year = o.optInt("year"),
            )
        }
    }
}

/**
 * Writes tags into a file. The real implementation is TagLib through JNI (F10). It is not in this build yet, so
 * [PendingTagWriter] is used and the editor stays disabled. The editor never changes the index for a file it has
 * not written.
 */
interface TagWriter {
    val available: Boolean
    suspend fun write(context: Context, uri: Uri, fields: TagFields): Result<Unit>
}

object PendingTagWriter : TagWriter {
    override val available: Boolean = false

    override suspend fun write(context: Context, uri: Uri, fields: TagFields): Result<Unit> =
        Result.failure(UnsupportedOperationException("Tag writing needs the TagLib build, which is not in this stage yet."))
}

/**
 * Batch tag edits with a one-tap undo (F10). Each batch first stores the old values, then writes each file. The index
 * is updated only for files that were written. Blocking I/O: call from a background thread.
 */
class TagEditor(private val repo: LibraryRepository, private val writer: TagWriter = PendingTagWriter) {

    val writerAvailable: Boolean get() = writer.available

    class Outcome(val batchId: Long, val written: Int, val failed: Int, val message: String?)

    suspend fun applyBatch(context: Context, tracks: List<TrackRow>, fields: TagFields): Outcome {
        val batchId = System.currentTimeMillis()
        if (!writer.available) {
            return Outcome(batchId, 0, tracks.size, "Tag writing is not in this build yet.")
        }
        repo.saveTagBackups(batchId, tracks)
        var written = 0
        var failed = 0
        var message: String? = null
        for (t in tracks) {
            if (!t.isMediaStore) {
                failed++
                message = message ?: "Files added from a folder cannot be edited yet."
                continue
            }
            val result = writer.write(context, Uri.parse(t.uri), fields)
            if (result.isSuccess) {
                repo.applyTagsToRow(t.id, fields)
                written++
            } else {
                failed++
                message = message ?: result.exceptionOrNull()?.message
            }
        }
        repo.pruneOrphans()
        return Outcome(batchId, written, failed, message)
    }

    /** Puts back the old values of a batch, in the files and in the index. */
    suspend fun undo(context: Context, batchId: Long): Outcome {
        if (!writer.available) return Outcome(batchId, 0, 0, "Tag writing is not in this build yet.")
        var written = 0
        var failed = 0
        var message: String? = null
        for ((trackId, json) in repo.tagBackups(batchId)) {
            val track = repo.trackById(trackId) ?: continue
            val old = TagFields.fromJson(json)
            if (!track.isMediaStore) {
                failed++
                continue
            }
            val result = writer.write(context, Uri.parse(track.uri), old)
            if (result.isSuccess) {
                repo.applyTagsToRow(trackId, old)
                written++
            } else {
                failed++
                message = message ?: result.exceptionOrNull()?.message
            }
        }
        repo.pruneOrphans()
        if (failed == 0) repo.deleteTagBackups(batchId)
        return Outcome(batchId, written, failed, message)
    }
}
