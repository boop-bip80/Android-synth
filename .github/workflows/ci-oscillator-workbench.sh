#!/usr/bin/env bash
# Actual production MainActivity oscillator editor, captured through instrumented
# Compose user interactions and Android's live UiAutomation framebuffer API.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/oscillator-capture-phase.txt
summary=artifacts/oscillator-capture-summary.txt
printf 'begin\n' > "$phase"
printf 'run=%s\ncommit=%s\n' "$GITHUB_RUN_ID" "$GITHUB_SHA" > "$summary"

finish() {
  local result=$?
  trap - EXIT
  set +e
  printf 'exit=%s\nphase=%s\n' "$result" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  timeout 15s adb logcat -d -t 1000 -v threadtime > artifacts/oscillator-capture-logcat.txt 2>&1
  exit "$result"
}
trap finish EXIT

printf 'install-test-runner\n' > "$phase"
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$test_apk" ]] || { echo "Instrumentation APK missing: $test_apk" >&2; exit 1; }
timeout 120s adb install -r "$test_apk" > artifacts/oscillator-test-apk-install.txt

printf 'launch\n' > "$phase"
timeout 12s adb shell am force-stop com.orbital.synth >/dev/null 2>&1 || true
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/oscillator-activity-launch.txt
ready=0
for attempt in {1..60}; do
  timeout 12s adb shell dumpsys window > artifacts/oscillator-window-state.txt 2>&1 || true
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/oscillator-window-state.txt &&
     ! grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' artifacts/oscillator-window-state.txt; then
    ready=1
    break
  fi
  sleep 1
done
[[ "$ready" == 1 ]] || { echo 'ORBITAL never owned the drawn emulator window.' >&2; exit 1; }
printf 'launch_focus=MainActivity\n' >> "$summary"

size_text="$(timeout 12s adb shell wm size | tr -d '\r')"
screen_xy="$(python3 - "$size_text" <<'PY'
import re
import sys
matches = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", sys.argv[1])
if not matches:
    raise SystemExit("Cannot read actual emulator screen dimensions")
w,h = map(int, matches[-1])
if w < 600 or h < 1000 or w >= h:
    raise SystemExit(f"Invalid portrait emulator dimensions {w}x{h}")
print(w,h)
PY
)"
read -r screen_w screen_h <<< "$screen_xy"
printf 'display=%sx%s\n' "$screen_w" "$screen_h" >> "$summary"

printf 'instrument-live-workbench\n' > "$phase"
# Real Activity + engine: scroll Compose editor, add OSC1 and tap SAW before
# sampling the actual emulator screen. There are no synthetic fallback images.
timeout 180s adb shell am instrument -w -r \
  -e class 'com.orbital.synth.ui.OscillatorWorkbenchCaptureTest#capturesRunningOscillatorEditorAfterUserConfiguration' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/oscillator-capture-instrumentation.txt
grep -Eq 'OK \(1 test\)' artifacts/oscillator-capture-instrumentation.txt || {
  echo 'Live oscillator instrumentation did not pass its capture test.' >&2
  exit 1
}
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' artifacts/oscillator-capture-instrumentation.txt; then
  echo 'Instrumentation reported failure.' >&2
  exit 1
fi
printf 'oscillator_1=active\nwaveform_tap=SAW\ninstrumentation=1_passed\n' >> "$summary"

printf 'extract-real-framebuffer\n' > "$phase"
# Debug-only run-as reads exact UiAutomation.takeScreenshot() PNG bytes saved
# by the running app's instrumentation test. This stage never draws an image.
timeout 30s adb exec-out run-as com.orbital.synth cat files/orbital-oscillator-workbench.png \
  > artifacts/orbital-oscillator-workbench.png
test -s artifacts/orbital-oscillator-workbench.png
test "$(file -b --mime-type artifacts/orbital-oscillator-workbench.png)" = image/png

python3 - artifacts/orbital-oscillator-workbench.png "$screen_w" "$screen_h" artifacts/orbital-emulator.png <<'PY'
import hashlib
import struct
import sys
path, wanted_w, wanted_h, original = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
with open(path,"rb") as image:
    header = image.read(24)
    image.seek(0,2)
    length = image.tell()
if len(header)!=24 or header[:8]!=b"\x89PNG\r\n\x1a\n" or header[12:16]!=b"IHDR":
    raise SystemExit("Captured framebuffer has invalid PNG header")
w,h = struct.unpack(">II",header[16:24])
if (w,h)!=(wanted_w,wanted_h) or length<4096:
    raise SystemExit(f"Invalid screenshot geometry/bytes: {w}x{h}, {length}")
def digest(file_path):
    with open(file_path,"rb") as image:
        return hashlib.sha256(image.read()).hexdigest()
if digest(path)==digest(original):
    raise SystemExit("Workbench screenshot identical to default portrait screenshot")
print(f"Verified real oscillator screenshot: {w}x{h}, {length} bytes, SHA256={digest(path)}")
PY
printf 'capture_bytes=%s\nscreenshot_sha256=%s\n' \
  "$(wc -c < artifacts/orbital-oscillator-workbench.png | tr -d ' ')" \
  "$(sha256sum artifacts/orbital-oscillator-workbench.png | cut -d' ' -f1)" >> "$summary"
printf 'complete\n' > "$phase"
