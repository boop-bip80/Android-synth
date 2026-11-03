#!/usr/bin/env bash
# Fresh API35 emulator preparation for the REAL AudioTrack + FOCUS framebuffer proof.
# The isolated stage lane prevents a costly graphics capture from running twice
# after a long Android instrumentation suite, but NEVER skips the capture gate.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/focus-bootstrap-phase.txt
summary=artifacts/focus-bootstrap-summary.txt
printf 'start\n' > "$phase"
printf 'run=%s\ncommit=%s\nsuite=stage\n' "$GITHUB_RUN_ID" "$GITHUB_SHA" > "$summary"
finish() {
  local status=$?
  trap - EXIT
  set +e
  printf 'exit_status=%s\nphase=%s\n' "$status" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  if [[ "$status" != 0 ]]; then
    timeout 10s adb devices -l > artifacts/focus-bootstrap-adb.txt 2>&1
    timeout 12s adb logcat -d -t 900 -v threadtime > artifacts/focus-bootstrap-logcat.txt 2>&1
  fi
  exit "$status"
}
trap finish EXIT

printf 'assemble-real-app-and-android-test-apks\n' > "$phase"
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --no-daemon --console=plain
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$app_apk" && -s "$test_apk" ]] || {
  echo 'The production app or Android instrumentation APK is missing.' >&2
  exit 1
}

printf 'install-apks\n' > "$phase"
timeout 120s adb install -r "$app_apk" > artifacts/focus-bootstrap-app-install.txt
timeout 120s adb install -r "$test_apk" > artifacts/focus-bootstrap-test-install.txt
timeout 15s adb shell pm path com.orbital.synth > artifacts/focus-bootstrap-package.txt
grep -q 'package:' artifacts/focus-bootstrap-package.txt || {
  echo 'Actual app package not installed in the fresh emulator.' >&2
  exit 1
}

printf 'launch-production-MainActivity\n' > "$phase"
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity   > artifacts/focus-bootstrap-launch.txt
ready=0
for attempt in {1..70}; do
  timeout 10s adb shell dumpsys window > artifacts/focus-bootstrap-window.txt 2>&1 || true
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt &&
     ! grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' artifacts/focus-bootstrap-window.txt; then
    ready=1
    break
  fi
  sleep 1
done
[[ "$ready" == 1 ]] || {
  echo 'Fresh emulator never focused the drawn production MainActivity.' >&2
  exit 1
}
sleep 2

printf 'capture-genuine-normal-app-baseline\n' > "$phase"
captured=0
for attempt in 1 2; do
  # Only bytes actually emitted by Android's current displayed framebuffer.
  if timeout 25s adb exec-out screencap -p > artifacts/orbital-emulator.png &&
     test -s artifacts/orbital-emulator.png &&
     test "$(file -b --mime-type artifacts/orbital-emulator.png)" = image/png &&
     python3 - artifacts/orbital-emulator.png <<'PY'
import struct
import sys
with open(sys.argv[1], "rb") as frame:
    header = frame.read(24)
    frame.seek(0, 2)
    size = frame.tell()
    frame.seek(-12, 2)
    ending = frame.read(12)
if header[:8] != b"\\x89PNG\\r\\n\\x1a\\n" or header[12:16] != b"IHDR":
    raise SystemExit("Missing Android screenshot PNG header")
width, height = struct.unpack(">II", header[16:24])
if width < 600 or height < 1000 or width >= height or size < 4096:
    raise SystemExit(f"Invalid genuine portrait baseline {width}x{height} size={size}")
if ending != b"\\x00\\x00\\x00\\x00IEND\\xaeB\\x60\\x82":
    raise SystemExit("Truncated genuine portrait PNG")
print(f"PASS genuine foreground normal-app framebuffer {width}x{height}, {size} bytes")
PY
  then
    captured=1
    break
  fi
  printf 'baseline_retry=%s\n' "$attempt" >> "$summary"
  timeout 8s adb devices -l >> "$summary" 2>&1 || true
  sleep 2
done
[[ "$captured" == 1 ]] || {
  echo 'No actual normal-app portrait baseline PNG from the emulator.' >&2
  exit 1
}
timeout 12s adb shell dumpsys window > artifacts/focus-bootstrap-window.txt 2>&1
grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt || {
  echo 'Real app lost OS window focus during baseline screenshot.' >&2
  exit 1
}
printf 'normal_baseline_sha256=%s\n'   "$(sha256sum artifacts/orbital-emulator.png | cut -d' ' -f1)" >> "$summary"
printf 'ready-for-strict-focus-PCM-gesture-instrumentation\n' > "$phase"
