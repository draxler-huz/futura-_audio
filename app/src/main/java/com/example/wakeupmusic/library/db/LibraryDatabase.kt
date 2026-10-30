package com.example.wakeupmusic.library.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * library.db: the Stage 2 library index (F07).
 *
 * This is deliberately a separate file from audio_library.db (the alarm track cache) and alarms.db, so that a library
 * rescan can never touch alarm data. WAL mode. Version 1 creates every table the stage lists. `credits` stays empty
 * until Stage 2.6, `analysis` until Stage 7 and `waveforms` until Stage 5.
 *
 * FTS5 is optional, because not every SQLite build ships it. If creating track_fts fails, search falls back to LIKE.
 */
class LibraryDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, FILE_NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA_V1.forEach { db.execSQL(it) }
        try {
            db.execSQL(FTS_SQL)
        } catch (e: android.database.SQLException) {
            // Left out on purpose. LibraryRepository checks sqlite_master before it uses track_fts.
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Add stepwise migrations here. Never drop user data.
    }

    companion object {
        const val FILE_NAME = "library.db"
        const val VERSION = 1

        @Volatile private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: LibraryDatabase(context).also { instance = it }
            }

        private const val FTS_SQL =
            "CREATE VIRTUAL TABLE track_fts USING fts5(title, artist, album, genre, folder, " +
                "tokenize = 'unicode61 remove_diacritics 2')"

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE artists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )""",
            """CREATE TABLE genres (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )""",
            """CREATE TABLE albums (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                album_artist TEXT NOT NULL DEFAULT '',
                UNIQUE(name, album_artist)
            )""",
            """CREATE TABLE folders (
                path TEXT PRIMARY KEY,
                parent_path TEXT,
                name TEXT NOT NULL,
                track_count INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL UNIQUE,
                root TEXT NOT NULL,
                media_id INTEGER,
                uri TEXT NOT NULL,
                folder_path TEXT NOT NULL,
                title TEXT NOT NULL,
                album_id INTEGER REFERENCES albums(id) ON DELETE SET NULL,
                artist_id INTEGER REFERENCES artists(id) ON DELETE SET NULL,
                genre_id INTEGER REFERENCES genres(id) ON DELETE SET NULL,
                track_no INTEGER NOT NULL DEFAULT 0,
                disc_no INTEGER NOT NULL DEFAULT 0,
                year INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                date_modified_ms INTEGER NOT NULL DEFAULT 0,
                added_ms INTEGER NOT NULL DEFAULT 0,
                rating INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                seen_gen INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL CHECK (kind IN ('MANUAL', 'SMART')),
                builtin_key TEXT UNIQUE,
                rules_json TEXT NOT NULL DEFAULT '',
                created_ms INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE playlist_items (
                playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                PRIMARY KEY (playlist_id, position)
            )""",
            """CREATE TABLE queues (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0,
                created_ms INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE queue_items (
                queue_id INTEGER NOT NULL REFERENCES queues(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                PRIMARY KEY (queue_id, position)
            )""",
            """CREATE TABLE play_stats (
                track_id INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
                play_count INTEGER NOT NULL DEFAULT 0,
                last_played_ms INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE credits (
                track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                person TEXT NOT NULL,
                PRIMARY KEY (track_id, role, person)
            )""",
            """CREATE TABLE scan_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL CHECK (kind IN ('WHITELIST', 'BLACKLIST', 'SAF_ROOT', 'MIN_DURATION_MS')),
                value TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                UNIQUE (kind, value)
            )""",
            """CREATE TABLE analysis (
                track_id INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
                payload TEXT
            )""",
            """CREATE TABLE waveforms (
                track_id INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
                payload BLOB
            )""",
            """CREATE TABLE tag_backups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_id INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                old_json TEXT NOT NULL,
                created_ms INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_tracks_folder ON tracks(folder_path)",
            "CREATE INDEX idx_tracks_title ON tracks(title COLLATE NOCASE)",
            "CREATE INDEX idx_tracks_album ON tracks(album_id)",
            "CREATE INDEX idx_tracks_artist ON tracks(artist_id)",
            "CREATE INDEX idx_tracks_genre ON tracks(genre_id)",
            "CREATE INDEX idx_tracks_added ON tracks(added_ms)",
            "CREATE INDEX idx_tracks_modified ON tracks(date_modified_ms)",
            "CREATE INDEX idx_tracks_duration ON tracks(duration_ms)",
            "CREATE INDEX idx_tracks_year ON tracks(year)",
            "CREATE INDEX idx_tracks_rating ON tracks(rating)",
            "CREATE INDEX idx_tracks_favorite ON tracks(favorite)",
            "CREATE INDEX idx_tracks_root_seen ON tracks(root, seen_gen)",
            "CREATE INDEX idx_tracks_media ON tracks(media_id)",
            "CREATE INDEX idx_tracks_uri ON tracks(uri)",
            "CREATE INDEX idx_folders_parent ON folders(parent_path)",
            "CREATE INDEX idx_play_stats_count ON play_stats(play_count)",
            "CREATE INDEX idx_play_stats_last ON play_stats(last_played_ms)",
            "CREATE INDEX idx_tag_backups_batch ON tag_backups(batch_id)",
        )
    }
}
