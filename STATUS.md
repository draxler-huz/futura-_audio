# Futura Audio: STATUS

Current stage: **Stage 1, playback core** (Stage 0 build pipeline and skeleton is complete in code; its device checks are still pending)
Blueprint: `docs/stage-0-build-pipeline.md` (Stage 0). The Stage 1 blueprint is the spec given with this task. It is not in the repo, so it is summarised below.

## Verification state

| Check | State |
|---|---|
| Gradle / Android / NDK build (`./gradlew :app:assembleRelease`) | **Not run for Stage 1.** No Android SDK and no Google Maven access in the authoring environment. CI must confirm it. |
| CI guards | **Audio thread guard passes locally** (20 function bodies checked, now including the Stage 1 player). Network grep is clean. Permission, telemetry and alignment guards need a CI build. |
| Player engine, host tests (C++, real decoders, real-time Oboe stand-in) | **Passing: 44/44.** `bash engine/host-tests/player/run.sh`. Covers the ring, the resampler, the gapless window, an A-to-B gapless switch that matches the reference **sample for sample (max difference 0)**, seeking that matches the reference exactly, 44.1 kHz to 48 kHz playback (worst error 2.2e-4 against the analytic signal), and the probe. |
| Decoder host tests | **Passing: 25/25.** `bash engine/host-tests/decoder/run.sh`. Every decode is checked against ffmpeg's own decode. The MP3 LAME gapless values match `ffprobe` skip_samples and discard_padding. |
| Stage 0 host tests | **Passing** (unchanged code). `bash engine/host-tests/run.sh` |
| Native code against the real Oboe 1.8.0 headers | **Not compiled for Stage 1.** The Oboe calls are the same ones Stage 0 used, plus `getBufferSizeInFrames`, `getXRunCount` and `getAudioApi`. CI must confirm them. |
| JNI bridge | **Syntax-checked** against a scratch jni stub. Not linked. |
| Kotlin `:engine` (`PlayerEngine.kt`) and `:app` player code | **Not compiled.** Reviewed statically (see Known risks). CI must confirm. |
| On-device run (audible, gapless by ear, lock screen, calls, unplug, crash restart) | **Pending.** Needs a physical Android 14+ device. |
| Existing alarm features on device | **Pending.** Alarm code is untouched in Stage 1. |

## Toolchain pins

Stage 0 pins are unchanged, except where noted.

| Item | Version | Where |
|---|---|---|
| JDK | 17 | unchanged |
| Gradle wrapper | 8.11.1 | unchanged |
| Android Gradle Plugin / Kotlin | 8.9.1 / 2.0.21 | unchanged |
| Compose BOM | 2024.12.01 | unchanged |
| **Media3 session** (`androidx.media3:media3-session`) | **1.6.1** (new, Stage 1). Not verified against Google Maven. CI decides. | `app/build.gradle.kts` |
| **JUnit** (test only) | 4.13.2 (new) | `app/build.gradle.kts` |
| Android NDK / CMake / Oboe | r28b (28.0.13004108) / 3.22.1 / 1.8.0, unchanged | unchanged |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 36 / 34, unchanged | unchanged |

### Third-party native code (Stage 1), fetched at configure time

Pinned in `engine/src/main/cpp/player/decoders.cmake`. Each commit was resolved with `git ls-remote` on the day of authoring.

| Dependency | Pin | Used for | Licence (read from the repo's LICENSE) |
|---|---|---|---|
| minimp3 | `lieff/minimp3` @ `ea99364f61c14656440e8d77e9c233ccf3124633` | MP3 decoding | CC0-1.0 |
| dr_libs | `mackron/dr_libs` @ `baa9558d1ecbe01b25f90c675996c4530b0fb75f` | FLAC decoding (`dr_flac.h`) | Public domain or MIT-0, at our choice |
| stb | `nothings/stb` @ `003501f6d7a8668ce8b6d875e34dd97ad0d5d5b5` | Ogg Vorbis (`stb_vorbis.c`) | Public domain or MIT, at our choice |
| libopus | `xiph/opus` tag `v1.5.2` (commit `ddbe48383984d56acd9e1ab6a090c54ca6b735a6`) | Opus decoding | BSD-3-Clause. **Check the full LICENSE file before release;** only the copyright header was read. |
| Apple ALAC | `macosforge/alac` @ `c38887c5c5e64a4b31108733bd79ca9b2496d987` | ALAC decoding (Android only) | Apache-2.0 |
| Google Media3 | `androidx.media3:media3-session:1.6.1` | MediaSessionService and media session | Apache-2.0 |

Own code, no dependency: the WAV parser, the Ogg demuxer, the LAME/Xing header parser, the resampler, the gapless trimmer and the SPSC ring.

## Module layout (Stage 1 additions)

- `engine/src/main/cpp/player/`: the player.
  - `decoder.h`: the `Decoder` interface and the `TrackInfo` contract.
  - `decoder_factory.cpp`: `createDecoder()` sniffs the magic bytes. Also `codecName()`.
  - `decoder_wav.cpp`, `decoder_flac.cpp`, `decoder_mp3.cpp`, `decoder_vorbis.cpp`, `decoder_opus.cpp` with `ogg_demux.*`.
  - `decoder_mediacodec.cpp` (AAC/M4A) and `decoder_alac.cpp` (ALAC): Android only, compiled behind `#if defined(__ANDROID__)`.
  - `pcm_ring.h`: lock-free SPSC ring of stereo float. Used for the decoded audio and the audio tap.
  - `gapless.h`: the trim window, from skip and padding.
  - `resampler.*`: cubic Hermite conversion to the output rate, in the decode thread.
  - `player_engine.*`: the three-thread engine. Decode thread, audio callback, and controller.
  - `player_jni.cpp`: JNI for `PlayerBridge`. The Kotlin side is `engine/.../engine/PlayerEngine.kt`.
- `app/.../player/`: the service (`PlaybackService`, `FuturaPlayer`, `AudioFocusController`, `MediaButtonMapper`), the data layer (`PlayerStateStore`, `TrackMetadataReader`, `SourceOpener`), and the UI (`PlayerActivity`, `PlayerScreen`, `SettingsScreen`, `FormatTestScreen`, `PermissionExplanations`).
- `engine/host-tests/player/` and `engine/host-tests/decoder/`: host tests. Fixtures are generated with ffmpeg at run time. No binary fixtures are committed.

Stage 0's alarm engine, tone engine, alarm activities and receivers are unchanged. The player has its own Oboe stream, with usage Media and content type Music.

## Stage 1 acceptance checklist

Spec 1.1, engine architecture
- [x] Three threads: decode thread, audio callback, and controller. Commands use a lock-free queue (`SpscQueue` from Stage 0, for volume).
- [x] All buffers are allocated on load (`attachLocked`). Nothing is allocated in the callback. The resampler's scratch is allocated on configure.
- [x] Audio tap: a second ring the callback writes into, non-blocking (`tap_` in `PlayerEngine`). It is created now and read from Stage 4.
- [x] Engine stats exposed to the UI: xruns, callback time (last, average, max), buffer size, output sample rate, underrun frames, decode errors. (`EngineStats` in `PlayerEngine.kt`.)
- [x] Audio-thread guard passes locally, with the player's functions included.

Spec 1.2, decoders (F01)
- [x] Behind a `Decoder` interface. Versions are pinned, and licences are recorded above.
- [x] MP3, FLAC, WAV, Ogg Vorbis and Opus through open-source decoders. **Verified on the host** against ffmpeg's decode.
- [~] AAC/M4A through `MediaCodec`. **Written, not verified.** It needs the NDK, and the host cannot run it.
- [~] ALAC through an open ALAC decoder (Apple's). **Written, not verified.** The round trip is verified for 16-bit only, and the MP4 demux is on-device only.
- [x] **DSD deferred to Stage 3.** The spec allows this when Stage 1 is too large. `createDecoder` returns `kUnsupportedFormat` for DSF and DFF.
- [x] Files are opened from file descriptors (`SourceOpener` uses `detachFd()`, and the native side owns and closes the fd).
- [x] Format self-test screen: pick a file, see the codec, bit depth, sample rate, channels, duration, decoded frames and decode errors. (`FormatTestScreen` calls `PlayerEngine.probe`.)

Spec 1.3, gapless (F02)
- [x] The next track is decoded ahead into a second ring. The switch happens on an exact sample, inside the same callback buffer. **Verified: the A-to-B output matches the reference with max difference 0.**
- [x] Encoder delay and end padding are read and trimmed (LAME/Xing/Lavc for MP3, pre-skip for Opus, `encoder-delay` and `encoder-padding` for AAC). **MP3 values verified against ffprobe.** AAC is unverified on device.
- [x] FLAC and WAV need no trim, and none is applied.

Spec 1.4, service and session (F04)
- [~] A foreground service of type `mediaPlayback`, built on `MediaSessionService`, with a custom `Player` that forwards to the native engine. **Written.** Media3 signatures are unverified (see Known risks).
- [x] Permissions: `READ_MEDIA_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. Each is explained on screen (`PermissionExplanations`). All four are on `allowed_permissions.txt`. No other permission was added.
- [~] Publish title, artist, album, artwork and duration to the media session. **Written, not verified on a device.** Seek from the lock screen is written, not verified.

Spec 1.5, audio focus (F03)
- [~] Request focus as music. Pause on a call, duck on transient navigation, resume on regain, stop on permanent loss. **Written, not verified on a device.**
- [~] Pause when headphones are unplugged or Bluetooth disconnects (`ACTION_AUDIO_BECOMING_NOISY`). **Written, not verified on a device.**

Spec 1.6, headset and Bluetooth buttons (F05)
- [~] Media button events handled by the session. Written in `MediaButtonMapper`, with the click-counting logic unit tested (not run).
- [~] Settings screen maps single, double and triple click to play/pause, next, previous, shuffle or nothing. **Written, not verified on a device.**

Spec 1.7, minimal interface
- [~] File picker for one or several tracks (the Storage Access Framework, so no storage permission is needed), a now-playing screen (seek bar, play/pause, next, previous, volume), and a small settings screen. **Written, not verified on a device.**
- [~] Playback state saved on pause: queue, position and volume restore after a restart (`PlayerStateStore`). **Written.** Restore on a clean restart is unverified.
- [~] If the native engine crashes, the service restarts and resumes at the saved position. **Partly met.** See Known risks: a native crash kills the process, so this depends on the system restarting the service.

Stage-wide rules
- [x] No network code outside `:net`. The network grep is clean for `app/src` and `engine/src`. `:net` has no code.
- [x] No allocation and no locks in the audio callback. Enforced by `audio_thread_guard.sh`, which now covers the player's functions.
- [x] Every existing alarm feature is untouched in code. Its device check is still pending.
- [ ] Every existing feature works on a device. **Pending device run.**

## Decisions made at Stage 1

- **DSD moved to Stage 3.** Allowed by the spec, because Stage 1 was too large. `decoder_factory.cpp` returns kUnsupportedFormat for DSF and DFF, and the format self-test reports it.
- alarm option pushed to stage 2,a button on the alarm screen that opens the app, with the alarm still ringing. A fork has to confirm the alarm survives leaving its activity, because the README says playback runs only while that activity is alive.stage 2 must build this
- **Opus uses libopus.** Using a libopus decoder is a deliberate choice, since "a small decoder" for Opus would be a poorer one. The Ogg demuxer and the OpusHead parser are our own. Opus mapping family 1 is accepted for one or two channels only. Multistream is rejected.
- **MP3 gapless trim is padding minus 529, as ffmpeg does.** The skip is the LAME encoder delay plus 529. Both match ffprobe on the test fixtures.
- **Resampler is cubic Hermite, not windowed-sinc.** It is good for music in the tests (worst error 2.2e-4), but it is not a bit-exact or audiophile-grade converter. Upgrading it is a later task.
- **Seek stops and restarts the output stream** for a few tens of milliseconds, so the rings can be repositioned safely. Seeking inside an MP3 or Opus file decodes and discards from the start, so it is slow on long files. Stage 2 adds a seek index.
- **A gapless switch works on exact frames**, by a compare-exchange on the next slot's state. The callback never waits on the controller or the decode thread.
- **`READ_MEDIA_AUDIO` is declared and explained, but not requested in Stage 1.** The picker grants access without it. Stage 2 requests it for the library. **Confirm this reading of the spec.** The alternative is to request it now.
- **Playback runs in the same process as the UI and the alarm.** The native engine is in `futura_audio`, next to the alarm engine, which keeps the Stage 0 structure.

## Known risks and limits (read these before testing)

- **Nothing in Kotlin has been compiled.** The `:app` player code and `PlayerEngine.kt` were written and reviewed by reading only. Expect a few compile errors on the first CI run. The Media3 1.6.1 signatures that were checked from memory are listed in the review notes: `SimpleBasePlayer.State.Builder` (`setPlaylist`, `setCurrentMediaItemIndex`, `setContentPositionMs`, `setPlayerError`, `setShuffleModeEnabled`), the `handle*` methods and their `ListenableFuture` return types, `MediaSession.Callback.onMediaButtonEvent`, `MediaSessionService.onGetSession`, and the `Futures` class from transitive Guava.
- **The Media3 version is unverified.** 1.6.1 is the intended version. If CI cannot resolve it, change it in `app/build.gradle.kts` and nothing else.
- **The permission guard may fail.** `media3-session` could merge a permission not on the allowlist, such as `WAKE_LOCK`. Check the merged manifest on the first CI build. Add a line to `ci/guards/allowed_permissions.txt` only after review.
- **The Android-only decoders are unverified.** AAC via `MediaCodec` and ALAC via the NDK extractor were compiled against stubs and never run against the NDK. The Android CMake build (libopus and ALAC cross-compiled for arm64) has not run in CI.
- **Crash recovery is partial.** A native crash kills the whole process, and an in-process handler cannot save it. The service restarts only if the system restarts it. The complete fix is to run the engine in an isolated process, behind AIDL. That is deferred. A crash-restart is verified only at the state-storage level.
- **The existing `app/src/main/cpp/` is a stale copy** from before Stage 0. Nothing builds it, and the audio guard does not scan it. It can be deleted in a cleanup commit.
- **The Stage 1 blueprint text was not in the repo.** This checklist follows the spec given with the task.

## Next stage

Stage 2: the library. It needs the `READ_MEDIA_AUDIO` runtime request, a seek index for MP3 and Opus, and the library database (uses the Stage 0 storage pattern).
