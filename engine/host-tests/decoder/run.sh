#!/usr/bin/env bash
# Host tests for the Stage 1 decoder family.
#
# Fixtures are generated with ffmpeg at run time. No binary fixtures are committed. The reference for every
# audio fixture is ffmpeg's own decode (f32le, stereo), which applies gapless trimming itself. For MP3 the
# skip and trim values are also checked against ffprobe's skip_samples and discard_padding side data.
#
# Usage: bash engine/host-tests/decoder/run.sh          (WORK=/some/dir to keep the fixtures)
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$(mktemp -d)}
BUILD="$WORK/build"
FX="$WORK/fx"
mkdir -p "$FX"

fail=0
pass=0
note() { printf '%s\n' "$*"; }

command -v ffmpeg >/dev/null || { echo "ffmpeg is required"; exit 2; }
command -v ffprobe >/dev/null || { echo "ffprobe is required"; exit 2; }

cmake -S "$HERE" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release >"$WORK/cmake.log" 2>&1 || { cat "$WORK/cmake.log"; exit 2; }
cmake --build "$BUILD" -j8 >"$WORK/build.log" 2>&1 || { cat "$WORK/build.log"; echo "BUILD FAILED"; exit 2; }
TEST="$BUILD/decoder_test"

# Makes a fixture: $1 name, $2 sample rate, then ffmpeg output args. The source is a 1.37 s stereo tone pair,
# so the length is not a multiple of any codec frame size.
make_fixture() {
    local name=$1 rate=$2; shift 2
    local src="aevalsrc=sin(2*PI*440*t)|0.5*sin(2*PI*660*t):s=${rate}:d=1.37"
    ffmpeg -v error -y -f lavfi -i "$src" "$@" "$FX/$name" || { echo "ffmpeg failed for $name"; return 1; }
    # Reference is stereo. Mono sources are copied to both channels, as the decoder contract says. ffmpeg's
    # default mono upmix is -3 dB, so it is not used for mono fixtures.
    local af=()
    [[ "$name" == *mono* ]] && af=(-af "pan=stereo|c0=c0|c1=c0")
    ffmpeg -v error -y -i "$FX/$name" -f f32le -acodec pcm_f32le -ac 2 "${af[@]}" "$FX/$name.ref" || { echo "ref failed for $name"; return 1; }
}

check() {  # decode check with tolerance
    local name=$1 tol=$2
    if out=$("$TEST" decode "$FX/$name" "$FX/$name.ref" "$tol" 2>&1); then
        pass=$((pass + 1)); note "[ok]   $name"
    else
        fail=$((fail + 1)); note "[FAIL] $name"
    fi
    printf '%s\n' "$out" | sed 's/^/        /'
    LAST_OUT=$out
}

# ---- Audio fixtures, decoded and compared sample for sample with ffmpeg ----
make_fixture wav16.wav 44100 -c:a pcm_s16le
make_fixture wav24.wav 44100 -c:a pcm_s24le
make_fixture wav32.wav 44100 -c:a pcm_s32le
make_fixture wavf32.wav 44100 -c:a pcm_f32le
make_fixture wavu8.wav 44100 -c:a pcm_u8
make_fixture wavmono.wav 44100 -c:a pcm_s16le -ac 1
make_fixture flac.flac 44100 -c:a flac
make_fixture flacmono.flac 44100 -c:a flac -ac 1
make_fixture mp3lame.mp3 44100 -c:a libmp3lame -b:a 192k
make_fixture mp3nolame.mp3 44100 -c:a libmp3lame -b:a 192k -write_xing 0
make_fixture mp3id3.mp3 44100 -c:a libmp3lame -b:a 128k -id3v2_version 3 -metadata title=Fixture
make_fixture vorbis.ogg 44100 -c:a libvorbis -q:a 4
make_fixture vorbismono.ogg 44100 -c:a libvorbis -q:a 4 -ac 1
make_fixture opus.opus 48000 -c:a libopus -b:a 128k
make_fixture opusmono.opus 48000 -c:a libopus -b:a 64k -ac 1

# Tolerances, from measured maxima: PCM and FLAC are exact. MP3 (minimp3 float), Vorbis and Opus differ from
# ffmpeg only by rounding (about 2e-6 for MP3 and Vorbis, 2e-4 for Opus).
for n in wav16.wav wav24.wav wav32.wav wavf32.wav wavu8.wav wavmono.wav flac.flac flacmono.flac; do check "$n" 1e-6; done
check mp3lame.mp3 1e-4
check mp3nolame.mp3 1e-4
check mp3id3.mp3 1e-4
check vorbis.ogg 1e-4
check vorbismono.ogg 1e-4
check opus.opus 1e-3
check opusmono.opus 1e-3

# ---- MP3 gapless values against ffprobe ----
ffprobe_skip=$(ffprobe -v error -select_streams a:0 -show_packets -of default=nw=1 "$FX/mp3lame.mp3" \
    | awk -F= '$1=="skip_samples"{print $2; exit}')
ffprobe_pad=$(ffprobe -v error -select_streams a:0 -show_packets -of default=nw=1 "$FX/mp3lame.mp3" \
    | awk -F= '$1=="discard_padding" && $2!=0 {v=$2} END{print v+0}')
ours=$("$TEST" info "$FX/mp3lame.mp3")
our_skip=$(sed -n 's/.* skip=\([0-9-]*\).*/\1/p' <<< "$ours")
our_trim=$(sed -n 's/.* trim=\([0-9-]*\).*/\1/p' <<< "$ours")
note "ffprobe skip_samples=$ffprobe_skip discard_padding=$ffprobe_pad  ours skip=$our_skip trim=$our_trim"
if [[ "$ffprobe_skip" == "$our_skip" && "$ffprobe_pad" == "$our_trim" ]]; then
    pass=$((pass + 1)); note "[ok]   mp3 gapless values match ffprobe"
else
    fail=$((fail + 1)); note "[FAIL] mp3 gapless values differ from ffprobe"
fi

# ---- ALAC: lossless round trip through Apple's encoder and our wrapper ----
if out=$("$BUILD/alac_roundtrip" 2>&1); then
    pass=$((pass + 1)); note "[ok]   alac round trip (exact)"
else
    fail=$((fail + 1)); note "[FAIL] alac round trip"
fi
printf '%s\n' "$out" | sed 's/^/        /'

# ---- Errors: must be rejected, never decoded ----
head -c 8192 /dev/urandom > "$FX/garbage.bin"
{ printf 'DSD '; head -c 4096 /dev/urandom; } > "$FX/fake.dsf"
ffmpeg -v error -y -f lavfi -i "sine=frequency=300:sample_rate=44100:duration=0.2" -ac 6 -c:a pcm_s16le "$FX/wav6ch.wav"
for n in garbage.bin fake.dsf wav6ch.wav; do
    if out=$("$TEST" error "$FX/$n" 2>&1); then
        pass=$((pass + 1)); note "[ok]   rejects $n ($out)"
    else
        fail=$((fail + 1)); note "[FAIL] accepted $n ($out)"
    fi
done

# ---- Truncated windows: must not crash or hang ----
for n in mp3lame.mp3 flac.flac vorbis.ogg opus.opus wav16.wav; do
    size=$(stat -c %s "$FX/$n")
    if out=$("$TEST" window "$FX/$n" $((size * 4 / 10)) 2>&1); then
        pass=$((pass + 1)); note "[ok]   truncated $n: $out"
    else
        fail=$((fail + 1)); note "[FAIL] truncated $n: $out"
    fi
done

note ""
note "decoder host tests: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
