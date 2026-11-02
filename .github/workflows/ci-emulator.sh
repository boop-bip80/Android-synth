#!/usr/bin/env bash
# Invoked by android-emulator-runner after the API 35 device has booted.
# Keep the instrumentation exit code intact while always capturing diagnostics.
set -euo pipefail

mkdir -p artifacts

collect_diagnostics() {
  local status=$?
  trap - EXIT
  set +e
  printf '%s\n' "$status" > artifacts/emulator-exit-status.txt
  adb shell getprop ro.build.version.sdk > artifacts/android-version.txt 2>&1
  adb logcat -d -t 3000 -v threadtime > artifacts/emulator-logcat.txt 2>&1
  adb shell dumpsys activity activities > artifacts/activity-state.txt 2>&1
  adb shell dumpsys window > artifacts/window-state.txt 2>&1
  adb exec-out screencap -p > artifacts/diagnostic-screen.png 2>/dev/null
  exit "$status"
}
trap collect_diagnostics EXIT

# Fail immediately if any JUnit instrumentation test fails.
gradle :app:connectedDebugAndroidTest --stacktrace --no-daemon --console=plain

# The test runner may close its Activity. Launch the real app for the screenshot.
adb shell am force-stop com.orbital.synth || true
adb shell am start -W -n com.orbital.synth/.MainActivity | tee artifacts/activity-launch.txt
grep -q '^Status: ok' artifacts/activity-launch.txt

# "am start" returning OK only means the intent was accepted. Verify that the
# app's window is actually focused before saving a screenshot as UI evidence.
app_ready=0
for attempt in {1..45}; do
  adb shell dumpsys window > artifacts/window-state.txt 2>&1 || true
  if adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth' artifacts/window-state.txt; then
    app_ready=1
    break
  fi
  sleep 1
done
if [[ "$app_ready" != 1 ]]; then
  echo "ORBITAL did not become the focused foreground window within 45 seconds." >&2
  exit 1
fi

# Allow the first Compose frame to settle after window focus.
sleep 2
adb exec-out screencap -p > artifacts/orbital-emulator.png
test -s artifacts/orbital-emulator.png
test "$(file -b --mime-type artifacts/orbital-emulator.png)" = "image/png"
printf 'foreground_window=verified\nscreenshot_sha256=%s\n' \
  "$(sha256sum artifacts/orbital-emulator.png | cut -d' ' -f1)" \
  > artifacts/emulator-summary.txt
