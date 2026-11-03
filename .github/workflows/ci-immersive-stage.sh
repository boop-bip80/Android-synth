#!/usr/bin/env bash
# Screenshot of the genuinely running API35 Compose keyboardless performance mode.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/immersive-emulator-phase.txt
summary=artifacts/immersive-emulator-summary.txt
printf 'starting\n' > "$phase"
printf 'run=%s\ncommit=%s\n' "$GITHUB_RUN_ID" "$GITHUB_SHA" > "$summary"
finish() {
  local status=$?
  trap - EXIT
  set +e
  printf 'exit_status=%s\nphase=%s\n' "$status" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  timeout 12s adb logcat -d -t 1500 -v threadtime > artifacts/immersive-emulator-logcat.txt 2>&1
  exit "$status"
}
trap finish EXIT

printf 'launch\n' > "$phase"
timeout 12s adb shell am force-stop com.orbital.synth >/dev/null 2>&1 || true
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/immersive-activity-launch.txt
ready=0
for attempt in {1..70}; do
  timeout 12s adb shell dumpsys window > artifacts/immersive-window-state.txt 2>&1 || true
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/immersive-window-state.txt &&
     ! grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' artifacts/immersive-window-state.txt; then
    ready=1
    break
  fi
  sleep 1
done
[[ "$ready" == 1 ]] || { echo 'Immersive proof: MainActivity was not foreground-ready.' >&2; exit 1; }
sleep 2

dump_ui() {
  local name="$1"
  local path="artifacts/immersive-$name.xml"
  for retry in {1..4}; do
    if timeout 35s adb shell uiautomator dump /sdcard/orbital-immersive.xml > "artifacts/immersive-$name-dump.txt" 2>&1 &&
       timeout 15s adb exec-out cat /sdcard/orbital-immersive.xml > "$path" &&
       [[ -s "$path" ]] &&
       python3 - "$path" <<'PY'
import sys
import xml.etree.ElementTree as ET
assert list(ET.parse(sys.argv[1]).iter("node"))
PY
    then
      return 0
    fi
    sleep 2
  done
  echo "Cannot obtain Compose accessibility tree: $name" >&2
  return 1
}
verify_ui() {
  python3 - "$1" "$2" <<'PY'
import sys
import xml.etree.ElementTree as ET
path, mode = sys.argv[1:]
names = [n.attrib.get("content-desc", "") for n in ET.parse(path).iter("node")]
piano = sum("multitouch piano keyboard" in n.lower() for n in names)
chromatic = sum(n.lower().startswith(
    "twelve live chromatic liquid-glass keys, octave ") for n in names)
enter = names.count("Enter immersive performance")
exit = names.count("Exit immersive performance")
if mode == "normal":
    # Portrait SOUND now has exactly one real chromatic performance keybed.
    # Keep the strict on-device keyboard AND enter checks; never accept 0
    # buttons, stale PLAY piano, or two overlapping keyboard families.
    if piano != 0 or chromatic != 1 or enter != 1 or exit:
        raise SystemExit(
            f"Normal SOUND must have one chromatic keybed+enter/no legacy piano/exit: "
            f"piano={piano} glass={chromatic} enter={enter} exit={exit}")
elif mode == "stage":
    if piano or chromatic or enter or exit != 1:
        raise SystemExit(
            f"STAGE must hide BOTH keyboards+enter and expose exit: "
            f"piano={piano} glass={chromatic} enter={enter} exit={exit}")
else:
    raise SystemExit(f"Unknown screen {mode}")
print(f"PASS {mode}: piano={piano} glass={chromatic} enter={enter} exit={exit}")
PY
}
tap_action() {
  local target
  target="$(python3 - "$1" "$2" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
path, label = sys.argv[1:]
nodes = [n for n in ET.parse(path).iter("node") if n.attrib.get("content-desc") == label]
if len(nodes) != 1:
    raise SystemExit(f"Expected exactly one accessible '{label}', found {len(nodes)}")
# Compose can attach the description to a wrapper node while the click
# semantics live on its child. We verify the real transition after tapping,
# rather than requiring the wrapper itself to report clickable=true.
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", nodes[0].attrib.get("bounds", ""))
if not match:
    raise SystemExit(f"Invalid accessibility bounds for '{label}'")
left, top, right, bottom = map(int, match.groups())
if right <= left or bottom <= top:
    raise SystemExit(f"Empty accessible tap bounds for '{label}'")
print((left + right)//2, (top + bottom)//2)
PY
)"
  read -r x y <<< "$target"
  # Bounded ADB retries cover an emulator input-service stall without
  # treating a failed injection as a successful mode transition.
  local attempt output_file
  output_file=/dev/null
  if [[ "$2" == 'Exit immersive performance' ]]; then
    output_file=artifacts/immersive-exit-adb-input.txt
    printf 'exit_tap_x=%s\nexit_tap_y=%s\nsource_hierarchy=%s\n' \
      "$x" "$y" "$1" >> "$summary"
  fi
  for attempt in 1 2; do
    if timeout 8s adb shell input tap "$x" "$y" > "$output_file" 2>&1; then
      return 0
    fi
    printf 'tap_retry=%s label=%s\n' "$attempt" "$2" >> "$summary"
    timeout 5s adb devices -l >> "$summary" 2>&1 || true
    sleep 1
  done
  echo "Android touch command failed twice for '$2'." >&2
  return 1
}

printf 'regular\n' > "$phase"
dump_ui regular
verify_ui artifacts/immersive-regular.xml normal
printf 'enter\n' > "$phase"
tap_action artifacts/immersive-regular.xml 'Enter immersive performance'
sleep 2
dump_ui stage
verify_ui artifacts/immersive-stage.xml stage

# Gesture coordinates are derived from the *actual* canvas accessibility node.
gesture="$(python3 - artifacts/immersive-stage.xml <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
nodes = [n for n in ET.parse(sys.argv[1]).iter("node")
         if n.attrib.get("content-desc") == "Immersive sound field"]
if len(nodes) == 1:
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", nodes[0].attrib.get("bounds", ""))
    if m:
        l,t,r,b = map(int, m.groups())
        if r-l > 60 and b-t > 60:
            print(l+(r-l)//3, t+(b-t)//3, l+2*(r-l)//3, t+2*(b-t)//3)
PY
)"
# Serialize ADB commands. Concurrent input-swipe + screencap intermittently
# stalls the API35 emulator's ADB/graphics pipe under instrumented test load.
# The screenshot is taken immediately after genuine touch input; never synthesize
# or render a replacement image. A short-lived residual shockwave may remain.
if [[ -n "$gesture" ]]; then
  read -r x1 y1 x2 y2 <<< "$gesture"
  printf 'gesture\n' > "$phase"
  if timeout 9s adb shell input swipe "$x1" "$y1" "$x2" "$y2" 550 \
    > artifacts/immersive-gesture.txt 2>&1; then
    printf 'gesture=completed_android_input_swipe\n' >> "$summary"
  else
    printf 'gesture_swipe=timed_out_or_failed\n' >> "$summary"
    timeout 5s adb devices -l >> "$summary" 2>&1 || true
    # The fallback is a real touch on the measured live surface; never
    # manufacture gesture success or a synthetic screenshot.
    if timeout 9s adb shell input tap "$x1" "$y1" \
      > artifacts/immersive-gesture-fallback.txt 2>&1; then
      printf 'gesture=completed_android_input_tap_fallback\n' >> "$summary"
    else
      echo 'Both real Android gesture injection methods failed.' >&2
      exit 1
    fi
  fi
else
  printf 'gesture=skipped_canvas_semantics_merged\n' >> "$summary"
fi

printf 'capture\n' > "$phase"
captured=0
for capture_attempt in 1 2; do
  printf 'capture_attempt=%s\n' "$capture_attempt" >> "$summary"
  # A failed/partial capture is discarded on each iteration. No stale artwork
  # or previous frame is accepted as evidence of a newly opened stage.
  if timeout 25s adb exec-out screencap -p > artifacts/orbital-immersive-emulator.png &&
     test -s artifacts/orbital-immersive-emulator.png &&
     test "$(file -b --mime-type artifacts/orbital-immersive-emulator.png)" = image/png &&
     python3 - artifacts/orbital-immersive-emulator.png <<'PY'
import struct
import sys
path = sys.argv[1]
with open(path, "rb") as stream:
    header = stream.read(24)
    stream.seek(0, 2)
    length = stream.tell()
if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
    raise SystemExit("ADB screencap did not return a PNG header")
width, height = struct.unpack(">II", header[16:24])
if length < 4096 or width < 600 or height < 1000 or width >= height:
    raise SystemExit(f"ADB screencap is truncated/nonportrait: {width}x{height}, {length} bytes")
PY
  then
    captured=1
    break
  fi
  printf 'capture_attempt_%s=failed\n' "$capture_attempt" >> "$summary"
  timeout 8s adb devices -l >> "$summary" 2>&1 || true
  sleep 2
done
if [[ "$captured" != 1 ]]; then
  # exec-out may hang on the host graphics pipe while device-side screencap
  # still works. This is still a real, fresh Android framebuffer capture.
  printf 'capture_attempt=device_side_pull\n' >> "$summary"
  device_path=/sdcard/orbital-immersive-ci.png
  if timeout 25s adb shell screencap -p "$device_path" \
      > artifacts/immersive-device-capture.txt 2>&1 &&
     timeout 25s adb pull "$device_path" artifacts/orbital-immersive-emulator.png \
      >> artifacts/immersive-device-capture.txt 2>&1 &&
     test -s artifacts/orbital-immersive-emulator.png &&
     test "$(file -b --mime-type artifacts/orbital-immersive-emulator.png)" = image/png; then
    captured=1
    printf 'capture_method=real_device_screencap_adb_pull\n' >> "$summary"
  fi
  timeout 6s adb shell rm -f "$device_path" >/dev/null 2>&1 || true
fi
if [[ "$captured" != 1 ]]; then
  printf '%s\n' 'No valid live emulator screenshot after bounded ADB attempts.' >&2
  exit 1
fi
printf 'capture_attempt_success=%s\n' "$capture_attempt" >> "$summary"
python3 - artifacts/orbital-immersive-emulator.png artifacts/orbital-emulator.png <<'PY'
import hashlib
import struct
import sys
stage, normal = sys.argv[1:]
with open(stage,"rb") as f:
    h = f.read(24)
    f.seek(0,2)
    byte_count = f.tell()
    f.seek(-12,2)
    trailer = f.read(12)
if len(h)!=24 or h[:8]!=b"\x89PNG\r\n\x1a\n" or h[12:16]!=b"IHDR":
    raise SystemExit("Stage screenshot has no PNG IHDR")
if byte_count < 4096 or trailer != b"\x00\x00\x00\x00IEND\xaeB\x60\x82":
    raise SystemExit("Stage screenshot is truncated or lacks the PNG end marker")
w,h = struct.unpack(">II",h[16:24])
if w < 600 or h < 1000 or w >= h:
    raise SystemExit(f"Stage screenshot geometry is invalid: {w}x{h}")
def digest(path):
    with open(path,"rb") as f:
        return hashlib.sha256(f.read()).hexdigest()
if digest(stage) == digest(normal):
    raise SystemExit("Stage screenshot equals normal screen: no evidence stage entered")
print(f"Verified actual {w}x{h} stage PNG SHA256 {digest(stage)}")
PY
printf 'foreground=verified\nscreenshot_sha256=%s\n' \
  "$(sha256sum artifacts/orbital-immersive-emulator.png | cut -d' ' -f1)" >> "$summary"

printf 'exit-verify-fresh-accessibility\n' > "$phase"
# Do not reuse the Stage hierarchy captured before the real gesture. The
# tap must target the currently visible native Android accessibility bounds.
dump_ui pre-exit
verify_ui artifacts/immersive-pre-exit.xml stage
python3 - artifacts/immersive-stage.xml artifacts/immersive-pre-exit.xml \
  > artifacts/immersive-exit-bounds.txt <<'PY'
import re,sys,xml.etree.ElementTree as ET
label="Exit immersive performance"
for tag,path in zip(("old-stage", "fresh-pre-exit"),sys.argv[1:]):
    nodes=[n for n in ET.parse(path).iter("node") if n.attrib.get("content-desc")==label]
    if len(nodes)!=1:raise SystemExit(f"{tag}: expected exactly one EXIT, found {len(nodes)}")
    attrs=nodes[0].attrib
    match=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",attrs.get("bounds",""))
    if not match:raise SystemExit(f"{tag}: invalid bounds {attrs}")
    l,t,r,b=map(int,match.groups())
    if r<=l or b<=t:raise SystemExit(f"{tag}: empty bounds")
    print(f"{tag}: bounds=[{l},{t}][{r},{b}] center=({(l+r)//2},{(t+b)//2}) "
          f"clickable={attrs.get('clickable')} enabled={attrs.get('enabled')} package={attrs.get('package')}")
PY
printf 'exit-inject-fresh-android-tap\n' > "$phase"
timeout 12s adb logcat -d -t 1200 -v threadtime > artifacts/immersive-exit-before-logcat.txt 2>&1 || true
tap_action artifacts/immersive-pre-exit.xml 'Exit immersive performance'
timeout 12s adb shell dumpsys input > artifacts/immersive-exit-dumpsys-input.txt 2>&1 || true
timeout 12s adb shell dumpsys window > artifacts/immersive-exit-window-after-tap.txt 2>&1 || true

# Bounded genuine OS accessibility polling covers delayed input delivery,
# but NEVER interprets a still-visible EXIT as success.
printf 'exit-poll-return-to-sound\n' > "$phase"
exit_reached=0
for exit_attempt in 1 2 3 4 5; do
  sleep 1
  dump_ui "exit-return-$exit_attempt"
  returned_xml="artifacts/immersive-exit-return-$exit_attempt.xml"
  if verify_ui "$returned_xml" normal; then
    cp "$returned_xml" artifacts/immersive-returned.xml
    printf 'exit_verified_on_poll=%s\n' "$exit_attempt" >> "$summary"
    exit_reached=1
    break
  fi
  printf 'exit_poll_%s=still_not_normal_SOUND\n' "$exit_attempt" >> "$summary"
done
timeout 12s adb logcat -d -t 2800 -v threadtime > artifacts/immersive-exit-after-logcat.txt 2>&1 || true
if [[ "$exit_reached" != 1 ]]; then
  echo 'Real EXIT tap did not return to SOUND after five fresh UiAutomator polls.' >&2
  exit 1
fi
verify_ui artifacts/immersive-returned.xml normal
printf 'enter=PASS\nkeyboard_hidden=PASS\nactual_png=PASS\nexit=PASS\n' >> "$summary"
printf 'complete\n' > "$phase"
