package com.example.wakeupmusic.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** alarms.db: alarm configurations only. Audio metadata is in AudioLibraryDatabase. */
class AlarmDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "alarms.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE alarm_configs (
                id INTEGER PRIMARY KEY,
                label TEXT NOT NULL DEFAULT '',
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                repeat_days_mask INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 0,
                next_trigger_at INTEGER NOT NULL DEFAULT 0,
                track_id INTEGER,
                verification_type TEXT NOT NULL DEFAULT 'NONE',
                verification_params TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Add stepwise migrations here, never drop user data.
    }

    companion object {
        const val VERSION = 1
    }
}

class AlarmDao(private val helper: AlarmDatabase) {

    fun upsert(a: AlarmConfig): Long {
        val cv = ContentValues().apply {
            if (a.id != 0L) put("id", a.id)
            put("label", a.label)
            put("hour", a.hour)
            put("minute", a.minute)
            put("repeat_days_mask", a.repeatDaysMask)
            put("enabled", if (a.enabled) 1 else 0)
            put("next_trigger_at", a.nextTriggerAtMs)
            if (a.trackId == null) putNull("track_id") else put("track_id", a.trackId)
            put("verification_type", a.verificationType.name)
            put("verification_params", a.verificationParams)
            put("updated_at", System.currentTimeMillis())
        }
        return helper.writableDatabase.insertWithOnConflict(
            "alarm_configs", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun get(id: Long): AlarmConfig? =
        helper.readableDatabase.query("alarm_configs", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }

    fun enabled(): List<AlarmConfig> =
        helper.readableDatabase.query("alarm_configs", null, "enabled = 1", null, null, null, "next_trigger_at ASC")
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    fun setEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("alarm_configs", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setTrack(id: Long, trackId: Long?) {
        val cv = ContentValues().apply {
            if (trackId == null) putNull("track_id") else put("track_id", trackId)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("alarm_configs", cv, "id = ?", arrayOf(id.toString()))
    }

    private fun read(c: Cursor) = AlarmConfig(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        label = c.getString(c.getColumnIndexOrThrow("label")),
        hour = c.getInt(c.getColumnIndexOrThrow("hour")),
        minute = c.getInt(c.getColumnIndexOrThrow("minute")),
        repeatDaysMask = c.getInt(c.getColumnIndexOrThrow("repeat_days_mask")),
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
        nextTriggerAtMs = c.getLong(c.getColumnIndexOrThrow("next_trigger_at")),
        trackId = c.getColumnIndexOrThrow("track_id").let { if (c.isNull(it)) null else c.getLong(it) },
        verificationType = runCatching {
            VerificationType.valueOf(c.getString(c.getColumnIndexOrThrow("verification_type")))
        }.getOrDefault(VerificationType.NONE),
        verificationParams = c.getString(c.getColumnIndexOrThrow("verification_params")),
        updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    companion object {
        /** The single alarm the current UI edits. */
        const val PRIMARY_ID = 1L
    }
}
