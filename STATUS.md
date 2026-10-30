# Futura Audio: STATUS

Current stage: **Stage 2, library** (features 2.1 to 2.5 are written; the alarm now rings in its own foreground service; 2.6 credits and 2.7 artwork are not built yet; the MP3/Opus seek index is deferred, see Stage 2 decisions). Stage 0 and Stage 1 device checks are still pending.
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
| Existing alarm features on device | **Pending.** Alarm code is untouched in Stages 1 and 2. |
| **Stage 2 SQL** (schema, queries, FTS5, smart playlists, folder and queue SQL) | **Passing: 22/22** on SQLite 3.45.1 with FTS5. The harness takes the real SQL strings from the Kotlin sources and runs them against sample data. Re-run it after any SQL change: `python3 tools/verify/sql_check.py` (source in this repo). |
| **Stage 2 JVM unit tests** (`ScanPolicyTest`, 9 cases; `SmartCompilerTest`, 6 cases) | **Written, not run.** No Gradle in the authoring environment. Run with `./gradlew :app:testDebugUnitTest`. |
| **Kotlin static checks** (bracket balance, imports for Compose, coroutine and library names) | **Passing** on all 24 changed or new Kotlin files. These are heuristic checks, not a compile. |
| **Kotlin compile** (`:app`, `:engine`) | **Not compiled for Stage 2.** No Kotlin compiler or Android SDK here. CI must confirm. Expect a few compile errors on the first run. |
| **Audio thread guard** (`ci/guards/audio_thread_guard.sh`) | **Passing locally**, 20 function bodies. Stage 2 adds no native code. |
| **Network guard** (grep over `app/src` and `engine/src`) | **Clean.** No network API outside `:net`. |
| **Permission allowlist** (declared permissions in the app manifest) | **All on the allowlist.** Stage 2 adds no permission. The merged-manifest check needs CI. |
| **Alarm foreground service** (`AlarmService`, `AlarmPlayback`, `AlarmActivity`, `AlarmReceiver`) | **Written, not compiled, not run on a device.** Static checks pass. The service uses only permissions already on the allowlist. |

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
| **AndroidX DocumentFile** (Stage 2, folder picker walk) | **1.0.1** (new). Not verified against Google Maven. CI decides. | `app/build.gradle.kts` |
| Android NDK / CMake / Oboe | r28b (28.0.13004108) / 3.22.1 / 1.8.0, unchanged | unchanged |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 36 / 34, unchanged | unchanged |

### Third-party native code (Stage 1), fetched at configure time

Pinned in `engine/src/main/cpp/player/decoders.cmake`. Each commit was resolved with `git ls-remote` on the day of authoring.

| Dependency | Pin | Used for | Licence (read from the repo's LICENSE) |
|---|---|---|---|
| minimp3 | `lieff/minimp3` @ `ea99364f61c14656440e8d77e9c233ccf3124633` | MP3 decoding | CC0-1.0 |
| dr_libs | `mackron/dr_libs` @ `e84e28a98d0503b762053415419919623e9d6c96` (corrected to the value in `decoders.cmake`, which the build uses; confirm with `git ls-remote`) | FLAC decoding (`dr_flac.h`) | Public domain or MIT-0, at our choice |
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

## Module layout (Stage 2 additions)

All Stage 2 Kotlin is in `app/src/main/java/com/example/wakeupmusic/library/`. There is no C++ change.

- `db/`: `LibraryDatabase` (library.db, WAL, schema v1), `LibraryRepository` (all SQL), `LibraryModels` (rows, filters and sort keys).
- `scan/`: `ScanPolicy` (folder rules, pure logic), `MediaScanner` (MediaStore source and SAF folder walk), `ScanCoordinator` (background runner, progress flow, notification), `FolderGrants` (picker folders and persistent grants).
- `smart/`: `SmartRules` (JSON model, compiler to SQL, parser), `SmartPlaylists` (six built-ins).
- `queue/`: `QueueStore` (named queues, active queue, ordered rewrites).
- `tags/`: `TagEditor` (batch apply, backup, undo), `TagWriter` (interface), `PendingTagWriter` (the stand-in until TagLib lands).
- `ui/`: `LibraryActivity` (route stack, permission gate, controller), `LibraryActions` (player and queue sync), `LibraryScreens`, `FolderScreen`, `PlaylistScreens`, `TagEditorScreen`.
- `library.db` is separate from `audio_library.db` (alarm cache) and `alarms.db`. A rescan cannot touch alarm data.
- Play counts: `PlaybackService` registers a `Player.Listener`. It writes play counts on a single background thread, best effort.
- Tests: `app/src/test/.../library/scan/ScanPolicyTest.kt`, `.../library/smart/SmartCompilerTest.kt`.
- Verification harness: `tools/verify/sql_check.py` (SQL), and the static checks were run from the authoring environment.

## Alarm foreground service (Stage 2)

The alarm's sound now belongs to `AlarmService`, a foreground service of type `mediaPlayback`, and not to `AlarmActivity`.

- `AlarmReceiver` starts the service. It no longer posts its own notification.
- `AlarmService` posts a high-priority, ongoing notification with a full-screen intent, a content intent and a Dismiss action. It owns the sound until the alarm is dismissed.
- `AlarmPlayback` holds the playback logic moved from the activity. The order is unchanged: native engine, device sound, embedded WAV, then the synthesised tone.
- `AlarmActivity` only shows the state and sends commands (toggle, skip, dismiss). Closing it or leaving the app does not stop the alarm.
- Dismiss, from the screen or the notification, is the only thing that ends the alarm. A command that reaches a service that is not ringing stops that service.
- The service is not sticky. A killed process does not restart a ringing alarm by itself.

## Stage 2 acceptance checklist

Spec 2.1, scanner (F06)
- [~] Reads the file list from MediaStore with only `READ_MEDIA_AUDIO`. Parsing runs on a background dispatcher. **Written.** Tags come from MediaStore columns, not TagLib (see deviations).
- [~] Whitelist, blacklist, minimum duration (30 s default) and ringtone, alarm and notification exclusion. **Written. `ScanPolicyTest` written, not run.** Changes apply after the next rescan.
- [~] Incremental rescan: unchanged files are marked seen, changed files are re-read, and removed files are deleted. Only after a walk that was not cancelled. **Written. The SQL is verified in the harness.**
- [~] Optional "add a folder" with the system picker and a persistent grant. **Written.** Folders on primary storage can also be used as whitelist or blacklist rules.
- [~] Progress in a notification and in the screen header, and the interface never blocks (Rule 7). **Written.**

Spec 2.2, index and search (F07)
- [x] SQLite tables as listed, plus `tag_backups` (extra, for undo). **The schema is verified: 33 statements run on SQLite 3.45.1.**
- [~] WAL mode, batched transactions of 300 rows. **Written.** Room is not used (deviation, see below).
- [x] Indexes on every column used for sorting and filtering. **Verified in the schema.**
- [x] FTS5 full-text search over title, artist, album, genre and folder, with a LIKE fallback when FTS5 is absent. **Query verified in the harness.**
- [~] Songs, Albums, Artists, Genres, Folders and Playlists screens, with fast scroll (letter rail) and sort. **Written.**

Spec 2.3, folder navigation (F08)
- [~] Folder tree built from each file's folder. Tapping a folder opens it, and "Play folder" plays its contents. **Written.**

Spec 2.4, smart playlists and multi-queue (F09)
- [x] Smart rules stored as JSON and compiled to SQL. Values are bound, and only whitelisted fields and operators reach the SQL. **Compiler verified by the unit test expectations and the harness.**
- [x] Built-in playlists: Recently Added, Most Played, Recently Played, Top Rated, Never Played, Favorites. **Queries verified in the harness.**
- [~] Several named queues, switch (play or make active), play next, add to end, swipe to remove, up and down to reorder, save as playlist. **Written.**
- [ ] **Drag to reorder is not built.** Up and down buttons are used instead. See deviations.
- [~] Star ratings and a favourite flag, stored locally. **Written.**

Spec 2.5, tag editor (F10)
- [ ] **Writing to files is not built.** The editor is disabled in this build and says so. It needs the TagLib build (see the Stage 2 known risks). Until then, the file-writing acceptance item cannot pass.
- [~] Batch edit (a blank field keeps the existing value), a backup of old values, and one-tap undo. **Written.** They cannot run until a writer exists.
- [ ] Cover art replacement and the comment and lyrics fields. **Not built.** They need TagLib.
- [ ] Android write-request flow (`MediaStore.createWriteRequest`, one prompt per batch). **Not built.** It is needed when the writer lands.

Stage 1 extensions (done first, as requested)
- [~] `READ_MEDIA_AUDIO` is requested when the library opens, after the explanation. Declining still lets the user add folders. **Written.** This closes the Stage 1 decision about when the permission is asked.
- [x] Picker filter changed from `audio/*` to `*/*` (`PICKER_MIME_TYPES`), so FLAC, Opus and M4A files can be picked. Taken from the nested copy of the project, which already had this fix.
- [ ] **MP3 and Opus seek index: not built.** See the Stage 2 decisions for the reasons.

Stage 2.6 and 2.7 (not in this build)
- [ ] 2.6 Studio credits (F11): `credits` table exists and is empty. Reading the credits and the credits card are not built.
- [ ] 2.7 Artwork and caching (F12): not built. Cover art and its three-level cache are not started.

Stage 2 acceptance (from the stage spec)
- [ ] A 10,000-track library scans in the background while playback and scrolling stay smooth. **Pending a device run with a large library.**
- [~] Search returns results as you type, with no visible delay. **Written.** Query is debounced 120 ms, FTS5 prefix search, limit 200. Timing pending a device run.
- [~] Whitelist and blacklist rules change what appears after a rescan. **Written. Rule logic unit-tested; device run pending.**
- [~] A smart playlist "added in the last 30 days and rated 4 or more" is correct. **Compiled SQL checked against the expected text and against SQLite.** Device run pending.
- [ ] Editing a title and a cover on a FLAC and an MP3 persists after a rescan. **Not met.** Needs the TagLib writer (and cover art, stage 2.7).
- [ ] Memory stays flat while scrolling thousands of album covers. **Not applicable yet.** Covers are stage 2.7.
- [~] Airplane mode makes no difference. **By design:** no network code, no INTERNET permission, and the local database is used throughout. Device run pending.

## Stage 2 decisions

- **Separate `library.db`.** The existing `audio_library.db` is the alarm track cache. Its `tracks` table has alarm fields, so reusing it would have mixed the two. A library rescan cannot touch alarm data.
- **SQLiteOpenHelper, not Room.** The codebase already uses SQLiteOpenHelper for both alarm databases. Adding Room would mean a new annotation processor (KSP) version pin that I could not verify offline. The schema and SQL are the same either way, so this can move to Room later with no change of behaviour.
- **FTS5 with a LIKE fallback.** FTS5 is not guaranteed on every Android SQLite build. The index is created when possible, and search checks whether it exists.
- **Tags come from MediaStore for now.** MediaStore already indexes the tags for every standard format, so the scanner does not need TagLib to read. TagLib is needed for writing, which is a separate pin.
- **Play counts come from the playback service.** Counting in the UI would miss plays while the library is closed.
- **Drag reorder replaced by buttons.** Up and down buttons cover the same operation with much less gesture code that I could not test. Drag can be added on top later without a data change.
- **Deferred: MP3 and Opus seek index (Stage 1 extension).** The Stage 1 status says a seek index is a Stage 2 item. I did not build it in this pass, for two reasons. It changes the MP3 and Opus decoders, which are host-verified against ffmpeg, and I cannot run those host tests here (their pinned sources need network access). It needs its own pass with the host tests running. The plan: a sparse index recorded during decode for MP3 (frame byte offsets, with a two-frame pre-roll for the bit reservoir), and for Opus the Ogg page table, with an 80 ms pre-roll.
- **Playback queue and stored queues stay in step.** When a stored queue is loaded, `LibraryActions.loadedQueueId` is set. Play next, add, remove and move then change the stored queue and the player, so playback is not interrupted. The sync is by index, so if the player is changed by another screen while a queue is loaded, the sync can drift. Clearing the loaded queue on player changes is a follow-up.
- **Tag writes are disabled rather than faked.** A failed write never changes the index. An edit that looked saved but was not written would be worse than a disabled button.

## Stage 2 known risks

- **Alarm start from the background.** `AlarmReceiver` starts a foreground service from a broadcast. Android allows this for exact alarms, and the app holds `USE_EXACT_ALARM`. Check this on Android 14 and 16 with the alarm set while the app is closed and the phone is locked. If the start is refused, the alarm will not ring, and that must be fixed before anything else is merged.
- **A killed process does not restart the alarm.** The service is not sticky, so if the system kills the process during a ringing alarm, the sound stops. This is deliberate: a sticky service would restart the alarm with no intent.
- **The alarm picker now offers every file.** `MainActivity` uses `*/*` instead of `audio/*`, so FLAC, Opus and M4A files can be chosen.

- **Nothing in Stage 2 is compiled.** Kotlin, Compose and Media3 APIs were written from memory, and the static checks only catch structural mistakes. The likeliest first-build errors are Compose signatures (`SwipeToDismissBox`, `rememberSwipeToDismissBoxState`, `ScrollableTabRow`) and Media3 controller methods (`moveMediaItem`, `removeMediaItem`). Check these first.
- **TagLib is not pinned.** The pin must be resolved with `git ls-remote` on the day, as the Stage 1 pins were, and its licence (LGPL-2.1 or MPL-2.0) recorded here. I did not invent a commit hash. Until TagLib lands, `PendingTagWriter` keeps the editor disabled.
- **`MediaStore.Audio.Media.DATA` is deprecated.** It still returns paths on Android 14 and 16 for files the app can read, and it is used here for the scan. If it stops returning paths for some providers, switch to `RELATIVE_PATH` plus `DISPLAY_NAME`.
- **Album identity is artist-based.** The album key is album name plus tag artist, so a compilation with many artists shows as several albums. TagLib's album-artist field would fix this.
- **SAF folders get less metadata.** Files added through the folder picker are read with MediaMetadataRetriever. Unknown fields stay empty, and DSD files that the retriever cannot open are indexed by file name. DSD playback is still Stage 3.
- **Scans are not resumed after the process dies.** The next scan is incremental, so it picks up where this one stopped.
- **Album and artist lists load everything.** There is no paging yet, so a very large library may be slow to open those tabs. Songs, search and folders are limited (20,000 and 200 rows).
- **Stage 2 is scoped to the library.** The alarm, the player and the Stage 1 playback path are not changed in behaviour. The one behavioural change is the picker filter, which now offers all files.

## Resolved in Stage 2: alarm foreground service

The Stage 1 note asked for an independent foreground service so alarm playback survives leaving the alarm screen. It was confirmed as Stage 2 work and is now built (see "Alarm foreground service" above). It is written, not yet run on a device.

## Repository hygiene (Stage 2 cleanup)

- The nested second copy of the whole project (`futura-_audio-main/futura-_audio-main/`) is removed. Its only differences from the root were the picker fix (now applied in the root) and an older STATUS.md and MainActivity.kt.
- The stale `app/src/main/cpp/` copy is removed. No build file referenced it.
- The `dr_libs` pin in the STATUS table now matches `decoders.cmake`. Confirm it with `git ls-remote` before the first CI build.

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

- **DSD moved to Stage 3.** Allowed by the spec, because Stage 1 was too large. `decoder_factory.cpp` returns kUnsupportedFormat for DSF and DFF, and the format self-test reports it
- Stage 2 must  introduces an independent foreground service architecture that decouples alarm playback from the UI activity, ensuring audio and system-level alarm status survive when users navigate away from the ringing screen. By integrating a persistent high-priority notification and media-backed playback controls, the feature allows users to seamlessly exit the alarm interface and utilize the rest of the application normally, while the operating system and device state continue to treat the active alarm as fully engaged until explicitly dismissed.
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
- **The stale `app/src/main/cpp/` copy was removed in Stage 2.** Nothing built it, and no build file referenced it.
- **The Stage 1 blueprint text was not in the repo.** This checklist follows the spec given with the task.

## Next stage

Finish Stage 2 first, then move on:
1. Stage 2.6 (credits, read from ID3 involved people, Vorbis comments and MP4 atoms) and 2.7 (artwork and the three-level cache). Both need TagLib for the full fields, so the TagLib pin comes first.
2. Resolve the TagLib pin with `git ls-remote`, record its licence, add its JNI writer, and replace `PendingTagWriter`. Add the write-request flow in the tag editor.
3. The MP3 and Opus seek index (Stage 1 extension). Run the host tests with network access before merging.
4. Drag to reorder in the queue screen.
5. Confirm the dr_libs pin with `git ls-remote`.
6. CI build and device run for Stages 0, 1 and 2 together, including the 10,000-track scan, the airplane-mode check, and an alarm that rings with the app closed and the screen locked.

N/B everything in stage 1 tests runs weel
