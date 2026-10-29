#!/usr/bin/env bash
# Fails if the merged release manifest requests INTERNET,
# or if network APIs appear in :app or :engine. The :net module is exempt.
set -euo pipefail
cd "$(dirname "$0")/../.."

MANIFEST=$(find app/build/intermediates -name AndroidManifest.xml -path '*merged*' -path '*release*' | head -n 1)
[[ -n "$MANIFEST" ]] || { echo "::error::merged release manifest not found"; exit 1; }

fail=0

if grep -q 'android.permission.INTERNET' "$MANIFEST"; then
    echo "::error::INTERNET permission found in merged manifest"
    fail=1
fi

HITS=$(grep -rnE 'java\.net\.|import java\.net|HttpURLConnection|okhttp|SocketChannel|\bSocket\(|sys/socket\.h' \
    app/src engine/src || true)
if [[ -n "$HITS" ]]; then
    echo "::error::network API used outside :net"
    echo "$HITS"
    fail=1
fi

exit "$fail"
