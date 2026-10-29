# Futura Audio: STATUS

Current stage: **Stage 0, build pipeline and skeleton**
Blueprint: `docs/stage-0-build-pipeline.md`

## Verification state

| Check | State |
|---|---|
| Gradle / Android / NDK build (`./gradlew :app:assembleRelease`) | **Passes in CI** (run 2). Native code, `:engine` Kotlin and `:app` Kotlin all compile. Warnings only. |
| Guards in CI (network, permission, fallback, telemetry, audio thread, 16 KB alignment) | **Network, permission, fallback and telemetry pass** (run 2). Audio thread guard failed in run 2 on a script bug, now fixed and tested locally. Alignment not yet reached in CI. |
| Engine logic (C++ on host, Oboe stubbed) | **Passing.** 24 checks, run with `bash engine/host-tests/run.sh`: SPSC queue ordering and capacity, a 2M-item two-thread stress run, tone silence, fade-in, 440 Hz frequency, stop fade-out, gain change, and the alarm engine's queued volume path. Built with `-Wall -Wextra -Werror`. |
| Native C++ against real Oboe 1.8.0 headers | **Compiles in CI** (run 1). The `getXRunCount`, `getAudioApi` and `getDeviceId` calls are confirmed. Linking is not yet confirmed. |
| Kotlin `:engine` module | **Compiles in CI** (run 2). Run 1 failed on a missing `override`, now fixed. |
| Kotlin `:app` module and Compose screen | **Compiles in CI** (run 2). Only deprecation warnings from `AlarmReceiver.kt`, which is unchanged. Runtime behaviour not yet tested on a device. |
| On-device run (tone audible, status fields correct, xruns counted) | **Pending.** Needs a physical Android 14+ device. |
| Existing alarm features on device | **Pending.** Needs a device run before release. |

## Toolchain pins

| Item | Version | Where |
|---|---|---|
| JDK | 17 (Temurin, via `actions/setup-java@v5`) | `.github/workflows/build.yml`, `compileOptions` |
| Gradle wrapper | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.9.1 | `build.gradle.kts` |
| Kotlin (Android plugin) | 2.0.21 | `build.gradle.kts` |
| Kotlin Compose compiler plugin | 2.0.21 | `build.gradle.kts` |
| Compose BOM | 2024.12.01 | `app/build.gradle.kts` |
| `activity-compose` | 1.9.3 | `app/build.gradle.kts` |
| `core-ktx` / `appcompat` / `material` | 1.12.0 / 1.6.1 / 1.11.0 (unchanged) | `app/build.gradle.kts` |
| Android NDK | 28.0.13004108 (r28b) | `engine/build.gradle.kts`, CI |
| CMake | 3.22.1 | `engine/build.gradle.kts`, CI |
| Oboe | 1.8.0, built from source as a static library | `engine/src/main/cpp/CMakeLists.txt` |
| `compileSdk` / `targetSdk` | 36 / 36 (Android 16) | all modules, `app` |
| `minSdk` | 34 (Android 14) | all modules |
| Build tools | 36.0.0 | CI |
| ABI | arm64-v8a only | `abiFilters` in `engine` and `app` |
| Linker page size | `-Wl,-z,max-page-size=16384` | `engine/src/main/cpp/CMakeLists.txt` |
| GitHub Actions | `checkout@v4`, `setup-java@v5`, `setup-gradle@v4`, `wrapper-validation@v4`, `cache@v4`, `upload-artifact@v4` | CI |

## Module layout

- `app/`: Kotlin and Compose. Alarm logic, player UI, `SkeletonActivity` (Stage 0 screen). Depends on `:engine`.
- `engine/`: C++ with CMake (`futura_audio` shared library) and a small Kotlin API (`ToneEngine`). Owns every native library.
- `net/`: empty placeholder. The only module allowed to contain network code.
- `docs/`: blueprint and design notes.
- `ci/guards/`: merged-manifest and source checks, run in CI.
- `engine/host-tests/`: host-side C++ tests with Oboe, JNI and the decoder stubbed. Run with `bash engine/host-tests/run.sh`.

## Stage 0 acceptance checklist

Spec section 0.1, repo layout
- [x] `app/`, `engine/`, `net/`, `docs/`, `STATUS.md` and `.github/workflows/build.yml` exist.
- [ ] The GitHub repository is private. **Check in the GitHub settings. Nothing in the code can verify this.**

Spec section 0.2, toolchain pins
- [x] JDK 17, Gradle wrapper pinned (8.11.1), AGP and Kotlin pinned.
- [x] NDK r28 or newer (r28b).
- [x] `compileSdk` 36, `targetSdk` 36, `minSdk` 34, ABI `arm64-v8a` only.
- [x] Every exact version recorded in this file.
- [x] 16 KB page alignment: link flags set in `CMakeLists.txt`.
- [x] CI verifies alignment of every `.so` and fails if one is not aligned (`REQUIRE_NATIVE=1`).
- [ ] Confirm the CI alignment check passes on a real build (not yet reached in CI).

Spec section 0.5, skeleton app
- [x] One Compose screen with a **Play test tone** button. The button shows **Stop test tone** while the tone plays.
- [x] `:engine` opens an Oboe stream (float, low-latency, shared mode).
- [x] Plays a 440 Hz sine from the audio callback. Checked on host with stubs; a device check is still needed.
- [x] Screen shows: audio API, sample rate, frames per burst, output device name, xrun counter.
- [ ] Confirm the values on a device, and that the xrun counter moves under load.
- [x] Callback rules: no allocation, no locks, no logging, parameters through a lock-free queue. Enforced by `audio_thread_guard.sh` in CI.
- [x] Empty ordered `Effect` interface defined (`process(buffer, frames)`).

Stage-wide rules
- [x] No network code outside `:net`. `network_guard.sh` checks `:app` and `:engine`. `:net` has no code.
- [x] No allocation and no locks on the audio thread, including the alarm engine. Its old `try_lock` mailbox is replaced by the lock-free queue.
- [ ] Every existing feature still works on a device. **Pending device run.**

## Decisions made at Stage 0

- **Native engine moved** from `app/src/main/cpp` to `engine/src/main/cpp`. The library name `futura_audio` and the JNI names are unchanged, so `NativeBridge` in `:app` still works.
- **Alarm engine's parameter path** changed from a mutex-staged mailbox (read with `try_lock` on the audio thread) to the shared lock-free queue. Every change is applied in order, not only the latest. The final state is the same.
- **Skeleton screen** is reached from a button in `MainActivity`, not made the launcher, so the alarm app's entry point is unchanged.
- **Test tone** uses `Usage::Media` and shared mode. The alarm uses `Usage::Alarm`. The tone is a separate stream and does not touch the alarm engine.
- **Permission allowlist:** `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` were already declared in the app manifest before Stage 0 but were missing from `allowed_permissions.txt`. They are added now, and `permission_guard.sh` is wired into CI. This had not been run in CI before. **Review this allowlist change.**
- **Oboe is built as a static library**, so no separate `libOboe.so` is packaged. That keeps the alignment check to one library.
- **Spec numbering:** the blueprint skips sections 0.3 and 0.4. The README's "Stage 0.3" section describes the alarm storage work, which is unchanged.

## Known limits and risks

- The whole app builds in CI (run 2). Nothing has run on a device, and the alignment guard has not yet been reached.
- The Oboe calls compiled in CI run 1 against the real headers, so their signatures are confirmed. `engine/host-tests/oboe/Oboe.h` is a stand-in and can drift from the real API. Trust the CI build over it.
- CI run 1 emitted harmless warnings: unused parameters inside Oboe's own headers, and `Unable to strip` for `libandroidx.graphics.path.so` and the engine library. Neither fails the build.
- `permission_guard.sh` passed in CI run 2, so the allowlist change matches the merged manifest.
- Raising `minSdk` to 34 drops Android 10 to 13 devices, which the spec requires.
- Exact-alarm behaviour on Android 14 and later is unchanged from before Stage 0, and still needs a device check.

## Next stage

See the blueprint for Stage 1. Its text was not in the uploads, so it is not summarised here.
