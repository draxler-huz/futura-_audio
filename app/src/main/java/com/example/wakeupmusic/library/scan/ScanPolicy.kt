package com.example.wakeupmusic.library.scan

/**
 * Folder rules for the scanner (F06). Pure logic, so it is unit tested on the JVM.
 *
 * - A whitelist, when it has entries, limits the library to those folders.
 * - A blacklist removes whole folders.
 * - Files shorter than [minDurationMs] are skipped (voice notes). An unknown duration (0) is kept.
 * - Ringtone, alarm and notification folders are always skipped.
 */
data class ScanPolicy(
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
) {
    fun accepts(path: String, durationMs: Long, systemFolderFlag: Boolean): Boolean {
        if (systemFolderFlag || inSystemFolder(path)) return false
        if (durationMs > 0L && durationMs < minDurationMs) return false
        if (blacklist.any { isUnder(path, it) }) return false
        if (whitelist.isNotEmpty() && whitelist.none { isUnder(path, it) }) return false
        return true
    }

    companion object {
        const val DEFAULT_MIN_DURATION_MS = 30_000L

        private val SYSTEM_FOLDERS = setOf("ringtones", "alarms", "notifications")

        fun inSystemFolder(path: String): Boolean = path.split('/').any { it.lowercase() in SYSTEM_FOLDERS }

        /** True when [path] is [folder] or sits below it. "/Music/Song" does not match "/Music/Songs". */
        fun isUnder(path: String, folder: String): Boolean {
            val f = folder.trimEnd('/')
            return f.isNotEmpty() && (path == f || path.startsWith("$f/"))
        }
    }
}
