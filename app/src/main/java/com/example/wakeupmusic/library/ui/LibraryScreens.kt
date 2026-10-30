package com.example.wakeupmusic.library.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.example.wakeupmusic.library.db.KIND_MANUAL
import com.example.wakeupmusic.library.db.NamedRow
import com.example.wakeupmusic.library.db.PlaylistRow
import com.example.wakeupmusic.library.db.ScanRuleRow
import com.example.wakeupmusic.library.db.SortKey
import com.example.wakeupmusic.library.db.TrackFilter
import com.example.wakeupmusic.library.db.TrackRow
import com.example.wakeupmusic.library.scan.FolderGrants
import com.example.wakeupmusic.library.scan.ScanCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOAD_DEBOUNCE_MS = 120L

/** A page with a back button and a title. Used for every screen that is not the home tabs. */
@Composable
fun ListPage(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing()
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/** Home: scan status, search, and the six tabs (F06 to F09). */
@Composable
fun HomeScreen(
    actions: LibraryActions,
    controller: MediaController?,
    scan: ScanCoordinator.Progress,
    open: (Route) -> Unit,
    onRescan: () -> Unit,
    onAddFolder: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val tabs = listOf("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists")

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(scan.message, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRescan, enabled = !scan.running) { Text("Rescan") }
            OutlinedButton(onClick = onAddFolder) { Text("Add folder") }
            TextButton(onClick = { open(Route.Rules) }) { Text("Scan rules") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search title, artist, album, genre, folder") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )

        if (query.isNotBlank()) {
            val q = query
            Box(Modifier.fillMaxWidth().weight(1f)) {
                    TrackListBody(
                        actions = actions,
                        controller = controller,
                        key = "search:$q",
                        sortable = true,
                        load = { sort -> actions.repo.tracks(TrackFilter.Search(q), sort, limit = 200) },
                        onEditAll = { open(Route.Tags(it.map { t -> t.id })) },
                    )
            }
        } else {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, name ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    0 -> TrackListBody(
                        actions = actions,
                        controller = controller,
                        key = "songs",
                        sortable = true,
                        load = { sort -> actions.repo.tracks(TrackFilter.All, sort) },
                        onEditAll = { open(Route.Tags(it.map { t -> t.id })) },
                    )
                    1 -> NamedListBody(key = "albums", load = { actions.repo.albums() }) { row ->
                        open(Route.Tracks(row.name, TrackFilter.AlbumOf(row.id)))
                    }
                    2 -> NamedListBody(key = "artists", load = { actions.repo.artists() }) { row ->
                        open(Route.Tracks(row.name, TrackFilter.ArtistOf(row.id)))
                    }
                    3 -> NamedListBody(key = "genres", load = { actions.repo.genres() }) { row ->
                        open(Route.Tracks(row.name, TrackFilter.GenreOf(row.id)))
                    }
                    4 -> FolderBrowser(
                        actions = actions,
                        controller = controller,
                        onEditAll = { open(Route.Tags(it.map { t -> t.id })) },
                    )
                    5 -> PlaylistsPanel(actions = actions, open = open)
            }
            }
        }
    }
}

/**
 * A track list with sort, letter jump (F09 fast scroll), and per-row rating, favourite and menu.
 * [load] runs on the IO dispatcher. The list reloads when [key], the sort, or a change in a row changes.
 */
@Composable
fun TrackListBody(
    actions: LibraryActions,
    controller: MediaController?,
    key: Any,
    sortable: Boolean,
    load: (SortKey) -> List<TrackRow>,
    onEditAll: (List<TrackRow>) -> Unit,
) {
    var sort by rememberSaveable { mutableStateOf(SortKey.TITLE) }
    var refresh by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var playlistPickFor by remember { mutableStateOf<List<Long>?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(key, sort, refresh) {
        delay(LOAD_DEBOUNCE_MS)  // a short pause while typing, so each keystroke does not query
        rows = withContext(Dispatchers.IO) { load(sort) }
        loaded = true
    }

    val railOn = sortable && sort == SortKey.TITLE
    val rail = remember(rows, railOn) { if (railOn) letterIndex(rows) else emptyMap() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} tracks", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (sortable) SortMenu(sort) { sort = it }
            TextButton(onClick = { onEditAll(rows) }, enabled = rows.isNotEmpty()) { Text("Edit tags") }
        }
        if (loaded && rows.isEmpty()) {
            Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                // Index in the key, because a playlist can hold the same track twice.
                itemsIndexed(rows, key = { i, t -> "$i-${t.id}" }) { i, track ->
                    SongRow(
                        track = track,
                        actions = actions,
                        controller = controller,
                        onPlay = { actions.play(controller, rows, i, null) },
                        onChanged = { refresh++ },
                        onEdit = { onEditAll(listOf(track)) },
                        onAddToPlaylist = { playlistPickFor = listOf(track.id) },
                    )
                    HorizontalDivider()
                }
            }
            if (rail.isNotEmpty()) {
                FastScrollRail(rail) { index -> scope.launch { listState.scrollToItem(index) } }
            }
        }
    }

    playlistPickFor?.let { ids ->
        AddToPlaylistDialog(actions, ids) { playlistPickFor = null }
    }
}

/** One track row: tap to play, stars and heart to rate, and a menu for queue, playlist and tag actions. */
@Composable
internal fun SongRow(
    track: TrackRow,
    actions: LibraryActions,
    controller: MediaController?,
    onPlay: () -> Unit,
    onChanged: () -> Unit,
    onEdit: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                line.ifEmpty { track.folderPath.substringAfterLast('/') },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StarRating(track.rating) { value ->
            scope.launch {
                actions.setRating(track.id, value)
                onChanged()
            }
        }
        TextButton(onClick = {
            scope.launch {
                actions.toggleFavorite(track)
                onChanged()
            }
        }) { Text(if (track.favorite) "♥" else "♡") }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = {
                        menuOpen = false
                        scope.launch { actions.playNext(controller, track) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            actions.addToEnd(controller, track)
                            onChanged()
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    onClick = {
                        menuOpen = false
                        onAddToPlaylist()
                    },
                )
                if (track.isMediaStore) {
                    DropdownMenuItem(
                        text = { Text("Edit tags") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StarRating(value: Int, onSet: (Int) -> Unit) {
    Row {
        for (i in 1..5) {
            Text(
                if (i <= value) "★" else "☆",
                color = if (i <= value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.clickable { onSet(if (i == value) 0 else i) }.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun SortMenu(current: SortKey, onPick: (SortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sort: ${current.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortKey.entries.forEach { k ->
                DropdownMenuItem(text = { Text(k.label) }, onClick = {
                    open = false
                    onPick(k)
                })
            }
        }
    }
}

/** First row index for each letter in a title-sorted list. Non-letters go under '#'. */
private fun letterIndex(rows: List<TrackRow>): Map<Char, Int> {
    val out = LinkedHashMap<Char, Int>()
    rows.forEachIndexed { i, t ->
        val c = t.title.firstOrNull()?.uppercaseChar() ?: '#'
        out.putIfAbsent(if (c in 'A'..'Z') c else '#', i)
    }
    return out
}

/** The letters on the right edge. Tapping one scrolls to its first row. */
@Composable
private fun FastScrollRail(index: Map<Char, Int>, onJump: (Int) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(22.dp).verticalScroll(rememberScrollState()),
    ) {
        index.keys.sorted().forEach { ch ->
            Text(
                ch.toString(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { onJump(index.getValue(ch)) }.padding(vertical = 1.dp),
            )
        }
    }
}

/** Albums, artists and genres. Each row opens the tracks it holds. */
@Composable
fun NamedListBody(key: Any, load: () -> List<NamedRow>, onOpen: (NamedRow) -> Unit) {
    var rows by remember { mutableStateOf<List<NamedRow>>(emptyList()) }
    LaunchedEffect(key) { rows = withContext(Dispatchers.IO) { load() } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { row ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.subtitle.isNotBlank()) {
                        Text(row.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("${row.count}", style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun AddToPlaylistDialog(actions: LibraryActions, trackIds: List<Long>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<PlaylistRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        lists = withContext(Dispatchers.IO) { actions.repo.playlists().filter { it.kind == KIND_MANUAL } }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (lists.isEmpty()) Text("No playlists yet. Create one in the Playlists tab.")
                lists.forEach { p ->
                    TextButton(onClick = {
                        scope.launch {
                            actions.addToPlaylist(p.id, trackIds)
                            onDone()
                        }
                    }) { Text(p.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

/** Name entry for new playlists, new queues and renames. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Folder rules (F06) and the folder-add list. Rules apply after the next rescan, and the screen says so.
 * Rules use plain folder paths, so they work for folders on the phone's own storage. Other storage uses "Add folder".
 */
@Composable
fun ScanRulesBody(actions: LibraryActions, onScanNow: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<ScanRuleRow>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingKind by remember { mutableStateOf("WHITELIST") }

    LaunchedEffect(refresh) { rules = withContext(Dispatchers.IO) { actions.repo.scanRules() } }

    val rulePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val path = FolderGrants.pathForTree(uri)
            if (path == null) {
                message = "Rules work with folders on the phone's own storage. Use Add folder for other storage."
            } else {
                val kind = pendingKind
                scope.launch {
                    withContext(Dispatchers.IO) { actions.repo.addRule(kind, path) }
                    message = "Rule added. Rescan to apply it."
                    refresh++
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Rules apply after the next rescan.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Folder pickers dim the files inside a folder. That is normal: they select folders, not files. " +
                "Pick the folder itself.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            pendingKind = "WHITELIST"
            rulePicker.launch(null)
        }) { Text("Whitelist a folder") }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            pendingKind = "BLACKLIST"
            rulePicker.launch(null)
        }) { Text("Blacklist a folder") }

        Spacer(Modifier.height(16.dp))
        Text("Shortest track kept", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L, 30_000L, 60_000L).forEach { ms ->
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { actions.repo.setMinDurationMs(ms) }
                        message = "Minimum length set. Rescan to apply it."
                        refresh++
                    }
                }) { Text(if (ms == 0L) "No minimum" else "${ms / 1000} s") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Current rules", style = MaterialTheme.typography.titleSmall)
        if (rules.isEmpty()) Text("No rules. The whole library is included.")
        rules.forEach { rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.kind.replace('_', ' ').lowercase(), style = MaterialTheme.typography.labelMedium)
                    Text(rule.value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (rule.kind == "SAF_ROOT") {
                                FolderGrants.removeRoot(context, actions.repo, Uri.parse(rule.value))
                            } else {
                                actions.repo.removeRule(rule.id)
                            }
                        }
                        refresh++
                    }
                }) { Text("Remove") }
            }
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onScanNow) { Text("Rescan now") }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
