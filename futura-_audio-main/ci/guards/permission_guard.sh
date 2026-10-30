#!/usr/bin/env bash
# Fails if the merged release manifest declares a permission not on the allowlist for this stage.
set -euo pipefail
cd "$(dirname "$0")/../.."

MANIFEST=$(find app/build/intermediates -name AndroidManifest.xml -path '*merged*' -path '*release*' | head -n 1)
[[ -n "$MANIFEST" ]] || { echo "::error::merged release manifest not found"; exit 1; }

ALLOWED=$(grep -vE '^[[:space:]]*(#|$)' ci/guards/allowed_permissions.txt || true)

fail=0
while IFS= read -r perm; do
    [[ -z "$perm" ]] && continue
    if ! grep -qxF "$perm" <<< "$ALLOWED"; then
        echo "::error::permission not allowed at this stage: $perm"
        fail=1
    fi
done < <(grep -oE '<uses-permission[^>]*android:name="[^"]+"' "$MANIFEST" \
    | sed -E 's/.*android:name="([^"]+)".*/\1/' | sort -u)

exit "$fail"
