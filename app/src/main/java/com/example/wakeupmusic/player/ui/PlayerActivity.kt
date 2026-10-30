package com.example.wakeupmusic.player.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.wakeupmusic.library.ui.LibraryActivity
import com.example.wakeupmusic.player.data.PlayerStateStore
import com.example.wakeupmusic.player.data.SourceOpener
import com.example.wakeupmusic.player.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture

/**
 * Player entry point (feature 1.7). Connects a MediaController to [PlaybackService], then shows the now-playing
 * screen, with settings and the format self-test one tap away.
 *
 * Notification permission (POST_NOTIFICATIONS) is asked for on the first play, and only after the explanation in
 * [PermissionExplanations] has been shown. Saying no still lets playback run.
 */
class PlayerActivity : ComponentActivity() {

    private val controllerState = mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                controllerState.value = try {
                    future.get()
                } catch (e: Exception) {
                    null
                }
            },
            ContextCompat.getMainExecutor(this),
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerRoot(
                        controller = controllerState.value,
                        onOpenLibrary = { startActivity(Intent(this, LibraryActivity::class.java)) },
                    )
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

private enum class PlayerSection { NOW, SETTINGS, FORMAT }

/**
 * Picker filter. The old audio filter greyed out files whose MIME type the provider cannot name (FLAC, Opus, M4A on many providers).
 * The decoders sniff the format themselves, so every file is offered and the wrong ones simply fail to open.
 */
internal val PICKER_MIME_TYPES = arrayOf("*/*")

@Composable
private fun PlayerRoot(controller: MediaController?, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlayerStateStore.create(context) }
    var section by rememberSaveable { mutableStateOf(PlayerSection.NOW) }
    var showNotificationRationale by rememberSaveable { mutableStateOf(false) }
    var notificationDeclined by rememberSaveable { mutableStateOf(false) }

    fun doPlay() {
        controller?.play()
    }

    fun requestPlay() {
        if (hasNotificationPermission(context) || notificationDeclined) {
            doPlay()
        } else {
            // Explain first, then ask. The dialog text is PermissionExplanations.NOTIFICATION_RATIONALE_TEXT.
            showNotificationRationale = true
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Granted or not, playback continues. Only the notification depends on the answer.
        doPlay()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { SourceOpener.persistReadAccess(context, it) }
            // Opening files replaces the current queue.
            controller?.setMediaItems(uris.map { MediaItem.fromUri(it) }, 0, 0L)
            controller?.prepare()
            requestPlay()
        }
    }

    BackHandler(enabled = section != PlayerSection.NOW) { section = PlayerSection.NOW }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = {
                showNotificationRationale = false
                notificationDeclined = true
                doPlay()
            },
            title = { Text(PermissionExplanations.NOTIFICATION_RATIONALE_TITLE) },
            text = { Text(PermissionExplanations.NOTIFICATION_RATIONALE_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(PermissionExplanations.NOTIFICATION_RATIONALE_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    notificationDeclined = true
                    doPlay()
                }) { Text(PermissionExplanations.NOTIFICATION_RATIONALE_DISMISS) }
            },
        )
    }

    when (section) {
        PlayerSection.NOW -> PlayerScreen(
            controller = controller,
            onPickFiles = { filePicker.launch(PICKER_MIME_TYPES) },
            onPlay = { requestPlay() },
            onOpenSettings = { section = PlayerSection.SETTINGS },
            onOpenFormatTest = { section = PlayerSection.FORMAT },
            onOpenLibrary = onOpenLibrary,
        )
        PlayerSection.SETTINGS -> SettingsScreen(
            store = store,
            onBack = { section = PlayerSection.NOW },
        )
        PlayerSection.FORMAT -> FormatTestScreen(
            onBack = { section = PlayerSection.NOW },
        )
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
