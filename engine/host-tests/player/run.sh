#!/usr/bin/env bash
# Host tests for the Stage 1 playback engine: ring, resampler, gapless window, and end-to-end playback through
# the real decoders with a real-time stand-in for Oboe. Fixtures are made with ffmpeg at run time.
# Needs cmake, g++, ffmpeg and network access (the decoder dependencies are fetched by git at configure time).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$(mktemp -d)}
FX="$WORK/fx"; BUILD="$WORK/build"
mkdir -p "$FX"
command -v ffmpeg >/dev/null || { echo "ffmpeg is required"; exit 2; }

cmake -S "$HERE" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release >"$WORK/cmake.log" 2>&1 || { cat "$WORK/cmake.log"; exit 2; }
cmake --build "$BUILD" -j8 >"$WORK/build.log" 2>&1 || { cat "$WORK/build.log"; echo "BUILD FAILED"; exit 2; }

# Two 48 kHz tracks with different tones and lengths that are not frame multiples, one 44.1 kHz track.
ffmpeg -v error -y -f lavfi -i "aevalsrc=sin(2*PI*440*t)|0.4*sin(2*PI*330*t):s=48000:d=1.37" -c:a pcm_s16le "$FX/a48.wav"
ffmpeg -v error -y -f lavfi -i "aevalsrc=0.3*sin(2*PI*880*t)|0.3*sin(2*PI*1200*t):s=48000:d=0.91" -c:a pcm_s16le "$FX/b48.wav"
ffmpeg -v error -y -f lavfi -i "aevalsrc=sin(2*PI*500*t)|0.5*sin(2*PI*700*t):s=44100:d=1.37" -c:a pcm_s16le "$FX/c44.wav"
for n in a48 b48 c44; do
    ffmpeg -v error -y -i "$FX/$n.wav" -f f32le -acodec pcm_f32le -ac 2 "$FX/$n.f32" || { echo "reference failed"; exit 2; }
done

"$BUILD/player_test" "$FX/a48.wav" "$FX/a48.f32" "$FX/b48.wav" "$FX/b48.f32" "$FX/c44.wav" "$FX/c44.f32"
