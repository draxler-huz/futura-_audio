# :net

Placeholder module. It is empty at Stage 0.

This is the only module that may contain network code. `ci/guards/network_guard.sh` checks `:app` and `:engine`
for network APIs, and the merged release manifest for INTERNET. Those checks fail if network code appears outside
`:net`.

A later stage adds the `TrackFetcher` implementation here, and `:app` registers it. Until then, remote tracks stay
PENDING and the fallback sound plays.
