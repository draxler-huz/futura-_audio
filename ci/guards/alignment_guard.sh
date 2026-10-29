#!/usr/bin/env bash
# Fails if any native library in the APK is not 16 KB page aligned.
# If the APK has no native libraries, passes with a notice.
# Set REQUIRE_NATIVE=1 to make "no native libraries" a failure instead.
set -euo pipefail

APK="${1:?usage: alignment_guard.sh <apk>}"
REQUIRE_NATIVE="${REQUIRE_NATIVE:-0}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if ! unzip -q "$APK" 'lib/*' -d "$WORK" 2>/dev/null; then
    if [[ "$REQUIRE_NATIVE" == "1" ]]; then
        echo "::error::no native libraries in APK"
        exit 1
    fi
    echo "alignment_guard: no native libraries in APK, nothing to check"
    exit 0
fi

fail=0
count=0
while IFS= read -r so; do
    count=$((count + 1))
    while read -r align; do
        if (( align < 16384 )); then
            echo "::error::$so is not 16 KB aligned (LOAD segment align $align)"
            fail=1
        fi
    done < <(readelf -lW "$so" | awk '$1 == "LOAD" {print $NF}')
done < <(find "$WORK" -name '*.so')

if (( count == 0 )); then
    if [[ "$REQUIRE_NATIVE" == "1" ]]; then
        echo "::error::no .so files found"
        exit 1
    fi
    echo "alignment_guard: no .so files, nothing to check"
fi
exit "$fail"
