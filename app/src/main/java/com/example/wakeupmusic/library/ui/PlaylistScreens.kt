package com.example.wakeupmusic.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.example.wakeupmusic.library.db.KIND_SMART
import com.example.wakeupmusic.library.db.PlaylistRow
import com.example.wakeupmusic.library.db.QueueRow
import com.example.wakeupmusic.library.db.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Playlists tab: built-in smart playlists, your playlists, and your queues (F09). */
@Composable
fun PlaylistsPanel(actions: LibraryActions, open: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistRow>>(emptyList()) }
    var queues by remember { mutableStateOf<List<QueueRow>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        val loaded = withContext(Dispatchers.IO) { actions.repo.playlists() to actions.queues.queues() }
        playlists = loaded.first
        queues = loaded.second
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Smart playlists") }
        items(playlists.filter { it.kind == KIND_SMART }, key = { "s${it.id}" }) { p ->
            Line(p.name, "Built in") { open(Route.Playlist(p.id, p.name, manual = false)) }
        }

        item {
            SectionRow("Your playlists") {
                OutlinedButton(onClick = { creating = "PLAYLIST" }) { Text("New playlist") }
            }
        }
        items(playlists.filter { it.kind != KIND_SMART }, key = { "p${it.id}" }) { p ->
            Line(p.name, "${p.count} tracks") { open(Route.Playlist(p.id, p.name, manual = true)) }
        }

        item {
            SectionRow("Queues") {
                OutlinedButton(onClick = { creating = "QUEUE" }) { Text("New queue") }
            }
        }
        items(queues, key = { "q${it.id}" }) { q ->
            Line(if (q.isActive) "${q.name} (active)" else q.name, "${q.count} tracks") { open(Route.Queue(q.id)) }
        }
    }

    creating?.let { kind ->
        NameDialog(
            title = if (kind == "PLAYLIST") "New playlist" else "New queue",
            initial = "",
            confirm = "Create",
            onDismiss = { creating = null },
        ) { name ->
            creating = null
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (kind == "PLAYLIST") actions.repo.createPlaylist(name) else actions.queues.create(name)
                }
                refresh++
            }
        }
    }
}

/**
 * One queue: its tracks, swipe to remove, up and down to reorder, play, save as playlist, make active, delete.
 * Reordering uses buttons. Drag-to-reorder is listed as pending in STATUS.md.
 */
@Composable
fun QueueBody(actions: LibraryActions, controller: MediaController?, queueId: Long, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var info by remember { mutableStateOf<QueueRow?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(queueId, refresh) {
        val loaded = withContext(Dispatchers.IO) {
            actions.queues.items(queueId) to actions.queues.queues().firstOrNull { it.id == queueId }
        }
        items = loaded.first
        info = loaded.second
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (info?.isActive == true) "Active queue · ${items.size} tracks" else "${items.size} tracks",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.play(controller, items, 0, queueId) }, enabled = items.isNotEmpty()) {
                    Text("Play queue")
                }
                OutlinedButton(onClick = { saving = true }, enabled = items.isNotEmpty()) { Text("Save as playlist") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info?.isActive != true) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { actions.queues.setActive(queueId) }
                            refresh++
                        }
                    }) { Text("Make active") }
                }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { actions.queues.delete(queueId) }
                        onDeleted()
                    }
                }) { Text("Delete queue") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { i, t -> "$i-${t.id}" }) { i, track ->
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        scope.launch {
                            actions.removeFromQueue(controller, queueId, i)
                            refresh++
                        }
                    }
                    true
                })
                SwipeToDismissBox(
                    state = dismiss,
                    backgroundContent = { Text("Remove", modifier = Modifier.padding(16.dp)) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(enabled = i > 0, onClick = {
                            scope.launch {
                                actions.moveInQueue(controller, queueId, i, i - 1)
                                refresh++
                            }
                        }) { Text("▲") }
                        TextButton(enabled = i < items.lastIndex, onClick = {
                            scope.launch {
                                actions.moveInQueue(controller, queueId, i, i + 1)
                                refresh++
                            }
                        }) { Text("▼") }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (saving) {
        NameDialog(
            title = "Save queue as playlist",
            initial = info?.name.orEmpty(),
            confirm = "Save",
            onDismiss = { saving = false },
        ) { name ->
            saving = false
            scope.launch {
                withContext(Dispatchers.IO) { actions.queues.saveAsPlaylist(queueId, name.ifEmpty { "Playlist" }) }
                message = "Saved as a playlist."
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SectionRow(text: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun Line(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}
