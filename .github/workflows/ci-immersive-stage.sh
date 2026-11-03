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
keyboard = sum("multitouch piano keyboard" in n.lower() for n in names)
enter = names.count("Enter immersive performance")
exit = names.count("Exit immersive performance")
if mode == "normal":
    if keyboard != 1 or enter != 1 or exit:
        raise SystemExit(f"Normal screen must have keyboard+enter, no exit: {keyboard}/{enter}/{exit}")
elif mode == "stage":
    if keyboard or enter or exit != 1:
        raise SystemExit(f"Stage must hide keyboard+enter and expose exit: {keyboard}/{enter}/{exit}")
else:
    raise SystemExit(f"Unknown screen {mode}")
print(f"PASS {mode}: keyboard={keyboard} enter={enter} exit={exit}")
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
  timeout 10s adb shell input tap "$x" "$y"
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
gesture_pid=
if [[ -n "$gesture" ]]; then
  read -r x1 y1 x2 y2 <<< "$gesture"
  # Screenshot WHILE a finger is held; otherwise the effect fades to its idle glow.
  timeout 10s adb shell input swipe "$x1" "$y1" "$x2" "$y2" 1800 > artifacts/immersive-gesture.txt 2>&1 &
  gesture_pid=$!
  sleep 0.35
  printf 'gesture=live_accessible_canvas_swipe\n' >> "$summary"
else
  printf 'gesture=skipped_canvas_semantics_merged\n' >> "$summary"
fi

printf 'capture\n' > "$phase"
timeout 15s adb exec-out screencap -p > artifacts/orbital-immersive-emulator.png
if [[ -n "$gesture_pid" ]]; then
  wait "$gesture_pid"
fi
test -s artifacts/orbital-immersive-emulator.png
test "$(file -b --mime-type artifacts/orbital-immersive-emulator.png)" = image/png
python3 - artifacts/orbital-immersive-emulator.png artifacts/orbital-emulator.png <<'PY'
import hashlib
import struct
import sys
stage, normal = sys.argv[1:]
with open(stage,"rb") as f:
    h = f.read(24)
if len(h)!=24 or h[:8]!=b"\x89PNG\r\n\x1a\n" or h[12:16]!=b"IHDR":
    raise SystemExit("Stage screenshot has no PNG IHDR")
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

printf 'exit\n' > "$phase"
tap_action artifacts/immersive-stage.xml 'Exit immersive performance'
sleep 2
dump_ui returned
verify_ui artifacts/immersive-returned.xml normal
printf 'enter=PASS\nkeyboard_hidden=PASS\nactual_png=PASS\nexit=PASS\n' >> "$summary"
printf 'complete\n' > "$phase"
