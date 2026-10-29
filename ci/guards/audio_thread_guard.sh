#!/usr/bin/env bash
# Fails if a function that runs on the audio thread allocates, takes a lock, or logs.
#
# How it works: one awk pass over engine/src/main/cpp/*.cpp finds definitions of known audio-thread functions
# (at column 0) and scans each body, from the definition line to the closing brace at column 0. Comments are
# removed before matching. awk reports violations on stdout and the function count on a final "CHECKED" line.
#
# Add a function name to RT_FUNCS when you add code that the audio callback calls.
set -euo pipefail
cd "$(dirname "$0")/../.."

RT_FUNCS='onAudioReady|applyParams|applyCommands|process|configure|read|next|setTarget|reset'
FORBIDDEN='(^|[^A-Za-z_])(new|malloc|calloc|realloc|make_shared|make_unique)[ (]|std::vector|push_back|emplace_back|std::string|std::function|std::mutex|std::shared_mutex|lock_guard|unique_lock|scoped_lock|std::lock|try_to_lock|pthread_mutex|[.]lock[(][)]|__android_log|ALOG|LOG[IWEDV][(]|printf|fprintf|puts[(]|std::cout|std::cerr'

DIR=engine/src/main/cpp
[[ -d "$DIR" ]] || { echo "::error::$DIR not found"; exit 1; }

mapfile -t FILES < <(find "$DIR" -name '*.cpp' | sort)
(( ${#FILES[@]} > 0 )) || { echo "::error::no .cpp files in $DIR"; exit 1; }

AWK_RC=0
OUT=$(awk -v rt="$RT_FUNCS" -v forbid="$FORBIDDEN" '
    BEGIN { bad = 0; checked = 0; inside = 0 }
    FNR == 1 { inside = 0 }
    {
        line = $0
        sub(/\/\/.*/, "", line)                  # ignore line comments
    }
    inside == 0 && $0 ~ ("^[^ \t#/}][^;]*::(" rt ")[(]") {
        inside = 1
        checked++
        next
    }
    inside == 1 {
        if ($0 ~ /^}/) { inside = 0; next }
        if (line ~ forbid) {
            printf "%s:%d: forbidden on the audio thread: %s\n", FILENAME, FNR, $0
            bad = 1
        }
    }
    END { printf "CHECKED %d %d\n", checked, bad }
' "${FILES[@]}" 2>&1) || AWK_RC=$?

if (( AWK_RC != 0 )); then
    echo "::error::awk failed while scanning audio-thread code:"
    echo "$OUT"
    exit 1
fi

SUMMARY=$(grep '^CHECKED ' <<< "$OUT" || true)
VIOLATIONS=$(grep -v '^CHECKED ' <<< "$OUT" || true)
[[ -n "$VIOLATIONS" ]] && echo "$VIOLATIONS"

CHECKED=$(awk '{print $2}' <<< "$SUMMARY")
BAD=$(awk '{print $3}' <<< "$SUMMARY")
if [[ -z "$CHECKED" ]] || (( CHECKED == 0 )); then
    echo "::error::no audio-thread functions found; check RT_FUNCS"
    exit 1
fi
if [[ "$BAD" != "0" ]]; then
    echo "::error::audio-thread rule broken (no allocation, locks or logging on the audio thread)"
    exit 1
fi
echo "audio_thread_guard: ok ($CHECKED audio-thread function bodies checked)"
