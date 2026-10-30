package com.example.wakeupmusic.library.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.wakeupmusic.library.db.LibraryDatabase
import com.example.wakeupmusic.library.db.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs library scans on a background dispatcher, one at a time (F06). The screen reads [progress] for its header, so
 * the interface never waits on a scan (Rule 7). Progress also goes to a notification.
 *
 * A scan that the process kills is not resumed. The next scan is incremental, so it picks up where this one stopped.
 */
object ScanCoordinator {

    data class Progress(val running: Boolean = false, val message: String = "Library not scanned yet", val done: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile private var cancelRequested = false
    private val state = MutableStateFlow(Progress())

    val progress: StateFlow<Progress> = state.asStateFlow()

    /** Starts a scan unless one is already running. Safe to call from any thread. */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        cancelRequested = false
        scope.launch { runScan(app) }
    }

    fun cancel() {
        cancelRequested = true
    }

    private fun runScan(app: Context) {
        val notifier = ScanNotifier(app)
        try {
            val repo = LibraryRepository(LibraryDatabase.get(app))
            repo.ensureBuiltinPlaylists()
            val policy = repo.scanPolicy()
            val gen = System.currentTimeMillis()
            val scanner = MediaScanner(app, repo)
            val cancelled = { cancelRequested }
            val report: (Int, String) -> Unit = { done, label ->
                state.value = Progress(true, "Scanning: $label", done)
                notifier.progress(done)
            }
            state.value = Progress(true, "Reading the media index...", 0)
            // Without READ_MEDIA_AUDIO the index cannot be read, so the MediaStore pass is skipped entirely.
            // Skipping keeps the existing MediaStore tracks, instead of treating them as removed.
            if (hasAudioPermission(app)) {
                scanner.scanMediaStore(policy, gen, cancelled, report)
            }
            for (root in repo.safRoots()) {
                if (cancelRequested) break
                scanner.scanTree(Uri.parse(root), policy, gen, cancelled, report)
            }
            repo.pruneOrphans()
            repo.rebuildFolders()
            val count = repo.trackCount()
            state.value = Progress(
                false,
                if (cancelRequested) "Scan cancelled ($count tracks so far)" else "Library: $count tracks",
                count,
            )
            notifier.finished(count)
        } catch (e: Exception) {
            state.value = Progress(false, "Scan failed (${e.javaClass.simpleName})", 0)
            notifier.failed()
        } finally {
            running.set(false)
        }
    }
}

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

/** The scan notification. Silent, low importance: it is for progress, not for alerts. */
class ScanNotifier(private val context: Context) {

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library scan", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun progress(done: Int) = post(
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Scanning music library")
            .setContentText("$done files checked")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build(),
    )

    fun finished(total: Int) = post(
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Library updated")
            .setContentText("$total tracks")
            .setAutoCancel(true)
            .build(),
    )

    fun failed() = post(
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Library scan failed")
            .setContentText("Open the library to try again")
            .setAutoCancel(true)
            .build(),
    )

    @SuppressLint("MissingPermission")
    private fun post(n: Notification) {
        // Without the permission the scan still runs. Only the notification is skipped.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val CHANNEL_ID = "library_scan"
        const val NOTIFICATION_ID = 4102
    }
}
