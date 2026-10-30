package com.example.wakeupmusic.player.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wakeupmusic.engine.PlayerCode
import com.example.wakeupmusic.engine.PlayerEngine
import com.example.wakeupmusic.engine.ProbeResult
import com.example.wakeupmusic.player.data.SourceOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Format self-test (feature 1.2). Pick one file and see what the decoder makes of it: codec, bit depth, sample rate,
 * channels, duration, frames decoded and decode errors.
 *
 * The probe runs on Dispatchers.IO, because it decodes. It owns the file descriptor, so the Kotlin side passes a
 * detached one and never closes it.
 */
@Composable
fun FormatTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<FormatReport?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            fileName = uri.lastPathSegment ?: "file"
            busy = true
            report = null
            scope.launch {
                report = withContext(Dispatchers.IO) { probeUri(context, uri) }
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Format self-test", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick one audio file to see how it decodes. Nothing is played.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Pick a file")
        }

        fileName?.let {
            Spacer(Modifier.height(12.dp))
            Text("File: $it", style = MaterialTheme.typography.bodyMedium)
        }
        if (busy) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
        report?.let { r ->
            Spacer(Modifier.height(12.dp))
            r.rows.forEach { (label, value) ->
                Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** A flat list of label and value rows, ready for display. */
data class FormatReport(val rows: List<Pair<String, String>>)

/** Opens the URI and runs the native probe. Blocking: call from Dispatchers.IO only. */
internal fun probeUri(context: Context, uri: Uri): FormatReport {
    val fd = SourceOpener.openNativeOwnedFd(context, uri)
        ?: return FormatReport(listOf("Result" to "Could not open the file"))
    val r: ProbeResult = PlayerEngine.probe(fd)
    return toReport(r)
}

/** Converts a probe result to display rows. Pure, so it can be unit tested. */
internal fun toReport(r: ProbeResult): FormatReport {
    val rows = mutableListOf<Pair<String, String>>()
    rows += "Codec" to r.codec
    rows += "Sample rate" to if (r.sampleRate > 0) "${r.sampleRate} Hz" else "Unknown"
    rows += "Channels" to if (r.channels > 0) r.channels.toString() else "Unknown"
    rows += "Bit depth" to when {
        r.bitDepth > 0 -> "${r.bitDepth}-bit"
        r.lossless -> "Unknown"
        else -> "Lossy (no bit depth)"
    }
    rows += "Lossless" to if (r.lossless) "Yes" else "No"
    rows += "Duration" to if (r.durationMs >= 0) formatTime(r.durationMs) else "Unknown"
    rows += "Frames decoded" to r.framesDecoded.toString()
    rows += "Decode errors" to r.decodeErrors.toString()
    rows += "Result" to if (r.ok) "OK" else errorText(r.errorCode)
    return FormatReport(rows)
}

internal fun errorText(code: Int): String = when (code) {
    PlayerCode.ERR_UNSUPPORTED -> "Format not supported in Stage 1 (DSD files arrive in Stage 3)"
    PlayerCode.ERR_IO -> "Could not read the file"
    PlayerCode.ERR_DECODE -> "The decoder reported an error in the file"
    PlayerCode.ERR_EMPTY -> "The file has no audio"
    else -> "Failed (code $code)"
}
