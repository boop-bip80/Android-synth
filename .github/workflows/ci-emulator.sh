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
compact_logcat_pid=

collect_diagnostics() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "$compact_logcat_pid" ]]; then
    kill "$compact_logcat_pid" >/dev/null 2>&1 || true
    wait "$compact_logcat_pid" >/dev/null 2>&1 || true
  fi
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
  # Logcat is streamed before instrumentation, so test-stage messages survive
  # an emulator process crash when adb logcat -d is no longer available.
  printf '\n=== ORBITAL device-side compact/graphics diagnostic log (last 180 lines) ===\n'
  if [[ -s artifacts/compact-test-live-logcat.txt ]]; then
    tail -n 180 artifacts/compact-test-live-logcat.txt
  else
    printf '%s\n' 'No streamed compact/graphics logcat was captured.'
  fi
  printf '\n=== Android instrumentation failure XML excerpts ===\n'
  if [[ -d app/build/outputs/androidTest-results ]]; then
    while IFS= read -r report; do
      if grep -Eq '<failure|<error' "$report"; then
        printf 'XML: %s\n' "$report"
        grep -n -m 2 -C 3 -E '<failure|<error' "$report" | cut -c 1-400
      fi
    done < <(find app/build/outputs/androidTest-results -type f -name '*.xml' -print)
  fi
  printf '\n=== ADB device list after instrumentation (exit %s) ===\n' "$status"
  timeout 8s adb devices -l || true
  exit "$status"
}
trap collect_diagnostics EXIT

# A slow cold start can make "am start -W" report Status: timeout even
# when the Activity is still alive. Launch without the framework's short wait,
# then require the actual application surface (not the splash window) to draw.
launch_orbital() {
  local launch_log="$1"
  timeout 30s adb shell am start -n com.orbital.synth/.MainActivity | tee "$launch_log"
  if grep -Eq 'Error type [0-9]+|Error:|Exception|Permission Denial' "$launch_log"; then
    echo "Android rejected the ORBITAL Activity launch:" >&2
    cat "$launch_log" >&2
    return 1
  fi
}

orbital_window_drawn() {
  local snapshot="$1"
  grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' "$snapshot" || return 1
  # A splash window may coexist with a focused Activity that has not drawn.
  if grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' "$snapshot"; then
    return 1
  fi
  awk '
    /^[[:space:]]*Window #[0-9]+ Window\{.*com[.]orbital[.]synth\/com[.]orbital[.]synth[.]MainActivity/ { in_main=1; next }
    in_main && /^[[:space:]]*Window #[0-9]+ Window\{/ { in_main=0 }
    in_main && /Surface: shown=true/ { drawn=1 }
    END { exit !drawn }
  ' "$snapshot"
}

# A nonempty, MIME-recognized PNG can still be a truncated or tiny placeholder.
# Assert the real portrait capture has the expected usable display geometry.
# Read only the PNG signature and IHDR, requiring no Python packages or image tools.
verify_portrait_capture() {
  python3 - "$1" <<'PY'
import struct
import sys

image_path = sys.argv[1]
with open(image_path, "rb") as image:
    header = image.read(24)
if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
    raise SystemExit(f"{image_path}: missing PNG signature or IHDR")
width, height = struct.unpack(">II", header[16:24])
if width < 600 or height < 1000 or width >= height:
    raise SystemExit(f"{image_path}: implausible portrait screenshot {width}x{height}")
print(f"{width}x{height}")
PY
}

# A Pixel Launcher ANR is external to ORBITAL, but its system dialog can
# steal focus over a correctly drawn ORBITAL window. Recover only this exact
# external package; any ORBITAL ANR still fails the strict app-focus gate.
launcher_anr_overlay_visible() {
  local snapshot="$1"
  grep -Eq 'mCurrentFocus=.*Application Not Responding: com[.]google[.]android[.]apps[.]nexuslauncher(}|$)' "$snapshot"
}

recover_launcher_anr_overlay() {
  local phase="$1"
  local prior_attempts="$2"
  if (( prior_attempts == 0 )); then
    printf '%s: dismiss external Pixel Launcher ANR dialog with BACK\n' "$phase" >> artifacts/external-launcher-anr.txt
    timeout 10s adb shell input keyevent KEYCODE_BACK || true
  else
    printf '%s: launcher ANR persisted; force-stop only Pixel Launcher, relaunch ORBITAL\n' "$phase" >> artifacts/external-launcher-anr.txt
    timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher || true
    launch_orbital "artifacts/${phase}-launcher-recovery-launch.txt"
  fi
}

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
# Start BEFORE the failing test; background logcat retains the last device
# messages even if emulator-5554 vanishes before EXIT diagnostics can query it.
adb logcat -v threadtime -s OrbitalCompactTest:I AndroidRuntime:E ActivityManager:W SurfaceFlinger:E HWUI:E libc:F DEBUG:E '*:S' > artifacts/compact-test-live-logcat.txt 2>&1 &
compact_logcat_pid=$!
# Every remaining Compose/UI test runs normally on API35. The previous
# artificial 350dp Box fixtures have been replaced with two stronger real
# device keyboard/navigation checks in ci-compact-geometry.sh.
printf 'suite=general\nexpected_at_least=38\n' > artifacts/instrumentation-suite.txt
timeout 30m gradle :app:connectedDebugAndroidTest --stacktrace --no-daemon --console=plain
python3 - <<'PY'
import glob
from xml.etree import ElementTree as ET
files = glob.glob("app/build/outputs/androidTest-results/connected/debug/*.xml")
cases = sum(len(ET.parse(path).findall(".//testcase")) for path in files)
print(f"ORBITAL general instrumentation: {cases} testcases in {len(files)} XML reports")
if cases < 38:
    raise SystemExit(f"Expected at least 38 instrumented tests, got {cases}")
PY

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
launch_orbital artifacts/activity-launch.txt

# Require a real drawn MainActivity surface after the splash disappears.
# A live process, an external system dialog, or focused splash cannot pass.
app_ready=0
launcher_anr_recoveries=0
for attempt in {1..90}; do
  adb shell dumpsys window > artifacts/window-state.txt 2>&1 || true
  if adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     orbital_window_drawn artifacts/window-state.txt; then
    app_ready=1
    break
  fi
  if (( launcher_anr_recoveries < 2 )) &&
     launcher_anr_overlay_visible artifacts/window-state.txt; then
    recover_launcher_anr_overlay portrait "$launcher_anr_recoveries"
    launcher_anr_recoveries=$((launcher_anr_recoveries + 1))
    sleep 2
  fi
  sleep 1
done
if [[ "$app_ready" != 1 ]]; then
  echo "ORBITAL MainActivity did not draw its surface within 90 readiness polls." >&2
  exit 1
fi

printf '%s\n' 'portrait-screenshot' > artifacts/emulator-phase.txt
# Allow the first Compose frame to settle after window focus.
sleep 2
adb exec-out screencap -p > artifacts/orbital-emulator.png
test -s artifacts/orbital-emulator.png
test "$(file -b --mime-type artifacts/orbital-emulator.png)" = "image/png"
portrait_geometry="$(verify_portrait_capture artifacts/orbital-emulator.png)"
printf 'foreground_window=verified\nportrait_ready_poll=%s\nportrait_geometry=%s\nscreenshot_sha256=%s\n' \
  "$attempt" "$portrait_geometry" "$(sha256sum artifacts/orbital-emulator.png | cut -d' ' -f1)" \
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
launch_orbital artifacts/compact-activity-launch.txt

printf '%s\n' 'compact-screenshot' > artifacts/emulator-phase.txt
compact_ready=0
launcher_anr_recoveries=0
for attempt in {1..90}; do
  adb shell dumpsys window > artifacts/compact-window-state.txt 2>&1 || true
  if adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     orbital_window_drawn artifacts/compact-window-state.txt; then
    compact_ready=1
    break
  fi
  if (( launcher_anr_recoveries < 2 )) &&
     launcher_anr_overlay_visible artifacts/compact-window-state.txt; then
    recover_launcher_anr_overlay compact "$launcher_anr_recoveries"
    launcher_anr_recoveries=$((launcher_anr_recoveries + 1))
    sleep 2
  fi
  sleep 1
done
if [[ "$compact_ready" != 1 ]]; then
  echo 'ORBITAL MainActivity did not draw at 360 dp / 1.3x font scale.' >&2
  exit 1
fi
sleep 2
adb exec-out screencap -p > artifacts/orbital-compact-emulator.png
test -s artifacts/orbital-compact-emulator.png
test "$(file -b --mime-type artifacts/orbital-compact-emulator.png)" = "image/png"
compact_geometry="$(verify_portrait_capture artifacts/orbital-compact-emulator.png)"
printf 'compact_width_dp=360\ncompact_font_scale=1.3\ncompact_ready_poll=%s\ncompact_geometry=%s\ncompact_screenshot_sha256=%s\n' \
  "$attempt" "$compact_geometry" "$(sha256sum artifacts/orbital-compact-emulator.png | cut -d' ' -f1)" \
  >> artifacts/emulator-summary.txt
printf '%s\n' 'complete' > artifacts/emulator-phase.txt
