# Wake-Up Music (prototype)

## Run it
1. Open the WakeUpMusic folder in Android Studio (Hedgehog or newer).
2. Let Gradle sync. Android Studio creates the Gradle wrapper automatically.
3. Run on a physical Android 10+ device. Alarms and full-screen intents do not behave fully on emulators.
4. Set the time, tap "Set alarm", and lock the phone. Test with the screen off.

## What it demonstrates
- AlarmManager.setAlarmClock for precise timing
- Full-screen intent notification from a BroadcastReceiver
- showWhenLocked and turnScreenOn on the alarm screen
- Audio stream USAGE_ALARM with transient exclusive audio focus
- Driving mode gated by the agreement in docs/Driving_Mode_Agreement.md
- Volume warning above 80%
- Always-visible dismiss button

## Known limits (be honest about these in your report)
- Not compiled or tested by the author. Check it on a device before submitting.
- Playback runs only while the alarm activity is alive. A production app needs a foreground service.
- Uses built-in device sounds, not custom music.
- Does not implement the fade-in, pitch change, sync, or interval features from the 40 problems.
- On Android 14+, the full-screen intent permission may need to be granted in Settings.
- Play Store approval depends on the app being an alarm or timer as its core function.
