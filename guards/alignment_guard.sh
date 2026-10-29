#!/usr/bin/env bash
# Fails if any native library in the APK is not 16 KB page aligned.
set -euo pipefail

APK="${1:?usage: alignment_guard.sh <apk>}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

unzip -q "$APK" 'lib/*' -d "$WORK" || { echo "::error::no native libraries in APK"; exit 1; }

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

(( count > 0 )) || { echo "::error::no .so files found"; exit 1; }
exit "$fail"
