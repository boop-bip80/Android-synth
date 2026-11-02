#!/usr/bin/env bash
# Invoked by android-emulator-runner after the API 35 device has booted.
set -euo pipefail

mkdir -p artifacts

collect_diagnostics() {
  # Capture evidence both on success and on test / launch failure.
  adb logcat -d -v threadtime > artifacts/emulator-logcat.txt 2>&1 || true
  adb shell dumpsys activity activities > artifacts/activity-state.txt 2>&1 || true
  adb exec-out screencap -p > artifacts/diagnostic-screen.png 2>/dev/null || true
}
trap collect_diagnostics EXIT

gradle :app:connectedDebugAndroidTest --stacktrace --no-daemon
adb shell am force-stop com.orbital.synth || true
adb shell am start -W -n com.orbital.synth/.MainActivity | tee artifacts/activity-launch.txt
grep -q '^Status: ok' artifacts/activity-launch.txt
sleep 4
adb exec-out screencap -p > artifacts/orbital-emulator.png
test -s artifacts/orbital-emulator.png
