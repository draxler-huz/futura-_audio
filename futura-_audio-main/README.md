# Wake-Up Music (prototype)

## Run it
1. Open the WakeUpMusic folder in Android Studio (Hedgehog or newer).
2. Let Gradle sync. Android Studio creates the Gradle wrapper automatically.
3. Run on a physical Android 14+ device (minimum is Android 14, target is Android 16). Alarms and full-screen intents do not behave fully on emulators.
4. Set the time, tap "Set alarm", and lock the phone. Test with the screen off.

## Stage 0: build pipeline and skeleton
- Modules: `:app` (Kotlin, Compose, alarm and player UI), `:engine` (C++ with CMake, Oboe, every native library), `:net` (empty placeholder, the only module allowed to hold network code). `docs/stage-0-build-pipeline.md` is the blueprint for this stage. `STATUS.md` records the exact toolchain versions and the acceptance checklist.
- Toolchain: compileSdk 36, targetSdk 36, minSdk 34, arm64-v8a only, NDK r28, 16 KB page alignment on every `.so`.
- Skeleton: from the launcher, tap **Audio engine test (Stage 0)**. The Compose screen plays a 440 Hz sine through an Oboe shared, low-latency float stream. It shows the audio API, sample rate, frames per burst, output device and the xrun counter.
- Real-time rules for the audio thread: no allocation, no locks, no logging. Parameters reach the audio thread through a lock-free SPSC queue (`spsc_queue.h`). `ci/guards/audio_thread_guard.sh` enforces this in CI. The ordered `Effect` interface (`effect.h`) is defined but empty.

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
- Stage 0 raised the minimum to Android 14. Devices on Android 10 to 13 can no longer install the app.
- Playback runs only while the alarm activity is alive. A production app needs a foreground service.
- Uses built-in device sounds, not custom music.
- Does not implement the fade-in, pitch change, sync, or interval features from the 40 problems.
- On Android 14+, the full-screen intent permission may need to be granted in Settings.
- Play Store approval depends on the app being an alarm or timer as its core function.

## CI (GitHub Actions)
- `.github/workflows/build.yml` builds a release APK and then runs the guards in `ci/guards/`. Native libraries are required (`REQUIRE_NATIVE=1`).
- Signing secrets (optional; without them the APK is built unsigned): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Guards:
  - `network_guard.sh`: no INTERNET permission, no network APIs in source.
  - `permission_guard.sh`: merged manifest permissions must be listed in `ci/guards/allowed_permissions.txt`. Add a line there when you add a permission. Wired into CI at Stage 0.
  - `audio_thread_guard.sh`: no allocation, locks or logging inside audio-thread functions in `engine/src/main/cpp`. Add new audio-thread function names to `RT_FUNCS`.
  - `telemetry_guard.sh`: no analytics or crash-reporting libraries in the release classpath.
  - `alignment_guard.sh`: native libraries must be 16 KB aligned. Passes if the APK has none; set `REQUIRE_NATIVE=1` to require them.
