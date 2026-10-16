#!/usr/bin/env bash
# Runs the smoke test for the release APK first (the one customers get), then the debug APK. Fails if either fails.
set -u
cd "$(dirname "$0")/.."
rc=0
if [ -f out/ScreenshotAssistant-release.apk ]; then bash scripts/emulator-smoke.sh out/ScreenshotAssistant-release.apk release || rc=1; else echo "no release APK to test"; fi
bash scripts/emulator-smoke.sh out/ScreenshotAssistant-debug-signed.apk debug || rc=1
exit $rc
