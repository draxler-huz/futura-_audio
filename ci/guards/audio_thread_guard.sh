#!/usr/bin/env bash
# Fails if a function that runs on the audio thread allocates, takes a lock, or logs.
#
# How it works: it finds definitions of known audio-thread functions in engine/src/main/cpp and scans each
# body (from the definition line to the closing brace in column 0). Comments are removed before matching.
# Add a function name to RT_FUNCS when you add code that the audio callback calls.
set -euo pipefail
cd "$(dirname "$0")/../.."

RT_FUNCS='onAudioReady|applyParams|applyCommands|process|configure|read|next|setTarget|reset'
FORBIDDEN='(^|[^A-Za-z_])(new|malloc|calloc|realloc|make_shared|make_unique)[ (]|std::vector|push_back|emplace_back|std::string|std::function|std::mutex|std::shared_mutex|lock_guard|unique_lock|scoped_lock|std::lock|try_to_lock|pthread_mutex|\.lock\(\)|__android_log|ALOG|LOG[IWEDV]\(|printf|fprintf|puts\(|std::cout|std::cerr'

DIR=engine/src/main/cpp
[[ -d "$DIR" ]] || { echo "::error::$DIR not found"; exit 1; }

fail=0
checked=0
while IFS= read -r file; do
    result=$(awk -v rt="$RT_FUNCS" -v forbid="$FORBIDDEN" -v file="$file" '
        {
            line = $0
            sub(/\/\/.*/, "", line)              # drop line comments before matching
        }
        inside == 0 && $0 ~ ("^[^ \t#/}][^;]*::(" rt ")\\(") {
            inside = 1
            checked_fn++
            next
        }
        inside == 1 {
            if ($0 ~ /^}/) { inside = 0; next }
            if (line ~ forbid) {
                printf "%s:%d: forbidden on the audio thread: %s\n", file, FNR, $0
                bad = 1
            }
        }
        END {
            printf "%d\n", checked_fn > "/dev/stderr"
            exit bad
        }
    ' "$file" 2>/tmp/rt_count) || fail=1
    [[ -n "$result" ]] && echo "$result"
    checked=$((checked + $(cat /tmp/rt_count)))
done < <(find "$DIR" -name '*.cpp' | sort)

if (( checked == 0 )); then
    echo "::error::no audio-thread functions found; check RT_FUNCS"
    exit 1
fi
if (( fail )); then
    echo "::error::audio-thread rule broken (no allocation, locks or logging on the audio thread)"
    exit 1
fi
echo "audio_thread_guard: ok ($checked audio-thread function bodies checked)"
