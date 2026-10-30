package com.example.wakeupmusic.library.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.wakeupmusic.library.db.LibraryDatabase
import com.example.wakeupmusic.library.db.LibraryRepository
import com.example.wakeupmusic.library.db.TrackFilter
import com.example.wakeupmusic.library.queue.QueueStore
import com.example.wakeupmusic.library.scan.FolderGrants
import com.example.wakeupmusic.library.scan.ScanCoordinator
import com.example.wakeupmusic.library.tags.TagEditor
import com.example.wakeupmusic.player.ui.PermissionExplanations
import com.example.wakeupmusic.player.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the library is. The stack holds the screens opened so far, so Back returns one step. */
sealed interface Route {
    data object Home : Route
    data class Tracks(val title: String, val filter: TrackFilter) : Route
    data class Playlist(val id: Long, val title: String, val manual: Boolean) : Route
    data class Queue(val id: Long) : Route
    data class Tags(val trackIds: List<Long>) : Route
    data object Rules : Route
}

/**
 * The music library (Stage 2). A separate screen from the player and from the alarm. It connects to the same playback
 * service as the player, so playing from here and from the player share one queue.
 *
 * READ_MEDIA_AUDIO is asked for here, once, after the explanation. Declining still lets you add folders.
 */
class LibraryActivity : ComponentActivity() {

    private val controllerState = mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
        controllerFuture = future
        future.addListener(
            { controllerState.value = try { future.get() } catch (e: Exception) { null } },
            ContextCompat.getMainExecutor(this),
        )
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LibraryRoot(controllerState.value)
                }
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}

@Composable
fun LibraryRoot(controller: MediaController?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember {
        // Opening the database is deferred to the first query, so creating these objects does no disk I/O here.
        val db = LibraryDatabase.get(context)
        val repo = LibraryRepository(db)
        LibraryActions(repo, QueueStore(db, repo), TagEditor(repo))
    }
    val scan by ScanCoordinator.progress.collectAsState()

    var hasAudio by remember { mutableStateOf(hasAudioPermission(context)) }
    var skipped by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudio = granted
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) { FolderGrants.addRoot(context, actions.repo, uri) }
                ScanCoordinator.start(context)
            }
        }
    }

    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val current = stack.last()
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    // Rescan whenever the library opens with access. The scan is incremental, so this is quick once the library is built.
    LaunchedEffect(hasAudio) {
        if (hasAudio) ScanCoordinator.start(context)
    }

    when {
        !hasAudio && !skipped -> PermissionGate(
            onAllow = { permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) },
            onSkip = { skipped = true },
        )
        else -> when (val r = current) {
            Route.Home -> HomeScreen(
                actions = actions,
                controller = controller,
                scan = scan,
                open = open,
                onRescan = { ScanCoordinator.start(context) },
                onAddFolder = { folderLauncher.launch(null) },
            )
            is Route.Tracks -> ListPage(r.title, back) {
                TrackListBody(
                    actions = actions,
                    controller = controller,
                    key = r,
                    sortable = true,
                    load = { sort -> actions.repo.tracks(r.filter, sort) },
                    onEditAll = { open(Route.Tags(it.map { t -> t.id })) },
                )
            }
            is Route.Playlist -> ListPage(
                title = r.title,
                onBack = back,
                trailing = {
                    if (r.manual) {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { actions.repo.deletePlaylist(r.id) }
                                back()
                            }
                        }) { Text("Delete") }
                    }
                },
            ) {
                TrackListBody(
                    actions = actions,
                    controller = controller,
                    key = r,
                    sortable = false,
                    load = { _ -> actions.repo.playlist(r.id)?.let { actions.repo.playlistTracks(it) }.orEmpty() },
                    onEditAll = { open(Route.Tags(it.map { t -> t.id })) },
                )
            }
            is Route.Queue -> ListPage("Queue", back) {
                QueueBody(actions = actions, controller = controller, queueId = r.id, onDeleted = back)
            }
            is Route.Tags -> ListPage("Edit tags", back) {
                TagEditorBody(actions = actions, trackIds = r.trackIds)
            }
            Route.Rules -> ListPage("Scan rules", back) {
                ScanRulesBody(actions = actions, onScanNow = { ScanCoordinator.start(context) })
            }
        }
    }
}

@Composable
private fun PermissionGate(onAllow: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(PermissionExplanations.LIBRARY_TITLE, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(PermissionExplanations.READ_MEDIA_AUDIO_TEXT, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text("Allow audio access") }
        TextButton(onClick = onSkip) { Text("Only show folders I add") }
    }
}

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
