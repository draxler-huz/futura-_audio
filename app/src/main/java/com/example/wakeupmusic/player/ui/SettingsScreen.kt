package com.example.wakeupmusic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wakeupmusic.player.data.PlayerStateStore
import com.example.wakeupmusic.player.service.ButtonAction
import com.example.wakeupmusic.player.service.ClickMapping

/**
 * Player settings (feature 1.6): what a single, double and triple click does on a headset or Bluetooth button.
 * Each tap on a row moves to the next action. The change is saved at once, and the service reads it on the next press.
 *
 * The permission section explains each permission before anything asks for it (spec 1.4).
 */
@Composable
fun SettingsScreen(
    store: PlayerStateStore,
    onBack: () -> Unit,
) {
    var mapping by remember { mutableStateOf(store.readMapping()) }

    fun update(newMapping: ClickMapping) {
        mapping = newMapping
        store.saveMapping(newMapping)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Player settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("Headset and Bluetooth buttons", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap a row to change its action.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { update(mapping.copy(single = nextAction(mapping.single))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Single click: ${label(mapping.single)}") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { update(mapping.copy(double = nextAction(mapping.double))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Double click: ${label(mapping.double)}") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { update(mapping.copy(triple = nextAction(mapping.triple))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Triple click: ${label(mapping.triple)}") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { update(ClickMapping()) }) { Text("Reset to defaults") }

        Spacer(Modifier.height(24.dp))
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        PermissionRow(PermissionExplanations.READ_MEDIA_AUDIO_TITLE, PermissionExplanations.READ_MEDIA_AUDIO_TEXT)
        PermissionRow(PermissionExplanations.POST_NOTIFICATIONS_TITLE, PermissionExplanations.POST_NOTIFICATIONS_TEXT)
        PermissionRow(PermissionExplanations.FOREGROUND_SERVICE_TITLE, PermissionExplanations.FOREGROUND_SERVICE_TEXT)
        PermissionRow(
            PermissionExplanations.FOREGROUND_SERVICE_MEDIA_PLAYBACK_TITLE,
            PermissionExplanations.FOREGROUND_SERVICE_MEDIA_PLAYBACK_TEXT,
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun PermissionRow(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(text, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
}

/** The next action in enum order, wrapping around. Pure, so it is easy to read and to test. */
internal fun nextAction(current: ButtonAction): ButtonAction {
    val all = ButtonAction.entries
    return all[(current.ordinal + 1) % all.size]
}

internal fun label(action: ButtonAction): String = when (action) {
    ButtonAction.PLAY_PAUSE -> "Play or pause"
    ButtonAction.NEXT -> "Next track"
    ButtonAction.PREVIOUS -> "Previous track"
    ButtonAction.SHUFFLE -> "Toggle shuffle"
    ButtonAction.NONE -> "Nothing"
}
