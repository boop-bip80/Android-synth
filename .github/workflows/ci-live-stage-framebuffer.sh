#!/usr/bin/env bash
# Real Android framebuffer while an actual MainActivity synth note is held.
# Uses Compose touch injection + PCM accepted by AndroidAudioOutput AudioTrack;
# NEVER generates or substitutes images on the host.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/held-stage-capture-phase.txt
summary=artifacts/held-stage-capture-summary.txt
printf 'begin\n' > "$phase"
printf 'run=%s\ncommit=%s\n' "$GITHUB_RUN_ID" "$GITHUB_SHA" > "$summary"

finish() {
  local result=$?
  trap - EXIT
  set +e
  printf 'exit=%s\nphase=%s\n' "$result" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  timeout 15s adb logcat -d -t 1200 -v threadtime > artifacts/held-stage-capture-logcat.txt 2>&1
  exit "$result"
}
trap finish EXIT

printf 'install-android-test-runner\n' > "$phase"
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$test_apk" ]] || { echo "Missing built Android instrumentation APK: $test_apk" >&2; exit 1; }
timeout 120s adb install -r "$test_apk" > artifacts/held-stage-test-apk-install.txt

# Prove this captures the production app on a genuinely focused device.
printf 'launch\n' > "$phase"
timeout 12s adb shell am force-stop com.orbital.synth >/dev/null 2>&1 || true
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/held-stage-activity-launch.txt
focused=0
for attempt in {1..60}; do
  timeout 12s adb shell dumpsys window > artifacts/held-stage-window-state.txt 2>&1 || true
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/held-stage-window-state.txt &&
     ! grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' artifacts/held-stage-window-state.txt; then
    focused=1
    break
  fi
  sleep 1
done
[[ "$focused" == 1 ]] || { echo 'Stage proof: production MainActivity failed real focus gate.' >&2; exit 1; }

size_text="$(timeout 12s adb shell wm size | tr -d '\r')"
dimensions="$(python3 - "$size_text" <<'PY'
import re
import sys
matches = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", sys.argv[1])
if not matches:
    raise SystemExit("No physical Android display dimensions")
w,h = map(int,matches[-1])
if w < 600 or h < 1000 or w >= h:
    raise SystemExit(f"Not a real portrait phone viewport: {w}x{h}")
print(w,h)
PY
)"
read -r screen_w screen_h <<< "$dimensions"
printf 'focus=MainActivity\ndisplay=%sx%s\n' "$screen_w" "$screen_h" >> "$summary"

printf 'instrument-real-held-touch-and-pcm\n' > "$phase"
timeout 210s adb shell am instrument -w -r \
  -e class 'com.orbital.synth.ui.ImmersiveLiveFrameCaptureTest#photographsVividStageDuringRealAudioTrackHeldNoteAndReleasesSafely' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/held-stage-capture-instrumentation.txt
grep -Eq 'OK \(1 test\)' artifacts/held-stage-capture-instrumentation.txt || {
  echo 'Real held-note stage instrumented test did not pass.' >&2
  exit 1
}
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' artifacts/held-stage-capture-instrumentation.txt; then
  echo 'Android instrumentation reported a stage failure.' >&2
  exit 1
fi

printf 'extract-actual-device-frames\n' > "$phase"
# Android UiAutomation sampled both actual display frames. Read private bytes
# written by the real debug app; no host-side rendering or mock alternative.
for frame in orbital-stage-idle-proof.png orbital-stage-held-performance.png; do
  extracted=0
  for attempt in 1 2; do
    if timeout 30s adb exec-out run-as com.orbital.synth cat "files/$frame" > "artifacts/$frame" &&
       test -s "artifacts/$frame" &&
       test "$(file -b --mime-type "artifacts/$frame")" = image/png; then
      extracted=1
      break
    fi
    printf 'extract_retry=%s,file=%s\n' "$attempt" "$frame" >> "$summary"
    sleep 2
  done
  [[ "$extracted" == 1 ]] || { echo "No genuine Android screenshot bytes: $frame" >&2; exit 1; }
done
timeout 15s adb exec-out run-as com.orbital.synth cat files/orbital-stage-live-evidence.txt \
  > artifacts/held-stage-capture-evidence.txt
test -s artifacts/held-stage-capture-evidence.txt

python3 - artifacts/orbital-stage-idle-proof.png artifacts/orbital-stage-held-performance.png \
  artifacts/orbital-emulator.png artifacts/held-stage-capture-evidence.txt "$screen_w" "$screen_h" <<'PY'
import hashlib
import struct
import sys

idle, live, regular, evidence, width, height = sys.argv[1:5] + [int(v) for v in sys.argv[5:7]]
def validate_png(path):
    with open(path, "rb") as image:
        header = image.read(24)
        image.seek(0,2)
        length = image.tell()
        image.seek(-12,2)
        ending = image.read(12)
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise SystemExit(f"Missing genuine Android PNG header: {path}")
    dimensions = struct.unpack(">II", header[16:24])
    if dimensions != (width, height) or length < 4096:
        raise SystemExit(f"Truncated/wrong-size PNG: {path} {dimensions} {length}")
    if ending != b"\x00\x00\x00\x00IEND\xaeB\x60\x82":
        raise SystemExit(f"Invalid Android PNG end marker: {path}")
    return length

def sha(path):
    with open(path, "rb") as image:
        return hashlib.sha256(image.read()).hexdigest()

idle_bytes = validate_png(idle)
live_bytes = validate_png(live)
if sha(live) in (sha(idle), sha(regular)):
    raise SystemExit("Live stage frame identical to idle stage or keyboard portrait")

values = {}
with open(evidence, encoding="utf-8") as image_evidence:
    for line in image_evidence:
        key, sep, value = line.strip().partition("=")
        if sep:
            values[key] = value
for gate in ("audio_running", "note_held_during_capture"):
    if values.get(gate) != "true":
        raise SystemExit(f"Actual app did not prove {gate}")
if float(values["audio_pcm_rms"]) <= .002 or float(values["audio_pcm_peak"]) <= .004:
    raise SystemExit("No non-silent PCM submitted to the live Android AudioTrack")
idle_visible = int(values["idle_visible"])
live_visible = int(values["live_visible"])
idle_colorful = int(values["idle_colorful"])
live_colorful = int(values["live_colorful"])
sample_count = int(values["live_samples"])
# This first proof captures the normal STAGE, which intentionally has idle
# orbital artwork; true blank-black FOCUS is a separately gated mode.
if live_visible < idle_visible + max(100,sample_count//400):
    raise SystemExit("Held-note frame has no credible extra illuminated pixels")
if live_colorful < idle_colorful + max(50,sample_count//800):
    raise SystemExit("Held-note frame lacks newly illuminated chromatic filaments")
if values.get("live_dimensions") != f"{width}x{height}":
    raise SystemExit("In-app Android framebuffer geometry mismatched actual emulator")
print(f"PASS real AndroidAudioTrack PCM and held-note colored field; PNG {width}x{height} "
      f"idle={idle_bytes} live={live_bytes} bytes SHA256={sha(live)} "
      f"colorful={idle_colorful}->{live_colorful} visible={idle_visible}->{live_visible}")
PY
printf 'held_note=PCM_ACCEPTED\nvisual_field=LIVE_VISIBLE\nnote_up_and_exit=PASS\nscreenshot_sha256=%s\n' \
  "$(sha256sum artifacts/orbital-stage-held-performance.png | cut -d' ' -f1)" >> "$summary"
printf 'complete\n' > "$phase"
