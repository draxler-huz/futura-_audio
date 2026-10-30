package com.example.wakeupmusic.library.scan

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.wakeupmusic.library.db.ExistingTrack
import com.example.wakeupmusic.library.db.LibraryRepository
import com.example.wakeupmusic.library.db.MEDIASTORE_ROOT
import com.example.wakeupmusic.library.db.ScanCaches
import com.example.wakeupmusic.library.db.ScanWrite

/**
 * Reads the library (F06).
 *
 * Source 1, the system media index (MediaStore): needs only READ_MEDIA_AUDIO. Tags come from the index's own
 * columns. Genres come from the genre tables, read once per scan.
 *
 * Source 2, folders the user added with the system picker (SAF tree URIs). This covers files MediaStore does not
 * classify as audio, such as some DSD files. Tags come from MediaMetadataRetriever, and unknown fields stay empty.
 *
 * Incremental: a file whose modified time and size are unchanged is only marked as seen. Files that disappear, or
 * that the current rules reject, are deleted once the walk has finished without being cancelled.
 *
 * Writes go in batches of [BATCH] rows, so readers never wait for a long transaction. Runs on a background thread only.
 */
class MediaScanner(private val context: Context, private val repo: LibraryRepository) {

    class Outcome(val seen: Int, val added: Int, val updated: Int, val removed: Int, val cancelled: Boolean)

    fun scanMediaStore(
        policy: ScanPolicy,
        gen: Long,
        cancelled: () -> Boolean,
        onProgress: (Int, String) -> Unit,
    ): Outcome {
        val genres = readGenres()
        val existing = repo.existingTracks(MEDIASTORE_ROOT)
        val caches = ScanCaches()
        val batch = WriteBatch(repo, gen)
        val tally = Tally()
        batch.begin()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.IS_ALARM,
                MediaStore.Audio.Media.IS_NOTIFICATION,
                MediaStore.Audio.Media.IS_RINGTONE,
            )
            val cursor = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
            if (cursor == null) {
                // The index was unavailable. An empty result must not be read as "every file was removed".
                tally.cancelled = true
            }
            cursor?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val iModified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val iAlarm = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
                    val iNotif = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
                    val iRing = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)

                    while (c.moveToNext()) {
                        if (cancelled()) {
                            tally.cancelled = true
                            break
                        }
                        val path = c.getString(iData) ?: continue
                        val duration = c.getLong(iDuration)
                        val systemFolder = c.getInt(iAlarm) != 0 || c.getInt(iNotif) != 0 || c.getInt(iRing) != 0
                        if (!policy.accepts(path, duration, systemFolder)) continue

                        val id = c.getLong(iId)
                        val size = c.getLong(iSize)
                        val modified = c.getLong(iModified) * 1000L
                        val old = existing[path]
                        tally.seen++
                        if (old != null && old.dateModifiedMs == modified && old.sizeBytes == size) {
                            batch.pending += old.id
                        } else {
                            val raw = c.getInt(iTrack)
                            val title = c.getString(iTitle)?.takeIf { it.isNotBlank() }
                                ?: path.substringAfterLast('/').substringBeforeLast('.')
                            repo.upsertScanned(
                                ScanWrite(
                                    root = MEDIASTORE_ROOT,
                                    mediaId = id,
                                    path = path,
                                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                                    folderPath = path.substringBeforeLast('/', ""),
                                    title = title,
                                    artist = c.getString(iArtist).orEmpty(),
                                    album = c.getString(iAlbum).orEmpty(),
                                    genre = genres[id].orEmpty(),
                                    trackNo = if (raw >= 1000) raw % 1000 else raw,
                                    discNo = if (raw >= 1000) raw / 1000 else 0,
                                    year = c.getInt(iYear),
                                    durationMs = duration,
                                    sizeBytes = size,
                                    dateModifiedMs = modified,
                                ),
                                gen,
                                caches,
                            )
                            if (old == null) tally.added++ else tally.updated++
                        }
                        if (tally.seen % PROGRESS_EVERY == 0) onProgress(tally.seen, labelOf(path))
                        batch.step()
                    }
                }
            batch.flush()
        } catch (e: Exception) {
            batch.abort()
            throw e
        }
        var removed = 0
        if (!tally.cancelled) removed = repo.deleteUnseen(MEDIASTORE_ROOT, gen)
        return Outcome(tally.seen, tally.added, tally.updated, removed, tally.cancelled)
    }

    /** Walks one folder the user added with the system picker. [treeUri] comes from OpenDocumentTree. */
    fun scanTree(
        treeUri: Uri,
        policy: ScanPolicy,
        gen: Long,
        cancelled: () -> Boolean,
        onProgress: (Int, String) -> Unit,
    ): Outcome {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return Outcome(0, 0, 0, 0, cancelled = true)
        if (!root.canRead()) {
            // Access was lost (grant revoked, or the drive is unmounted). Keep the tracks; do not treat it as removal.
            return Outcome(0, 0, 0, 0, cancelled = true)
        }
        val rootKey = treeUri.toString()
        val ctx = TreeContext(
            rootKey = rootKey,
            gen = gen,
            policy = policy,
            existing = repo.existingTracks(rootKey),
            caches = ScanCaches(),
            batch = WriteBatch(repo, gen),
            tally = Tally(),
            cancelled = cancelled,
            onProgress = onProgress,
        )
        ctx.batch.begin()
        try {
            walk(root, FolderGrants.pathForTree(treeUri) ?: rootKey, ctx, depth = 0)
            ctx.batch.flush()
        } catch (e: Exception) {
            ctx.batch.abort()
            throw e
        }
        var removed = 0
        if (!ctx.tally.cancelled) removed = repo.deleteUnseen(rootKey, gen)
        return Outcome(ctx.tally.seen, ctx.tally.added, ctx.tally.updated, removed, ctx.tally.cancelled)
    }

    private class TreeContext(
        val rootKey: String,
        val gen: Long,
        val policy: ScanPolicy,
        val existing: HashMap<String, ExistingTrack>,
        val caches: ScanCaches,
        val batch: WriteBatch,
        val tally: Tally,
        val cancelled: () -> Boolean,
        val onProgress: (Int, String) -> Unit,
    )

    private fun walk(dir: DocumentFile, folderPath: String, ctx: TreeContext, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (f in dir.listFiles()) {
            if (ctx.tally.cancelled || ctx.cancelled()) {
                ctx.tally.cancelled = true
                return
            }
            val name = f.name ?: continue
            if (f.isDirectory) {
                walk(f, "$folderPath/$name", ctx, depth + 1)
                continue
            }
            if (!f.isFile || !isAudio(f.type, name)) continue

            val path = "$folderPath/$name"
            // A file MediaStore already indexes keeps that entry. Never index the same file twice.
            if (repo.rootOfPath(path) == MEDIASTORE_ROOT) continue

            val modified = f.lastModified()
            val size = f.length()
            val old = ctx.existing[path]
            if (old != null && old.dateModifiedMs == modified && old.sizeBytes == size) {
                if (!ctx.policy.accepts(path, old.durationMs, false)) continue
                ctx.tally.seen++
                ctx.batch.pending += old.id
            } else {
                val meta = readMeta(f.uri)
                if (!ctx.policy.accepts(path, meta.durationMs, false)) continue
                ctx.tally.seen++
                repo.upsertScanned(
                    ScanWrite(
                        root = ctx.rootKey,
                        mediaId = null,
                        path = path,
                        uri = f.uri.toString(),
                        folderPath = folderPath,
                        title = meta.title ?: name.substringBeforeLast('.'),
                        artist = meta.artist,
                        album = meta.album,
                        genre = meta.genre,
                        trackNo = 0,
                        discNo = 0,
                        year = meta.year,
                        durationMs = meta.durationMs,
                        sizeBytes = size,
                        dateModifiedMs = modified,
                    ),
                    ctx.gen,
                    ctx.caches,
                )
                if (old == null) ctx.tally.added++ else ctx.tally.updated++
            }
            if (ctx.tally.seen % PROGRESS_EVERY == 0) ctx.onProgress(ctx.tally.seen, name)
            ctx.batch.step()
        }
    }

    private class Meta(
        val title: String?,
        val artist: String,
        val album: String,
        val genre: String,
        val year: Int,
        val durationMs: Long,
    )

    private fun readMeta(uri: Uri): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            Meta(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0,
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            // Formats the retriever cannot open (some DSD files) are still indexed, with the file name as the title.
            Meta(null, "", "", "", 0, 0L)
        } finally {
            r.release()
        }
    }

    /** genre id to name, for every track that has a genre. One query per genre, once per scan. */
    private fun readGenres(): Map<Long, String> {
        val out = HashMap<Long, String>()
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null,
        )?.use { g ->
            while (g.moveToNext()) {
                val genreId = g.getLong(0)
                val name = g.getString(1) ?: continue
                val members = MediaStore.Audio.Genres.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, genreId)
                resolver.query(members, arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)?.use { m ->
                    while (m.moveToNext()) out.putIfAbsent(m.getLong(0), name)
                }
            }
        }
        return out
    }

    private fun labelOf(path: String): String = path.substringAfterLast('/')

    private fun isAudio(mime: String?, name: String): Boolean =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private class Tally {
        var seen = 0
        var added = 0
        var updated = 0
        var cancelled = false
    }

    /**
     * One open transaction at a time, committed every [BATCH] rows. A commit also writes the pending "seen" marks for
     * files that did not change, so an interrupted scan loses at most one batch.
     */
    private class WriteBatch(private val repo: LibraryRepository, private val gen: Long) {
        private var open = false
        private var n = 0
        val pending = ArrayList<Long>()
    
        fun begin() {
            if (!open) {
                repo.beginBatch()
                open = true
                n = 0
            }
        }

        fun step() {
            n++
            if (n >= BATCH) {
                flush()
                begin()
            }
        }

        fun flush() {
            if (!open) return
            repo.markSeen(pending, gen)
            pending.clear()
            repo.endBatch(true)
            open = false
        }

        fun abort() {
            if (open) repo.endBatch(false)
            open = false
            pending.clear()
        }
    }

    companion object {
        const val BATCH = 300
        private const val PROGRESS_EVERY = 200
        private const val MAX_DEPTH = 12
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "alac", "dsf", "dff",
        )
    }
}
