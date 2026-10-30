package com.example.wakeupmusic.library.db

/** Root marker for tracks that come from the system media index (MediaStore). Other roots are SAF tree URIs. */
const val MEDIASTORE_ROOT = "MEDIASTORE"
const val KIND_MANUAL = "MANUAL"
const val KIND_SMART = "SMART"

/** Sort keys. Each [sql] is a fixed string from this file, never user input. */
enum class SortKey(val sql: String, val label: String) {
    TITLE("t.title COLLATE NOCASE, t.id", "Title"),
    ARTIST("ar.name COLLATE NOCASE, al.name COLLATE NOCASE, t.disc_no, t.track_no", "Artist"),
    ALBUM("al.name COLLATE NOCASE, t.disc_no, t.track_no", "Album"),
    ADDED("t.added_ms DESC, t.id DESC", "Date added"),
    DURATION("t.duration_ms DESC", "Length"),
    RATING("t.rating DESC, t.title COLLATE NOCASE", "Rating"),
    PLAY_COUNT("COALESCE(ps.play_count, 0) DESC, t.title COLLATE NOCASE", "Most played"),
    LAST_PLAYED("COALESCE(ps.last_played_ms, 0) DESC", "Recently played"),
}

/** Which tracks a list shows. */
sealed interface TrackFilter {
    data object All : TrackFilter
    data class AlbumOf(val id: Long) : TrackFilter
    data class ArtistOf(val id: Long) : TrackFilter
    data class GenreOf(val id: Long) : TrackFilter
    data class FolderOf(val path: String, val recursive: Boolean) : TrackFilter
    data class Search(val text: String) : TrackFilter
}

/** One library track, joined with its album, artist, genre and play statistics. */
data class TrackRow(
    val id: Long,
    val path: String,
    val uri: String,
    val folderPath: String,
    val title: String,
    val album: String,
    val albumId: Long?,
    val artist: String,
    val artistId: Long?,
    val genre: String,
    val genreId: Long?,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val durationMs: Long,
    val addedMs: Long,
    val rating: Int,
    val favorite: Boolean,
    val playCount: Int,
    val lastPlayedMs: Long,
    val dateModifiedMs: Long,
    val root: String,
) {
    /** Only MediaStore tracks can be written through the media write request. */
    val isMediaStore: Boolean get() = root == MEDIASTORE_ROOT
}

data class NamedRow(val id: Long, val name: String, val count: Int, val subtitle: String)

data class FolderRow(val path: String, val parentPath: String?, val name: String, val trackCount: Int)

data class PlaylistRow(
    val id: Long,
    val name: String,
    val kind: String,
    val builtinKey: String?,
    val rulesJson: String,
    val count: Int,
)

data class QueueRow(val id: Long, val name: String, val isActive: Boolean, val count: Int)

data class ScanRuleRow(val id: Long, val kind: String, val value: String, val enabled: Boolean)

/** What the scanner already knows about a file, keyed by path. */
data class ExistingTrack(val id: Long, val dateModifiedMs: Long, val sizeBytes: Long, val durationMs: Long)

/** Values the scanner collected for one file, before any database ids are assigned. */
data class ScanWrite(
    val root: String,
    val mediaId: Long?,
    val path: String,
    val uri: String,
    val folderPath: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
)
