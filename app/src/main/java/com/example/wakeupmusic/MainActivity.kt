package com.example.wakeupmusic

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import com.example.wakeupmusic.data.AlarmConfig
import com.example.wakeupmusic.data.AlarmDao
import com.example.wakeupmusic.data.FuturaStorage
import com.example.wakeupmusic.data.Preflight
import com.example.wakeupmusic.player.ui.PlayerActivity
import com.example.wakeupmusic.skeleton.SkeletonActivity
import java.util.Calendar

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var timePicker: TimePicker
    private lateinit var status: TextView
    private lateinit var trackStatus: TextView
    private val storage by lazy { FuturaStorage.get(this) }

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

          root.addView(TextView(this).apply { text = "Sound (0 = off / flat)"; setPadding(0, 32, 0, 0) })
        addSlider(root, "Fade-in seconds", "fade_seconds", 0, 60, 0)
        addSlider(root, "Bass dB", "eq_low", -6, 6, 0)
        addSlider(root, "Mid dB", "eq_mid", -6, 6, 0)
        addSlider(root, "Treble dB", "eq_high", -6, 6, 0)

        // Alarm track (Stage 0.3): copied into private storage, hashed and verified, so the alarm never needs the network
        root.addView(TextView(this).apply { text = "Alarm track"; setPadding(0, 32, 0, 0) })
        trackStatus = TextView(this)
        root.addView(trackStatus)
        root.addView(Button(this).apply { text = "Choose alarm track"; setOnClickListener { pickTrack() } })
        root.addView(Button(this).apply { text = "Use built-in sounds"; setOnClickListener { clearTrack() } })

        // Stage 0: skeleton test tone and engine status. Keeps the alarm UI untouched.
        root.addView(Button(this).apply {
            text = "Audio engine test (Stage 0)"
            setOnClickListener { startActivity(Intent(this@MainActivity, SkeletonActivity::class.java)) }
        })

        // Stage 1: the music player (separate from the alarm).
        root.addView(Button(this).apply {
            text = "Music player (Stage 1)"
            setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }
        })

        root.addView(Button(this).apply {
      text = "Set alarm"
      setOnClickListener {
          try {
              setAlarm()
          } catch (e: Throwable) {
              status.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
          }
      }
  })
        root.addView(Button(this).apply { text = "Cancel alarm"; setOnClickListener { cancelAlarm() } })

        status = TextView(this).apply { setPadding(0, 32, 0, 0) }
        root.addView(status)
        showStatus()
        refreshTrackStatus()

        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
    }

    override fun onResume() {
        super.onResume()
        runPreflight()
    }

    // Slider that stores an integer in prefs. The label shows the current value.
    private fun addSlider(root: LinearLayout, label: String, key: String, lo: Int, hi: Int, default: Int) {
        val valueView = TextView(this)
        val current = prefs.getInt(key, default)
        valueView.text = "$label: $current"
        root.addView(valueView)
        root.addView(SeekBar(this).apply {
            max = hi - lo
            progress = current - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    valueView.text = "$label: ${p + lo}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt(key, (s?.progress ?: 0) + lo).apply()
                }
            })
        })
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

        // Record the configuration, then verify the track now and again shortly before the alarm
        val existing = storage.alarms.get(AlarmDao.PRIMARY_ID) ?: AlarmConfig(id = AlarmDao.PRIMARY_ID)
        storage.alarms.upsert(
            existing.copy(
                hour = timePicker.hour, minute = timePicker.minute,
                enabled = true, nextTriggerAtMs = cal.timeInMillis,
            )
        )
        Preflight.schedule(this, cal.timeInMillis)
        runPreflight()
        showStatus()
    }

    private fun cancelAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent())
        prefs.edit().remove("alarm_time").apply()
        storage.alarms.setEnabled(AlarmDao.PRIMARY_ID, false)
        Preflight.cancel(this)
        showStatus()
    }

    private fun pickTrack() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQ_PICK_TRACK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQ_PICK_TRACK || resultCode != RESULT_OK) return
        trackStatus.text = "Copying and verifying..."
        Thread {
            val message = try {
                val track = storage.trackStore.importFromUri(uri)
                val alarm = storage.alarms.get(AlarmDao.PRIMARY_ID)
                    ?: AlarmConfig(id = AlarmDao.PRIMARY_ID, hour = timePicker.hour, minute = timePicker.minute)
                storage.alarms.upsert(alarm.copy(trackId = track.id))
                Preflight.run(this)
                null
            } catch (e: Throwable) {
                "Could not use that file: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                refreshTrackStatus()
                if (message != null) trackStatus.text = message
            }
        }.start()
    }

    private fun clearTrack() {
        storage.alarms.get(AlarmDao.PRIMARY_ID)?.let { storage.alarms.setTrack(it.id, null) }
        refreshTrackStatus()
    }

    private fun runPreflight() {
        Thread {
            runCatching { Preflight.run(this) }
            runOnUiThread { refreshTrackStatus() }
        }.start()
    }

    // Shows the stored state only. The hash check itself runs in Preflight, off the UI thread.
    private fun refreshTrackStatus() {
        val trackId = storage.alarms.get(AlarmDao.PRIMARY_ID)?.trackId
        val track = trackId?.let { storage.tracks.get(it) }
        trackStatus.text = when {
            trackId == null -> "Built-in device sounds"
            track == null -> "Chosen track is missing. The built-in fallback sound will play."
            track.cacheState == com.example.wakeupmusic.data.CacheState.VERIFIED ->
                "${track.displayName} (verified offline)"
            else -> "${track.displayName} (${track.cacheState.name.lowercase()}). The built-in fallback sound will play."
        }
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

    private companion object {
        const val REQ_PICK_TRACK = 3001
    }
}
