package com.futura.audio

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.futura.audio.camouflage.AlarmCamouflageScreen
import com.futura.audio.engine.AudioEngineBridge

class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var permissionManager: PermissionManager

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.entries.all { it.value }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmScheduler = AlarmScheduler()
        permissionManager = PermissionManager()

        // Initialize alarm scheduler (registers AlarmReceiver)
        alarmScheduler.initialize(this)

        // Request POST_NOTIFICATIONS permission on Android 13+
        permissionManager.requestRequiredPermissions(this, requestPermissions)

        // Start with alarm camouflage screen
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = com.futura.audio.camouflage.OLED_GREEN)) {
                AlarmCamouflageScreen(
                    onDashboardAccess = { enterDashboard() }
                )
            }
        }
    }

    private fun enterDashboard() {
        // Start the real audio engine via the coordinator service
        AlarmCoordinator.start(this, System.currentTimeMillis() + 5000)
        Toast.makeText(this, "Dashboard accessed - engine starting", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            when (it.action) {
                "com.futura.audio.ALARM_FIRE" -> {
                    alarmScheduler.cancelAlarm(this)
                    // The AlarmReceiver handles starting the service
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}