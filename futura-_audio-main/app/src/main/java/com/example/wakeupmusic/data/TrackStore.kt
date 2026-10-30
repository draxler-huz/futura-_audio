package com.example.wakeupmusic.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Result of checking a track's local copy. */
sealed class Verification {
    object Ok : Verification()
    data class Bad(val state: CacheState, val reason: String) : Verification()
}

/**
 * Supplies the bytes of a remote track. The app module contains no network code (see
 * ci/guards/network_guard.sh), so a future network module registers an implementation through
 * [FuturaStorage.trackFetcher]. It is only ever called from pre-download, never at alarm time.
 */
fun interface TrackFetcher {
    /** Writes the remote track to [destination]. Returns false on any failure. */
    fun fetch(sourceUri: String, destination: File): Boolean
}

/** Owns the files in app-private storage and keeps them in step with the audio library database. */
class TrackStore(context: Context, private val dao: TrackDao) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "tracks").apply { mkdirs() }

    /** Copies a user-picked audio file into private storage, verifies it, and records it. */
    @Throws(IOException::class)
    fun importFromUri(uri: Uri): TrackMetadata {
        val name = displayName(uri)
        val part = File(dir, "import_${System.nanoTime()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
            input.use { src ->
                part.outputStream().use { dst ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        dst.write(buf, 0, n)
                    }
                    dst.fd.sync()
                }
            }
            val sha = hex(digest.digest())
            val duration = probeDurationMs(part) ?: throw IOException("Not a playable audio file")

            dao.findBySha256(sha)?.let { existing ->
                if (verify(existing) == Verification.Ok) return existing   // already stored, reuse it
            }

            val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val dest = File(dir, "${sha.take(16)}_${System.currentTimeMillis()}" + if (ext.isEmpty()) "" else ".$ext")
            if (!part.renameTo(dest)) throw IOException("Cannot store file")
            val now = System.currentTimeMillis()
            val track = TrackMetadata(
                displayName = name,
                localPath = dest.absolutePath,
                durationMs = duration,
                sha256 = sha,
                sizeBytes = dest.length(),
                fileModifiedMs = dest.lastModified(),
                cacheState = CacheState.VERIFIED,
                lastVerifiedMs = now,
                addedMs = now,
            )
            return track.copy(id = dao.insert(track))
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /** Records a remote track as PENDING. Pre-download fills it in; until then it is never played. */
    fun registerRemote(displayName: String, sourceUri: String, expectedSha256: String = ""): TrackMetadata {
        val dest = File(dir, "remote_${System.currentTimeMillis()}.audio")
        val track = TrackMetadata(
            displayName = displayName,
            localPath = dest.absolutePath,
            sourceUri = sourceUri,
            sha256 = expectedSha256.lowercase(),
            cacheState = CacheState.PENDING,
            addedMs = System.currentTimeMillis(),
        )
        return track.copy(id = dao.insert(track))
    }

    fun delete(track: TrackMetadata) {
        File(track.localPath).delete()
        dao.delete(track.id)
    }

    /**
     * Checks that the local copy exists, has the recorded size and hash, and decodes.
     * Files over [hashLimitBytes] skip the full hash and are compared by modification time instead,
     * so alarm time stays fast. Pre-download verification always uses the full hash.
     */
    fun verify(track: TrackMetadata, hashLimitBytes: Long = Long.MAX_VALUE): Verification {
        val f = File(track.localPath)
        if (!f.isFile) return Verification.Bad(CacheState.MISSING, "file is not on disk")
        if (!f.canRead()) return Verification.Bad(CacheState.CORRUPT, "file is not readable")
        val len = f.length()
        if (len <= 0L) return Verification.Bad(CacheState.CORRUPT, "file is empty")
        if (track.sizeBytes > 0 && len != track.sizeBytes) {
            return Verification.Bad(CacheState.CORRUPT, "size differs from the recorded size")
        }
        if (track.cacheState == CacheState.PENDING && track.sizeBytes == 0L) {
            return Verification.Bad(CacheState.PENDING, "download has not been verified")
        }
        if (track.sha256.isNotEmpty()) {
            if (len <= hashLimitBytes) {
                val actual = try { sha256Of(f) } catch (e: IOException) {
                    return Verification.Bad(CacheState.CORRUPT, "file cannot be read")
                }
                if (!actual.equals(track.sha256, ignoreCase = true)) {
                    return Verification.Bad(CacheState.CORRUPT, "hash does not match")
                }
            } else if (track.fileModifiedMs != 0L && f.lastModified() != track.fileModifiedMs) {
                return Verification.Bad(CacheState.CORRUPT, "file was modified")
            }
        }
        if (probeDurationMs(f) == null) return Verification.Bad(CacheState.CORRUPT, "file does not decode as audio")
        return Verification.Ok
    }

    /**
     * Downloads a PENDING/MISSING/CORRUPT remote track through the registered [TrackFetcher],
     * checks it, and moves it into place atomically. Returns the updated record, or null on failure.
     */
    fun download(track: TrackMetadata, fetcher: TrackFetcher): TrackMetadata? {
        val source = track.sourceUri ?: return null
        val dest = File(track.localPath)
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            part.delete()
            if (!fetcher.fetch(source, part) || !part.isFile || part.length() <= 0L) return null
            val sha = sha256Of(part)
            if (track.sha256.isNotEmpty() && !sha.equals(track.sha256, ignoreCase = true)) return null
            val duration = probeDurationMs(part) ?: return null
            dest.delete()
            if (!part.renameTo(dest)) return null
            val updated = track.copy(
                durationMs = duration,
                sha256 = sha,
                sizeBytes = dest.length(),
                fileModifiedMs = dest.lastModified(),
                cacheState = CacheState.VERIFIED,
                lastVerifiedMs = System.currentTimeMillis(),
            )
            dao.update(updated)
            return updated
        } catch (e: Exception) {
            return null
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> if (n.isNotBlank()) return n }
            }
        }
        return uri.lastPathSegment ?: "Track"
    }

    companion object {
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** Duration in ms if the file has a decodable-looking audio track, otherwise null. */
        fun probeDurationMs(file: File): Long? {
            val ex = MediaExtractor()
            return try {
                ex.setDataSource(file.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        val us = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                        return us / 1000L
                    }
                }
                null
            } catch (e: Exception) {
                null
            } finally {
                ex.release()
            }
        }
    }
}
