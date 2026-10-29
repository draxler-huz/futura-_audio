package com.example.wakeupmusic.skeleton

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wakeupmusic.engine.StreamInfo
import com.example.wakeupmusic.engine.ToneEngine
import java.util.concurrent.Executors

/**
 * Stage 0 skeleton screen: a Play test tone button and the engine's status.
 *
 * Threading: every call into [ToneEngine] runs on [controller], a single thread. The Compose state is only written
 * on the main thread.
 */
class SkeletonActivity : ComponentActivity() {

    private val state = SkeletonState()
    private val controller = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Only touched on the controller thread.
    private var engine: ToneEngine? = null

    private val pollXRuns = object : Runnable {
        override fun run() {
            controller.execute {
                val count = engine?.xRunCount() ?: 0
                mainHandler.post { state.xruns = count }
            }
            mainHandler.postDelayed(this, XRUN_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SkeletonScreen(state = state, onTogglePlay = ::togglePlay)
            }
        }
        controller.execute { openEngine() }
    }

    private fun openEngine() {
        val e = ToneEngine()
        engine = e
        val code = e.open()
        if (code != ToneEngine.OK) {
            mainHandler.post { state.status = "Audio output could not be opened (error $code)" }
            return
        }
        val info = e.streamInfo()
        val name = outputDeviceName(info)
        mainHandler.post {
            state.info = info
            state.deviceName = name
            state.status = "Ready"
            mainHandler.post(pollXRuns)
        }
    }

    private fun togglePlay() {
        if (state.info == null) return
        val next = !state.playing
        state.playing = next
        controller.execute { engine?.setPlaying(next) }
    }

    // Maps the stream's device id to a product name. Falls back to the id when the name is unavailable.
    private fun outputDeviceName(info: StreamInfo?): String {
        val id = info?.deviceId ?: return UNKNOWN
        if (id <= 0) return UNKNOWN
        val am = getSystemService(AudioManager::class.java) ?: return "Device $id"
        val device: AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        return device?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Device $id"
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollXRuns)
        controller.execute {
            engine?.close()
            engine = null
        }
        controller.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val XRUN_POLL_MS = 500L
        const val UNKNOWN = "Unknown"
    }
}

/** Everything the screen shows. Written on the main thread only. */
private class SkeletonState {
    var playing by mutableStateOf(false)
    var status by mutableStateOf("Opening audio output...")
    var info by mutableStateOf<StreamInfo?>(null)
    var deviceName by mutableStateOf("Unknown")
    var xruns by mutableIntStateOf(0)
}

@Composable
private fun SkeletonScreen(state: SkeletonState, onTogglePlay: () -> Unit) {
    val info = state.info
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Futura Audio: engine skeleton", style = MaterialTheme.typography.headlineSmall)
            Text("Status: ${state.status}")
            Text("Audio API: ${info?.audioApi?.label ?: "-"}")
            Text("Sample rate: ${info?.let { "${it.sampleRate} Hz" } ?: "-"}")
            Text("Frames per burst: ${info?.framesPerBurst ?: "-"}")
            Text("Output device: ${state.deviceName}")
            Text("Xruns: ${state.xruns}")
            Button(onClick = onTogglePlay, enabled = info != null) {
                Text(if (state.playing) "Stop test tone" else "Play test tone")
            }
        }
    }
}
