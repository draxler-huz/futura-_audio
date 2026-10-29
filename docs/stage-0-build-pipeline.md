# STAGE 0: Build Pipeline and Skeleton

 Features: none yet. 

## 0.1 Repo layout

- `app/` (Kotlin + Compose), `engine/` (C++ with CMake), `net/` (empty placeholder, no code yet), `docs/` (the blueprint files), `STATUS.md`, `.github/workflows/build.yml`.
- Private GitHub repo. Never make it public.

## 0.2 Toolchain pins

- JDK 17, Gradle wrapper pinned, Android Gradle Plugin and Kotlin pinned, NDK r28 or newer, `compileSdk` 36, `targetSdk` 36, `minSdk` 34, ABI `arm64-v8a` only.
- Record every exact version in `STATUS.md`.
- **16 KB page alignment:** link flags `-Wl,-z,max-page-size=16384`. CI verifies alignment of every `.so` file and fails if one is not aligned.




## 0.5 Skeleton app

- One activity, a Compose screen with a **Play test tone** button.
- The `:engine` module opens an Oboe stream (float, low-latency, shared mode) and plays a 440 Hz sine from the audio callback.
- The screen shows: audio API in use, sample rate, frames per burst, output device name, and the **xrun counter**.
- The callback follows these rules from day one: no allocation, no locks, no logging; parameters arrive through a lock-free queue.
- An empty ordered `Effect` interface is defined now (`process(buffer, frames)`), so later stages only add effects.






