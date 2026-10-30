package com.example.wakeupmusic.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.example.wakeupmusic.library.db.FolderRow
import com.example.wakeupmusic.library.db.SortKey
import com.example.wakeupmusic.library.db.TrackFilter
import com.example.wakeupmusic.library.db.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folder tree (F08). Shows the folders built from each file's location. Tapping a folder opens it. "Play folder"
 * plays everything below it.
 */
@Composable
fun FolderBrowser(
    actions: LibraryActions,
    controller: MediaController?,
    onEditAll: (List<TrackRow>) -> Unit,
) {
    val trail = remember { mutableStateListOf<String?>(null) }
    val path = trail.last()
    var children by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var files by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var pickFor by remember { mutableStateOf<List<Long>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, refresh) {
        val loaded = withContext(Dispatchers.IO) {
            val folders = actions.repo.folderChildren(path)
            val tracks = if (path == null) emptyList() else actions.repo.tracks(TrackFilter.FolderOf(path, recursive = false), SortKey.TITLE)
            folders to tracks
        }
        children = loaded.first
        files = loaded.second
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trail.size > 1) {
                TextButton(onClick = { trail.removeAt(trail.lastIndex) }) { Text("Up") }
            }
            Text(
                path?.substringAfterLast('/') ?: "Folders",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (path != null) {
                Button(onClick = {
                    scope.launch {
                        val all = withContext(Dispatchers.IO) {
                            actions.repo.tracks(TrackFilter.FolderOf(path, recursive = true), SortKey.TITLE)
                        }
                        actions.play(controller, all, 0, null)
                    }
                }) { Text("Play folder") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(children, key = { "f:${it.path}" }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { trail.add(f.path) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(f.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.trackCount}", style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider()
            }
            itemsIndexed(files, key = { i, t -> "$i-${t.id}" }) { i, track ->
                SongRow(
                    track = track,
                    actions = actions,
                    controller = controller,
                    onPlay = { actions.play(controller, files, i, null) },
                    onChanged = { refresh++ },
                    onEdit = { onEditAll(listOf(track)) },
                    onAddToPlaylist = { pickFor = listOf(track.id) },
                )
                HorizontalDivider()
            }
        }
    }

    pickFor?.let { ids -> AddToPlaylistDialog(actions, ids) { pickFor = null } }
}
