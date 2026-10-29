# Engine host tests

Run with `bash engine/host-tests/run.sh`. It needs only `g++`. It does not need the Android SDK, the NDK, or Oboe.

What it covers:
- the lock-free SPSC queue: FIFO order, capacity, and a two-thread stress run of 2 million items
- the tone engine: silence until started, a fade-in with no click, 440 Hz frequency, fade-out, gain changes, and info cleared on close
- the alarm engine's queued parameter path: volume, compressor and EQ commands, and stop

What it does not cover:
- the real Oboe API. `oboe/Oboe.h` is a hand-written stand-in for the subset the engine uses. If Oboe's signatures change, check the real headers.
- JNI. `jni.h` is a stand-in. The JNI bridge is only compiled by the NDK build.
- the audio decoder. `stubs.cpp` provides a fake `decodeTrack`.
- real audio hardware. A device test is still required.

The `audio_thread_guard.sh` CI check is separate. It checks the audio-thread rules in the source and runs without a compiler.
