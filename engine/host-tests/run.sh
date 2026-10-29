#!/usr/bin/env bash
# Host-side tests for the native engine. Needs only g++ and a POSIX shell. Does NOT need the NDK or Oboe.
#
# Oboe, JNI and the audio decoder are replaced by stubs in this directory (oboe/Oboe.h, jni.h, stubs.cpp).
# These tests check engine logic: queue ordering, tone output and parameter handling. They do NOT check
# that the stubs match the real Oboe API, and they do not replace a device test.
set -euo pipefail
cd "$(dirname "$0")"
SRC=../src/main/cpp
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

g++ -std=c++17 -Wall -Wextra -Werror -g -pthread \
    -I. -I"$SRC" \
    test_main.cpp stubs.cpp \
    "$SRC/audio_engine.cpp" "$SRC/tone_engine.cpp" "$SRC/dsp.cpp" "$SRC/gapless_loop.cpp" \
    -o "$OUT/engine_test"
"$OUT/engine_test"
