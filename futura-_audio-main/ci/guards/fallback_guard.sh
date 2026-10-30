#!/usr/bin/env bash
# Fails if the embedded fail-safe alarm sound is missing or is not a valid WAV file.
set -euo pipefail
cd "$(dirname "$0")/../.."

F=app/src/main/res/raw/futura_fallback.wav
[[ -f "$F" ]] || { echo "::error::embedded fallback sound missing: $F"; exit 1; }
SIZE=$(wc -c < "$F")
(( SIZE > 1024 )) || { echo "::error::fallback sound is too small ($SIZE bytes)"; exit 1; }
[[ "$(head -c 4 "$F")" == "RIFF" && "$(head -c 12 "$F" | tail -c 4)" == "WAVE" ]] \
    || { echo "::error::fallback sound is not a WAV file"; exit 1; }
echo "fallback_guard: ok ($SIZE bytes)"
