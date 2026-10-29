package com.example.wakeupmusic

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import java.util.Calendar

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var timePicker: TimePicker
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply { text = "Wake-Up Music"; textSize = 26f })

        timePicker = TimePicker(this).apply { setIs24HourView(true) }
        root.addView(timePicker)

        // Driving mode: requires the agreement before it can be turned on
        val driving = Switch(this).apply {
            text = "Driving Mode (large controls)"
            isChecked = prefs.getBoolean("driving", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !prefs.getBoolean("agreed", false)) {
                    showAgreement { accepted ->
                        if (accepted) {
                            prefs.edit().putBoolean("agreed", true).putBoolean("driving", true).apply()
                        } else {
                            isChecked = false
                        }
                    }
                } else {
                    prefs.edit().putBoolean("driving", checked).apply()
                }
            }
        }
        root.addView(driving)

        // Volume: warning above 80%, shown once until acknowledged
        root.addView(TextView(this).apply { text = "Volume" })
        val volume = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("volume", 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val p = progress
                    if (p > 80 && !prefs.getBoolean("loud_ack", false)) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Loud volume")
                            .setMessage("Long exposure to high volume can damage hearing. Continue?")
                            .setPositiveButton("Continue") { _, _ ->
                                prefs.edit().putBoolean("loud_ack", true).apply()
                                prefs.edit().putInt("volume", p).apply()
                            }
                            .setNegativeButton("Lower") { _, _ ->
                                progress = 80
                                prefs.edit().putInt("volume", 80).apply()
                            }
                            .show()
                    } else {
                        prefs.edit().putInt("volume", p).apply()
                    }
                }
            })
        }
        root.addView(volume)

        root.addView(Button(this).apply { text = "Set alarm"; setOnClickListener { setAlarm() } })
        root.addView(Button(this).apply { text = "Cancel alarm"; setOnClickListener { cancelAlarm() } })

        status = TextView(this).apply { setPadding(0, 32, 0, 0) }
        root.addView(status)
        showStatus()

        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
    }

    private fun showAgreement(onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Driving Mode Agreement")
            .setMessage(
                "I am responsible for using my phone safely at all times. " +
                "Driving Mode shows only play, pause, skip, and dismiss. " +
                "I will not browse music or change settings while the vehicle is moving. " +
                "The app cannot detect whether I am driving. " +
                "I will turn this mode off if it is unsafe or illegal where I am."
            )
            .setPositiveButton("I Agree") { _, _ -> onResult(true) }
            .setNegativeButton("Cancel") { _, _ -> onResult(false) }
            .setCancelable(false)
            .show()
    }

    private fun setAlarm() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, timePicker.hour)
            set(Calendar.MINUTE, timePicker.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        val showIntent = PendingIntent.getActivity(
            this, 1002, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAlarmClock is the alarm-clock API: precise timing, works in Doze, no extra permission
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, showIntent), alarmIntent())
        prefs.edit().putLong("alarm_time", cal.timeInMillis).apply()
        showStatus()
    }

    private fun cancelAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent())
        prefs.edit().remove("alarm_time").apply()
        showStatus()
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 1001, Intent(this, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showStatus() {
        val t = prefs.getLong("alarm_time", 0L)
        status.text = if (t == 0L) "No alarm set" else "Alarm set for " +
            java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(t)
    }
}
