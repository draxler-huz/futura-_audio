#!/usr/bin/env bash
# Fails if the release runtime classpath contains known analytics or crash-reporting libraries.
set -euo pipefail
cd "$(dirname "$0")/../.."

DEPS=$(./gradlew --no-daemon -q :app:dependencies --configuration releaseRuntimeClasspath)
DENY='firebase|crashlytics|sentry|appcenter|amplitude|mixpanel|bugsnag|newrelic|datadog|google-analytics|flurry'

HITS=$(grep -iE "$DENY" <<< "$DEPS" || true)
if [[ -n "$HITS" ]]; then
    echo "::error::telemetry or analytics library in dependency tree"
    echo "$HITS"
    exit 1
fi
