package com.example.wakeupmusic.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** What the player shows and publishes for one track. */
data class TrackMeta(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artwork: ByteArray?,
) {
    companion object {
        fun fallback(uri: Uri): TrackMeta = TrackMeta(
            title = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Unknown track",
            artist = null,
            album = null,
            durationMs = 0L,
            artwork = null,
        )
    }
}

/**
 * Result-like value. [meta] is always usable. [error] is set when the file could not be read for metadata,
 * in which case [meta] holds fallbacks.
 */
data class MetaResult(val meta: TrackMeta, val error: String?)

/**
 * Reads title, artist, album, duration and embedded artwork from a content URI (feature 1.4). Never throws.
 *
 * Artwork is scaled down to at most [ARTWORK_MAX_PX] pixels and re-encoded as JPEG. Media sessions pass artwork
 * through a binder transaction, which has a size limit of about 1 MB, so large cover images would crash the
 * controller.
 *
 * Blocking I/O: call from a background thread, never from the main thread.
 */
class TrackMetadataReader(context: Context) {

    private val appContext = context.applicationContext

    fun read(uri: Uri): MetaResult {
        val fallback = TrackMeta.fallback(uri)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: displayName(uri)
                ?: fallback.title
            val meta = TrackMeta(
                title = title,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                artwork = retriever.embeddedPicture?.let { downscaleArtwork(it) },
            )
            MetaResult(meta, error = null)
        } catch (e: Exception) {
            // Fall back to the file name. The track can still play, because the decoder does its own probing.
            MetaResult(fallback.copy(title = displayName(uri) ?: fallback.title), error = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ARTWORK_MAX_PX = 512
        private const val ARTWORK_JPEG_QUALITY = 85

        /** Returns a JPEG of at most [ARTWORK_MAX_PX] on its long side, or null if the bytes are not an image. */
        internal fun downscaleArtwork(bytes: ByteArray): ByteArray? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val longSide = maxOf(bounds.outWidth, bounds.outHeight)
                if (longSide <= 0) {
                    null
                } else {
                    var sample = 1
                    while (longSide / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bitmap: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    bitmap?.let { bmp ->
                        ByteArrayOutputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
                            bmp.recycle()
                            out.toByteArray()
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
