package com.futura.audio.camouflage

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.audio.AlarmCoordinator
import com.futura.audio.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

val OLED_BLACK = Color(0xFF000000)
val OLED_GREEN = Color(0xFF00FF00)

@Composable
fun AlarmCamouflageScreen(
    modifier: Modifier = Modifier,
    onDashboardAccess: () -> Unit
) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }

    var fakeTime by remember { mutableStateOf(java.util.Calendar.getInstance()) }
    var alarmTime by remember { mutableStateOf(java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 0) }) }

    var secretGesture by remember { mutableStateOf<List<Long>>(emptyList()) }
    val gestureThreshold = 6

    // Tick clock every second
    LaunchedEffect(Unit) {
        var running = true
        while (running) {
            kotlinx.coroutines.delay(1000)
            fakeTime = java.util.Calendar.getInstance()
        }
    }

    // Keypad input handling
    val handleKeypadInput = { key: String ->
        when (key) {
            "C" -> { secretGesture = emptyList() }
            "→" -> {
                if (secretGesture.size >= gestureThreshold) {
                    onDashboardAccess()
                }
            }
            else -> secretGesture = (secretGesture + key.toLong()).takeLast(gestureThreshold * 2)
        }
    }

    // Secret gesture detection via long press + drag
    val detectGesture = Modifier
        .pointerInput(Unit) {
            detectLongPress { offset ->
                if (secretGesture.isEmpty()) {
                    secretGesture = (secretGesture + 1L)
                }
            }
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OLED_BLACK)
            .then(detectGesture)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Top fake clock
        AnalogClockFake(
            time = fakeTime,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        )

        Spacer(Modifier.height(32.dp))

        // Alarm time display
        Text(
            text = String.format(
                "%02d:%02d",
                alarmTime.get(java.util.Calendar.HOUR_OF_DAY),
                alarmTime.get(java.util.Calendar.MINUTE)
            ),
            color = OLED_GREEN,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Text(
            text = if (secretGesture.isNotEmpty()) "GESTURE ${secretGesture.size}/$gestureThreshold" else "SWIPE RIGHT >",
            color = Color(0xFF666666),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.weight(1f))

        // Keypad overlay
        KeyPadOverlay(
            onKey = { handleKeypadInput(it) }
        )

        Spacer(Modifier.height(24.dp))

        // Start alarm button (hidden behind gesture)
        Button(
            onClick = { AlarmCoordinator.start(context, alarmTime.timeInMillis) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
        ) {
            Text("SET ALARM", color = OLED_GREEN, fontSize = 18.sp)
        }
    }
}

@Composable
fun AnalogClockFake(
    time: java.util.Calendar,
    modifier: Modifier = Modifier
) {
    val hour = (time.get(java.util.Calendar.HOUR_OF_DAY) % 12).toFloat()
    val minute = time.get(java.util.Calendar.MINUTE).toFloat()
    val second = time.get(java.util.Calendar.SECOND).toFloat()

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = min(centerX, centerY) * 0.9f

        // Draw clock outline
        drawCircle(
            color = OLED_GREEN,
            center = Offset(centerX, centerY),
            radius = radius,
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )

        // Draw 12 hour marks
        for (i in 0..11) {
            val angle = (i * 30 - 90) * PI / 180
            val innerRadius = radius * 0.8
            val outerRadius = radius * 0.9
            val x1 = centerX + innerRadius * cos(angle).toFloat()
            val y1 = centerY + innerRadius * sin(angle).toFloat()
            val x2 = centerX + outerRadius * cos(angle).toFloat()
            val y2 = centerY + outerRadius * sin(angle).toFloat()
            drawLine(OLED_GREEN, Offset(x1, y1), Offset(x2, y2), 4f)
        }

        // Hour hand
        val hourAngle = (hour * 30 + minute * 0.5 - 90) * PI / 180
        val hourLen = radius * 0.5f
        drawLine(
            color = OLED_GREEN,
            start = Offset(centerX, centerY),
            end = Offset(
                centerX + hourLen * cos(hourAngle).toFloat(),
                centerY + hourLen * sin(hourAngle).toFloat()
            ),
            strokeWidth = 8f
        )

        // Minute hand
        val minuteAngle = (minute * 6 - 90) * PI / 180
        val minuteLen = radius * 0.7f
        drawLine(
            color = OLED_GREEN,
            start = Offset(centerX, centerY),
            end = Offset(
                centerX + minuteLen * cos(minuteAngle).toFloat(),
                centerY + minuteLen * sin(minuteAngle).toFloat()
            ),
            strokeWidth = 5f
        )

        // Second hand
        val secondAngle = (second * 6 - 90) * PI / 180
        val secondLen = radius * 0.85f
        drawLine(
            color = OLED_GREEN,
            start = Offset(centerX, centerY),
            end = Offset(
                centerX + secondLen * cos(secondAngle).toFloat(),
                centerY + secondLen * sin(secondAngle).toFloat()
            ),
            strokeWidth = 2f
        )

        // Center dot
        drawCircle(
            color = OLED_GREEN,
            center = Offset(centerX, centerY),
            radius = 12f
        )
    }
}

@Composable
fun KeyPadOverlay(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keys = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
        listOf("C", "0", "→")
    )

    Column(modifier = modifier.fillMaxWidth()) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { key ->
                    Button(
                        onClick = { onKey(key) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222))
                    ) {
                        Text(
                            text = key,
                            color = OLED_GREEN,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SecretGesturePrompt(
    onDismiss: () -> Unit,
    onUnlock: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        confirmButton = {
            TextButton(onClick = { onUnlock() }) {
                Text("UNLOCK", color = OLED_GREEN)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("CANCEL", color = OLED_GREEN)
            }
        },
        title = { Text("ENTER SEQUENCE", color = OLED_GREEN) },
        text = { Text("Swipe right 6+ times to unlock", color = Color(0xFF666666)) },
        containerColor = OLED_BLACK,
        tonalElevation = 0.dp
    )
}