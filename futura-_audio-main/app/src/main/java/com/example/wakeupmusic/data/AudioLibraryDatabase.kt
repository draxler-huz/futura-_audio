package com.example.wakeupmusic.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** audio_library.db: track metadata only. Alarm configurations are in AlarmDatabase. */
class AudioLibraryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "audio_library.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                local_path TEXT NOT NULL,
                source_uri TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                bpm REAL,
                gain_db REAL NOT NULL DEFAULT 0,
                stem_group_id TEXT,
                stem_role TEXT NOT NULL DEFAULT 'FULL_MIX',
                sha256 TEXT NOT NULL DEFAULT '',
                size_bytes INTEGER NOT NULL DEFAULT 0,
                file_modified_ms INTEGER NOT NULL DEFAULT 0,
                cache_state TEXT NOT NULL DEFAULT 'PENDING',
                last_verified_ms INTEGER NOT NULL DEFAULT 0,
                added_ms INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_tracks_sha256 ON tracks(sha256)")
        db.execSQL("CREATE INDEX idx_tracks_stem_group ON tracks(stem_group_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Add stepwise migrations here, never drop user data.
    }

    companion object {
        const val VERSION = 1
    }
}

class TrackDao(private val helper: AudioLibraryDatabase) {

    fun insert(t: TrackMetadata): Long =
        helper.writableDatabase.insertOrThrow("tracks", null, values(t, withId = false))

    fun update(t: TrackMetadata) {
        helper.writableDatabase.update("tracks", values(t, withId = false), "id = ?", arrayOf(t.id.toString()))
    }

    fun get(id: Long): TrackMetadata? =
        helper.readableDatabase.query("tracks", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    fun findBySha256(sha: String): TrackMetadata? =
        if (sha.isEmpty()) null
        else helper.readableDatabase.query("tracks", null, "sha256 = ?", arrayOf(sha), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    fun stemGroup(groupId: String): List<TrackMetadata> =
        helper.readableDatabase.query("tracks", null, "stem_group_id = ?", arrayOf(groupId), null, null, "stem_role ASC")
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    fun all(): List<TrackMetadata> =
        helper.readableDatabase.query("tracks", null, null, null, null, null, "added_ms DESC")
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    fun setState(id: Long, state: CacheState, verifiedAtMs: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("cache_state", state.name)
            put("last_verified_ms", verifiedAtMs)
        }
        helper.writableDatabase.update("tracks", cv, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("tracks", "id = ?", arrayOf(id.toString()))
    }

    private fun values(t: TrackMetadata, withId: Boolean) = ContentValues().apply {
        if (withId) put("id", t.id)
        put("display_name", t.displayName)
        put("local_path", t.localPath)
        if (t.sourceUri == null) putNull("source_uri") else put("source_uri", t.sourceUri)
        put("duration_ms", t.durationMs)
        if (t.bpm == null) putNull("bpm") else put("bpm", t.bpm)
        put("gain_db", t.gainDb)
        if (t.stemGroupId == null) putNull("stem_group_id") else put("stem_group_id", t.stemGroupId)
        put("stem_role", t.stemRole.name)
        put("sha256", t.sha256)
        put("size_bytes", t.sizeBytes)
        put("file_modified_ms", t.fileModifiedMs)
        put("cache_state", t.cacheState.name)
        put("last_verified_ms", t.lastVerifiedMs)
        put("added_ms", t.addedMs)
    }

    private fun read(c: Cursor): TrackMetadata {
        fun col(n: String) = c.getColumnIndexOrThrow(n)
        return TrackMetadata(
            id = c.getLong(col("id")),
            displayName = c.getString(col("display_name")),
            localPath = c.getString(col("local_path")),
            sourceUri = c.getString(col("source_uri")),
            durationMs = c.getLong(col("duration_ms")),
            bpm = col("bpm").let { if (c.isNull(it)) null else c.getFloat(it) },
            gainDb = c.getFloat(col("gain_db")),
            stemGroupId = c.getString(col("stem_group_id")),
            stemRole = runCatching { StemRole.valueOf(c.getString(col("stem_role"))) }.getOrDefault(StemRole.FULL_MIX),
            sha256 = c.getString(col("sha256")),
            sizeBytes = c.getLong(col("size_bytes")),
            fileModifiedMs = c.getLong(col("file_modified_ms")),
            cacheState = runCatching { CacheState.valueOf(c.getString(col("cache_state"))) }.getOrDefault(CacheState.CORRUPT),
            lastVerifiedMs = c.getLong(col("last_verified_ms")),
            addedMs = c.getLong(col("added_ms")),
        )
    }
}
