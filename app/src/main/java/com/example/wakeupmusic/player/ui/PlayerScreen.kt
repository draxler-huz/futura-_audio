package com.example.wakeupmusic.player.ui

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

/**
 * Now-playing screen (feature 1.7): title, artist, seek bar, play/pause, next, previous, volume.
 *
 * The screen reads the controller on a short loop instead of listening for every event. That keeps it simple, and
 * the seek bar and the time labels stay correct without extra state.
 *
 * [onPlay] is passed in, so the activity can show the notification explanation before the first play.
 */
@Composable
fun PlayerScreen(
    controller: MediaController?,
    onPickFiles: () -> Unit,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFormatTest: () -> Unit,
) {
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var hasItems by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var volume by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            title = c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing"
            artist = c.mediaMetadata.artist?.toString().orEmpty()
            isPlaying = c.isPlaying
            hasItems = c.mediaItemCount > 0
            positionMs = c.currentPosition.coerceAtLeast(0L)
            durationMs = c.duration.takeIf { it > 0L } ?: 0L
            if (dragFraction == null) volume = c.volume
            delay(POLL_MS)
        }
    }

    val enabled = controller != null && hasItems
    val fraction = dragFraction
        ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (controller == null) {
            Text("Connecting to the player...", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }

        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (artist.isNotEmpty()) Text(artist, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))

        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { f ->
                    controller?.seekTo((f * durationMs).toLong())
                    positionMs = (f * durationMs).toLong()
                }
                dragFraction = null
            },
            enabled = enabled && durationMs > 0L,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs))
            Text(if (durationMs > 0L) formatTime(durationMs) else "--:--")
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { controller?.seekToPrevious() }, enabled = enabled) { Text("Previous") }
            Button(
                onClick = { if (isPlaying) controller?.pause() else onPlay() },
                enabled = enabled,
            ) { Text(if (isPlaying) "Pause" else "Play") }
            OutlinedButton(onClick = { controller?.seekToNext() }, enabled = enabled) { Text("Next") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Volume ${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                controller?.volume = it
            },
            enabled = controller != null,
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFiles, enabled = controller != null, modifier = Modifier.fillMaxWidth()) {
            Text("Open audio files")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) { Text("Player settings") }
        TextButton(onClick = onOpenFormatTest) { Text("Format self-test") }
    }
}

private const val POLL_MS = 250L

/** m:ss for values under an hour, h:mm:ss otherwise. */
internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
