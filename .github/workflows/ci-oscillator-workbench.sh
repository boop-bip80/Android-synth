#!/usr/bin/env bash
# Authentic API35 capture of ORBITAL's live multi-oscillator workbench.
# This proof uses real accessibility text, Android touch input and ADB screencap.
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

printf 'launch\n' > "$phase"
timeout 12s adb shell am force-stop com.orbital.synth >/dev/null 2>&1 || true
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/oscillator-activity-launch.txt
ready=0
for attempt in {1..70}; do
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
sleep 1

size_text="$(timeout 12s adb shell wm size | tr -d '\r')"
screen_xy="$(python3 - "$size_text" <<'PY'
import re
import sys
matches = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", sys.argv[1])
if not matches:
    raise SystemExit("Cannot read actual emulator screen dimensions")
w, h = map(int, matches[-1])
if w < 600 or h < 1000 or w >= h:
    raise SystemExit(f"Invalid portrait emulator dimensions {w}x{h}")
print(w, h)
PY
)"
read -r screen_w screen_h <<< "$screen_xy"
printf 'display=%sx%s\n' "$screen_w" "$screen_h" >> "$summary"

dump_ui() {
  local label="$1"
  local path="artifacts/oscillator-$label.xml"
  for retry in {1..4}; do
    if timeout 35s adb shell uiautomator dump /sdcard/orbital-oscillator-ui.xml > "artifacts/oscillator-$label-dump.txt" 2>&1 &&
       timeout 15s adb exec-out cat /sdcard/orbital-oscillator-ui.xml > "$path" &&
       [[ -s "$path" ]] &&
       python3 - "$path" <<'PY'
import sys
import xml.etree.ElementTree as ET
if not list(ET.parse(sys.argv[1]).iter("node")):
    raise SystemExit("No accessibility nodes")
PY
    then
      return 0
    fi
    sleep 2
  done
  echo "Cannot read oscillator editor accessibility state: $label" >&2
  return 1
}

# Check a real Android accessibility property, not a source-code assumption.
has_node() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys
import xml.etree.ElementTree as ET
path, field, wanted = sys.argv[1:]
nodes = list(ET.parse(path).iter("node"))
if not any(n.attrib.get(field, "").strip() == wanted for n in nodes):
    raise SystemExit(1)
PY
}

tap_text() {
  local pos
  pos="$(python3 - "$1" "$2" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
path, text = sys.argv[1:]
nodes = [n for n in ET.parse(path).iter("node") if n.attrib.get("text", "").strip() == text]
if len(nodes) != 1:
    raise SystemExit(f"Expected one visible '{text}' control, got {len(nodes)}")
m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", nodes[0].attrib.get("bounds",""))
if not m:
    raise SystemExit(f"Invalid accessible bounds for '{text}'")
left,top,right,bottom = map(int,m.groups())
if right <= left or bottom <= top:
    raise SystemExit(f"'{text}' control has empty touch bounds")
print((left+right)//2,(top+bottom)//2)
PY
)"
  read -r x y <<< "$pos"
  timeout 10s adb shell input tap "$x" "$y"
  printf 'tap=%s,x=%s,y=%s\n' "$2" "$x" "$y" >> "$summary"
}

scroll_sound_rack() {
  local direction="$1"
  local x=$((screen_w * 12 / 100))
  local start=$((screen_h * 56 / 100))
  local end=$((screen_h * 23 / 100))
  if [[ "$direction" == up ]]; then
    timeout 12s adb shell input swipe "$x" "$start" "$x" "$end" 380
  else
    timeout 12s adb shell input swipe "$x" "$end" "$x" "$start" 380
  fi
  sleep 0.45
}

printf 'find-empty-rack\n' > "$phase"
rack_found=0
for pass in {1..11}; do
  dump_ui "search-$pass"
  if has_node "artifacts/oscillator-search-$pass.xml" text '+ ADD OSCILLATOR'; then
    rack_found=1
    rack_hierarchy="artifacts/oscillator-search-$pass.xml"
    printf 'rack_scrolls=%s\n' "$((pass-1))" >> "$summary"
    break
  fi
  scroll_sound_rack down
done
if [[ "$rack_found" != 1 ]]; then
  echo 'Empty oscillator rack ADD control was not reachable on SOUND screen.' >&2
  exit 1
fi

printf 'activate-osc1\n' > "$phase"
tap_text "$rack_hierarchy" '+ ADD OSCILLATOR'
sleep 0.7
dump_ui activated
if ! has_node artifacts/oscillator-activated.xml content-desc 'Oscillator 1, active'; then
  echo 'OSC1 did not become active after tapping ADD; screenshot proof invalid.' >&2
  exit 1
fi
printf 'oscillator_1=active\n' >> "$summary"

printf 'find-waveform\n' > "$phase"
wave_found=0
for pass in {1..9}; do
  dump_ui "wave-$pass"
  if has_node "artifacts/oscillator-wave-$pass.xml" text 'SAW'; then
    wave_found=1
    wave_hierarchy="artifacts/oscillator-wave-$pass.xml"
    break
  fi
  scroll_sound_rack down
done
if [[ "$wave_found" != 1 ]]; then
  echo 'Activated oscillator waveform chooser was not reachable.' >&2
  exit 1
fi
tap_text "$wave_hierarchy" 'SAW'
sleep 0.5
dump_ui configured
has_node artifacts/oscillator-configured.xml text 'SAW' || {
  echo 'Waveform chooser disappeared before screenshot.' >&2
  exit 1
}
printf 'waveform_tap=SAW\n' >> "$summary"

printf 'capture\n' > "$phase"
captured=0
for capture_attempt in 1 2; do
  if timeout 25s adb exec-out screencap -p > artifacts/orbital-oscillator-workbench.png &&
     test -s artifacts/orbital-oscillator-workbench.png &&
     test "$(file -b --mime-type artifacts/orbital-oscillator-workbench.png)" = image/png &&
     python3 - artifacts/orbital-oscillator-workbench.png "$screen_w" "$screen_h" <<'PY'
import struct
import sys
path,wanted_w,wanted_h = sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
with open(path,"rb") as image:
    header = image.read(24)
    image.seek(0,2)
    length = image.tell()
if len(header)!=24 or header[:8]!=b"\x89PNG\r\n\x1a\n" or header[12:16]!=b"IHDR":
    raise SystemExit("Invalid ADB PNG header")
w,h = struct.unpack(">II",header[16:24])
if (w,h)!=(wanted_w,wanted_h) or length < 4096:
    raise SystemExit(f"Invalid workbench screenshot dimensions/bytes: {w}x{h}, {length}")
PY
  then
    captured=1
    break
  fi
  printf 'capture_attempt_%s=failed\n' "$capture_attempt" >> "$summary"
  timeout 8s adb devices -l >> "$summary" 2>&1 || true
  sleep 2
done
[[ "$captured" == 1 ]] || { echo 'No authentic workbench screenshot after two bounded ADB attempts.' >&2; exit 1; }

python3 - artifacts/orbital-oscillator-workbench.png artifacts/orbital-emulator.png <<'PY'
import hashlib
import sys
def digest(path):
    with open(path,'rb') as image:
        return hashlib.sha256(image.read()).hexdigest()
if digest(sys.argv[1]) == digest(sys.argv[2]):
    raise SystemExit("Workbench capture identical to default screenshot")
print(f"Verified actual oscillator workbench screenshot SHA256={digest(sys.argv[1])}")
PY
printf 'capture_attempt_success=%s\nscreenshot_sha256=%s\n' "$capture_attempt" \
  "$(sha256sum artifacts/orbital-oscillator-workbench.png | cut -d' ' -f1)" >> "$summary"
printf 'complete\n' > "$phase"
