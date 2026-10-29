package com.example.wakeupmusic.data

/** Where a track's local copy stands. Only VERIFIED tracks may be played at alarm time. */
enum class CacheState { PENDING, VERIFIED, MISSING, CORRUPT }

/** Which stem of a multi-stem track this file is. FULL_MIX for ordinary tracks. */
enum class StemRole { FULL_MIX, DRUMS, BASS, VOCALS, OTHER }

/** How the user proves they are awake. NONE = plain dismiss (the Stage 0 behaviour). */
enum class VerificationType { NONE, MATH, STEPS, QR }

/**
 * Alarm configuration. Lives in alarms.db and knows nothing about audio files except the
 * soft reference [trackId] (a different database, so no foreign key).
 * [repeatDaysMask]: bit 0 = Monday ... bit 6 = Sunday. 0 = one-shot.
 */
data class AlarmConfig(
    val id: Long = 0L,
    val label: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val repeatDaysMask: Int = 0,
    val enabled: Boolean = false,
    val nextTriggerAtMs: Long = 0L,
    val trackId: Long? = null,
    val verificationType: VerificationType = VerificationType.NONE,
    val verificationParams: String = "",
    val updatedAtMs: Long = 0L,
)

/**
 * Audio metadata. Lives in audio_library.db and knows nothing about alarms.
 * [localPath] is always inside app-private storage. [sourceUri] is set only for tracks that
 * came from a remote source and is used only by pre-download, never at alarm time.
 */
data class TrackMetadata(
    val id: Long = 0L,
    val displayName: String,
    val localPath: String,
    val sourceUri: String? = null,
    val durationMs: Long = 0L,
    val bpm: Float? = null,
    val gainDb: Float = 0f,
    val stemGroupId: String? = null,
    val stemRole: StemRole = StemRole.FULL_MIX,
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val fileModifiedMs: Long = 0L,
    val cacheState: CacheState = CacheState.PENDING,
    val lastVerifiedMs: Long = 0L,
    val addedMs: Long = 0L,
)
