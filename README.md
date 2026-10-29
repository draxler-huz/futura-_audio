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

## Stage 0.3: storage, database and asset management
- Two separate databases (plain SQLite, no new dependencies): `alarms.db` holds alarm configuration (time, repeat mask, enabled, next trigger, wake-up verification type and params, track reference). `audio_library.db` holds audio metadata (path, duration, BPM, gain, stem group and role, SHA-256, size, cache state).
- Tracks picked by the user are copied into app-private storage, hashed and probed. Only VERIFIED local files can play at alarm time. Alarm playback reads local storage only.
- `Preflight` re-verifies the track when the alarm is set, when the app opens, and about 30 minutes before the alarm (`PreflightReceiver`). Remote tracks are downloaded there through a `TrackFetcher`. The app module has no network code (see `network_guard.sh`), so a future network module must register the fetcher. Until then, remote tracks stay PENDING and the fallback plays.
- Fail-safe sound: `res/raw/futura_fallback.wav` (packaged uncompressed in the APK). If the chosen track is missing, corrupt, hash-mismatched or rejected by the decoder, it plays at once. If the native engine cannot play it, MediaPlayer plays it, and if that fails a tone synthesised in code (`EmergencyTone`) plays. `ci/guards/fallback_guard.sh` checks the asset exists.
- Per-track gain is applied to the volume. A boost never pushes the level above 80% unless the user's own volume is already higher.
- Not exposed in the UI yet: repeat days, wake-up verification, BPM, stems. The schema stores them.

## Known limits (be honest about these in your report)
- Not compiled or tested by the author. Check it on a device before submitting.
- Playback runs only while the alarm activity is alive. A production app needs a foreground service.
- Uses built-in device sounds, not custom music.
- Does not implement the fade-in, pitch change, sync, or interval features from the 40 problems.
- On Android 14+, the full-screen intent permission may need to be granted in Settings.
- Play Store approval depends on the app being an alarm or timer as its core function.

## CI (GitHub Actions)
- `.github/workflows/build.yml` builds a release APK and then runs the guards in `ci/guards/`.
- Signing secrets (optional; without them the APK is built unsigned): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Guards:
  - `network_guard.sh`: no INTERNET permission, no network APIs in source.
  - `permission_guard.sh`: merged manifest permissions must be listed in `ci/guards/allowed_permissions.txt`. Add a line there when you add a permission.
  - `telemetry_guard.sh`: no analytics or crash-reporting libraries in the release classpath.
  - `alignment_guard.sh`: native libraries must be 16 KB aligned. Passes if the APK has none; set `REQUIRE_NATIVE=1` to require them.
