#!/usr/bin/env bash
# Invoked by android-emulator-runner after the API 35 device has booted.
# Keep the instrumentation exit code intact while always capturing diagnostics.
set -euo pipefail

mkdir -p artifacts
printf '%s\n' 'initializing' > artifacts/emulator-phase.txt
# A compact-screen pass temporarily overrides display size and font scale.
# The EXIT trap restores both even if screenshot capture fails.
display_overridden=0
original_font_scale=null

collect_diagnostics() {
  local status=$?
  trap - EXIT
  set +e
  if [[ "$display_overridden" == 1 ]]; then
    timeout 10s adb shell wm size reset >/dev/null 2>&1
    if [[ "$original_font_scale" == null ]]; then
      timeout 10s adb shell settings delete system font_scale >/dev/null 2>&1
    else
      timeout 10s adb shell settings put system font_scale "$original_font_scale" >/dev/null 2>&1
    fi
  fi
  printf '%s\n' "$status" > artifacts/emulator-exit-status.txt
  printf 'workflow_run_id=%s\ncommit=%s\nphase=%s\nscript_exit=%s\n' \
    "${GITHUB_RUN_ID:-unknown}" "${GITHUB_SHA:-unknown}" \
    "$(cat artifacts/emulator-phase.txt 2>/dev/null || printf unknown)" "$status" \
    > artifacts/emulator-provenance.txt
  # Bound diagnostics independently: an unresponsive ADB daemon must not
  # consume the remaining workflow budget or hide the original test failure.
  timeout 15s adb shell getprop ro.build.version.sdk > artifacts/android-version.txt 2>&1
  timeout 15s adb logcat -d -t 3000 -v threadtime > artifacts/emulator-logcat.txt 2>&1
  timeout 15s adb shell dumpsys activity activities > artifacts/activity-state.txt 2>&1
  timeout 15s adb shell dumpsys window > artifacts/window-state.txt 2>&1
  timeout 15s adb shell dumpsys media.audio_policy > artifacts/audio-policy.txt 2>&1
  timeout 15s adb shell dumpsys media.audio_flinger > artifacts/audio-flinger.txt 2>&1
  timeout 15s adb shell dumpsys midi > artifacts/midi-state.txt 2>&1
  timeout 15s adb shell dumpsys meminfo com.orbital.synth > artifacts/app-memory.txt 2>&1
  timeout 15s adb shell pm path com.orbital.synth > artifacts/installed-package.txt 2>&1
  {
    timeout 10s adb shell wm size
    timeout 10s adb shell wm density
  } > artifacts/display-state.txt 2>&1
  timeout 15s adb exec-out screencap -p > artifacts/diagnostic-screen.png 2>/dev/null
  exit "$status"
}
trap collect_diagnostics EXIT

# The emulator runner waits for boot, but package manager initialization may
# still lag. A bounded readiness gate avoids misdiagnosing that race as a test failure.
printf '%s\n' 'waiting-for-package-manager' > artifacts/emulator-phase.txt
package_manager_ready=0
for attempt in {1..45}; do
  if timeout 8s adb shell pm list packages com.android.settings 2>/dev/null \
      | grep -q 'package:com.android.settings'; then
    package_manager_ready=1
    break
  fi
  sleep 2
done
if [[ "$package_manager_ready" != 1 ]]; then
  echo 'Android package manager was not ready within 90 seconds.' >&2
  exit 1
fi

# Fail immediately if any JUnit instrumentation test fails.
printf '%s\n' 'instrumentation' > artifacts/emulator-phase.txt
timeout 30m gradle :app:connectedDebugAndroidTest --stacktrace --no-daemon --console=plain

# Android Gradle Plugin's connected test runner may remove the target APK
# during cleanup. A green instrumentation result does not guarantee the app
# is still installed, so explicitly restore the built APK for the UI capture.
printf '%s\n' 'reinstalling-debug-apk' > artifacts/emulator-phase.txt
debug_apk='app/build/outputs/apk/debug/app-debug.apk'
if [[ ! -s "$debug_apk" ]]; then
  echo "Debug APK missing or empty after instrumentation: $debug_apk" >&2
  exit 1
fi
timeout 120s adb install -r "$debug_apk"
installed_package="$(timeout 15s adb shell pm path com.orbital.synth | tr -d '\r')"
if [[ "$installed_package" != *'package:'* ]]; then
  echo "com.orbital.synth is not installed after adb install: $installed_package" >&2
  exit 1
fi

printf '%s\n' 'launching-portrait' > artifacts/emulator-phase.txt
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

printf '%s\n' 'portrait-screenshot' > artifacts/emulator-phase.txt
# Allow the first Compose frame to settle after window focus.
sleep 2
adb exec-out screencap -p > artifacts/orbital-emulator.png
test -s artifacts/orbital-emulator.png
test "$(file -b --mime-type artifacts/orbital-emulator.png)" = "image/png"
printf 'foreground_window=verified\nscreenshot_sha256=%s\n' \
  "$(sha256sum artifacts/orbital-emulator.png | cut -d' ' -f1)" \
  > artifacts/emulator-summary.txt

# Exercise the same real Compose activity at 360 dp wide with 1.3x system fonts.
# Pixel dimensions are calculated from emulator density (wm size accepts px).
# This detects a different class of layout / process failures than the default
# Pixel 6 screenshot without inventing a real-device accessibility claim.
printf '%s\n' 'compact-configuration' > artifacts/emulator-phase.txt
original_font_scale="$(adb shell settings get system font_scale | tr -d '\r\n')"
density="$(adb shell wm density | tr -d '\r' | grep -E '(Physical|Override) density:' | tail -1 | grep -Eo '[0-9]+' | tail -1 || true)"
if [[ ! "$density" =~ ^[0-9]+$ ]] || (( density < 120 || density > 1000 )); then
  echo "Could not determine sensible emulator density: $density" >&2
  exit 1
fi
compact_width_px=$((360 * density / 160))
compact_height_px=$((780 * density / 160))
display_overridden=1
adb shell wm size "${compact_width_px}x${compact_height_px}"
adb shell settings put system font_scale 1.3
adb shell am force-stop com.orbital.synth
adb shell am start -W -n com.orbital.synth/.MainActivity > artifacts/compact-activity-launch.txt
grep -q '^Status: ok' artifacts/compact-activity-launch.txt

printf '%s\n' 'compact-screenshot' > artifacts/emulator-phase.txt
compact_ready=0
for attempt in {1..45}; do
  adb shell dumpsys window > artifacts/compact-window-state.txt 2>&1 || true
  if adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth' artifacts/compact-window-state.txt; then
    compact_ready=1
    break
  fi
  sleep 1
done
if [[ "$compact_ready" != 1 ]]; then
  echo 'ORBITAL did not focus at 360 dp / 1.3x font scale.' >&2
  exit 1
fi
sleep 2
adb exec-out screencap -p > artifacts/orbital-compact-emulator.png
test -s artifacts/orbital-compact-emulator.png
test "$(file -b --mime-type artifacts/orbital-compact-emulator.png)" = "image/png"
printf 'compact_width_dp=360\ncompact_font_scale=1.3\ncompact_screenshot_sha256=%s\n' \
  "$(sha256sum artifacts/orbital-compact-emulator.png | cut -d' ' -f1)" \
  >> artifacts/emulator-summary.txt
printf '%s\n' 'complete' > artifacts/emulator-phase.txt
