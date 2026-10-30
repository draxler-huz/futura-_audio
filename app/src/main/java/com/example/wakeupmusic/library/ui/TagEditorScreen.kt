package com.example.wakeupmusic.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wakeupmusic.library.db.TrackRow
import com.example.wakeupmusic.library.tags.TagEditor
import com.example.wakeupmusic.library.tags.TagFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tag editor (F10). One track fills the fields with its values. Several tracks start blank, and a blank field means
 * "leave it as it is", so a batch changes only what you type.
 *
 * Writing is disabled until the TagLib build is in. The screen says so, and it never changes the index for a file that
 * was not written.
 */
@Composable
fun TagEditorBody(actions: LibraryActions, trackIds: List<Long>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var trackNo by remember { mutableStateOf("") }
    var discNo by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(trackIds) {
        tracks = withContext(Dispatchers.IO) { actions.repo.tracksInOrder(trackIds) }
        tracks.singleOrNull()?.let { t ->
            title = t.title
            artist = t.artist
            album = t.album
            genre = t.genre
            trackNo = if (t.trackNo > 0) t.trackNo.toString() else ""
            discNo = if (t.discNo > 0) t.discNo.toString() else ""
            year = if (t.year > 0) t.year.toString() else ""
        }
        loaded = true
    }

    val writable = actions.editor.writerAvailable
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            if (tracks.size == 1) "Editing one track" else "Editing ${tracks.size} tracks. Leave a field blank to keep it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!writable) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tag writing is not in this build yet. It needs the TagLib build (see STATUS.md), so nothing " +
                    "you type here is saved.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        Field("Title", title) { title = it }
        Field("Artist", artist) { artist = it }
        Field("Album", album) { album = it }
        Field("Genre", genre) { genre = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Track", trackNo, Modifier.weight(1f)) { trackNo = it.filter(Char::isDigit) }
            Field("Disc", discNo, Modifier.weight(1f)) { discNo = it.filter(Char::isDigit) }
            Field("Year", year, Modifier.weight(1f)) { year = it.filter(Char::isDigit).take(4) }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val fields = TagFields(
                    title = title.trim().ifEmpty { null },
                    artist = artist.trim().ifEmpty { null },
                    album = album.trim().ifEmpty { null },
                    genre = genre.trim().ifEmpty { null },
                    trackNo = trackNo.toIntOrNull(),
                    discNo = discNo.toIntOrNull(),
                    year = year.toIntOrNull(),
                )
                scope.launch {
                    val out = withContext(Dispatchers.IO) { actions.editor.applyBatch(context.applicationContext, tracks, fields) }
                    if (out.written > 0) actions.lastBatchId = out.batchId
                    message = describe(out.written, out.failed, out.message)
                }
            },
            enabled = loaded && tracks.isNotEmpty() && writable,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply to ${tracks.size} track${if (tracks.size == 1) "" else "s"}") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val id = actions.lastBatchId ?: return@OutlinedButton
                scope.launch {
                    val out = withContext(Dispatchers.IO) { actions.editor.undo(context.applicationContext, id) }
                    if (out.failed == 0) actions.lastBatchId = null
                    message = "Undo: " + describe(out.written, out.failed, out.message)
                }
            },
            enabled = writable && actions.lastBatchId != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Undo last batch") }

        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun describe(written: Int, failed: Int, detail: String?): String {
    val base = "Updated $written, failed $failed."
    return if (detail.isNullOrBlank()) base else "$base $detail"
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.padding(vertical = 4.dp),
    )
}
