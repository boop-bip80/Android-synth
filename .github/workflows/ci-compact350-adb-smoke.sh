#!/usr/bin/env bash
# Independent real-Android compact350 proof: genuine app, resized display, ADB finger input.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/compact350-adb-phase.txt
summary=artifacts/compact350-adb-summary.txt
printf 'source_sha=%s\nrun_id=%s\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" > "$summary"
finish() {
  local code=$?
  trap - EXIT
  set +e
  printf 'exit=%s\nphase=%s\n' "$code" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  timeout 12s adb shell dumpsys window > artifacts/compact350-adb-window.txt 2>&1
  timeout 12s adb logcat -d -t 2200 -v threadtime > artifacts/compact350-adb-logcat.txt 2>&1
  timeout 8s adb devices -l > artifacts/compact350-adb-devices.txt 2>&1
  exit "$code"
}
trap finish EXIT
printf 'resize\n' > "$phase"
apk=app/build/outputs/apk/debug/app-debug.apk
[[ -s "$apk" ]] || { echo 'Missing exact APK' >&2; exit 1; }
printf 'apk_sha256=%s\n' "$(sha256sum "$apk" | cut -d' ' -f1)" >> "$summary"
timeout 12s adb shell wm size 1080x1050
timeout 12s adb shell wm density 480
timeout 12s adb shell settings put system accelerometer_rotation 0
timeout 12s adb shell settings put system user_rotation 0
timeout 12s adb shell wm size > artifacts/compact350-adb-size.txt
timeout 12s adb shell wm density > artifacts/compact350-adb-density.txt
grep -qF 'Override size: 1080x1050' artifacts/compact350-adb-size.txt
grep -qF 'Override density: 480' artifacts/compact350-adb-density.txt
printf 'launch\n' > "$phase"
timeout 50s adb install -r "$apk"
timeout 12s adb shell am force-stop com.orbital.synth
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/compact350-adb-launch.txt
for i in {1..35}; do
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1; then break; fi
  sleep 1
done
sleep 2

dump_ui() {
  local stem="$1"
  local dest="artifacts/compact350-adb-$stem.xml"
  for attempt in 1 2 3; do
    if timeout 30s adb shell uiautomator dump /sdcard/compact350-forensic.xml > "artifacts/compact350-adb-$stem-dump.txt" 2>&1 &&
       timeout 12s adb exec-out cat /sdcard/compact350-forensic.xml > "$dest" &&
       python3 - "$dest" <<'PY'
import sys,xml.etree.ElementTree as ET
nodes=list(ET.parse(sys.argv[1]).iter("node"))
assert nodes and any(n.attrib.get("package")=="com.orbital.synth" for n in nodes), "No live ORBITAL nodes; possible Android ANR"
PY
    then return 0; fi
    sleep 1
  done
  echo "Real ORBITAL accessibility dump unavailable for $stem" >&2
  return 1
}
has_desc() {
  python3 - "$1" "$2" <<'PY'
import sys,xml.etree.ElementTree as ET
path,needle=sys.argv[1:]
found=any(needle in n.attrib.get("content-desc","") for n in ET.parse(path).iter("node"))
raise SystemExit(0 if found else 1)
PY
}
node_info() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys,re,xml.etree.ElementTree as ET
path,needle,field=sys.argv[1:]
nodes=[n for n in ET.parse(path).iter("node") if needle in n.attrib.get("content-desc","")]
if len(nodes)!=1: raise SystemExit(f"Expected one accessible {needle}, got {len(nodes)}")
n=nodes[0]
if field=="selected":
    if n.attrib.get("selected")!="true": raise SystemExit("Control not selected: "+str(n.attrib))
else:
    m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n.attrib.get("bounds",""))
    if not m: raise SystemExit("Bad accessibility bounds")
    left,top,right,bottom=map(int,m.groups())
    if bottom<=top or right<=left: raise SystemExit("Empty accessibility bounds")
    if field=="tap": print((left+right)//2,(top+bottom)//2)
    if field=="y": print((top+bottom)//2)
PY
}
tap_desc() {
  local xy x y
  xy="$(node_info "$1" "$2" tap)"
  read -r x y <<< "$xy"
  timeout 12s adb shell input tap "$x" "$y"
}
assert_piano() {
  has_desc "$1" 'Multitouch piano keyboard' || {
    echo "Anchored piano absent in real display: $1" >&2; return 1;
  }
}
swipe_up() { timeout 12s adb shell input swipe 525 705 525 335 380; }
swipe_down() { timeout 12s adb shell input swipe 525 335 525 705 380; }
swipe_left() { timeout 12s adb shell input swipe 830 "$1" 230 "$1" 380; }
capture_real_png() {
  timeout 25s adb exec-out screencap -p > "artifacts/compact350-adb-$1.png"
  python3 - "artifacts/compact350-adb-$1.png" <<'PY'
import sys,struct
with open(sys.argv[1],"rb") as f: h=f.read(24)
assert len(h)==24 and h[:8]==b"\x89PNG\r\n\x1a\n" and h[12:16]==b"IHDR"
wh=struct.unpack(">II",h[16:24])
assert wh==(1080,1050),f"Wrong actual framebuffer geometry {wh}"
PY
}
seek_vertical() {
  local prefix="$1" needle="$2" direction="$3" max="$4"
  local attempt
  for ((attempt=1;attempt<=max;attempt++)); do
    dump_ui "$prefix-$attempt"
    if has_desc "artifacts/compact350-adb-$prefix-$attempt.xml" "$needle"; then
      printf '%s\n' "artifacts/compact350-adb-$prefix-$attempt.xml"
      return 0
    fi
    if [[ "$direction" == up ]]; then swipe_up; else swipe_down; fi
  done
  echo "Unreachable control via real $direction swipe: $needle" >&2
  return 1
}
seek_horizontal() {
  local prefix="$1" needle="$2" y="$3" max="$4"
  local attempt
  for ((attempt=1;attempt<=max;attempt++)); do
    dump_ui "$prefix-$attempt"
    if has_desc "artifacts/compact350-adb-$prefix-$attempt.xml" "$needle"; then
      printf '%s\n' "artifacts/compact350-adb-$prefix-$attempt.xml"
      return 0
    fi
    swipe_left "$y"
  done
  echo "Unreachable control via real horizontal swipe: $needle" >&2
  return 1
}

printf 'baseline\n' > "$phase"
dump_ui baseline
assert_piano artifacts/compact350-adb-baseline.xml
has_desc artifacts/compact350-adb-baseline.xml 'Show eight-layer oscillator editor'
capture_real_png baseline
printf 'baseline_piano_and_osc=PASS\n' >> "$summary"

printf 'osc-expand\n' > "$phase"
tap_desc artifacts/compact350-adb-baseline.xml 'Show eight-layer oscillator editor'
dump_ui osc-open
has_desc artifacts/compact350-adb-osc-open.xml 'Hide eight-layer oscillator editor'
printf 'osc_expansion=PASS\n' >> "$summary"
printf 'osc-eight\n' > "$phase"
row="$(seek_vertical osc-row 'Oscillator 1,' up 6)"
y="$(node_info "$row" 'Oscillator 1,' y)"
eighth="$(seek_horizontal osc-eight 'Oscillator 8,' "$y" 7)"
tap_desc "$eighth" 'Oscillator 8,'
dump_ui osc-selected
node_info artifacts/compact350-adb-osc-selected.xml 'Oscillator 8,' selected
assert_piano artifacts/compact350-adb-osc-selected.xml
capture_real_png osc-selected
printf 'oscillator_8_selected=PASS\n' >> "$summary"

printf 'engine-nav\n' > "$phase"
toggle="$(seek_vertical osc-return 'Hide eight-layer oscillator editor' down 10)"
tap_desc "$toggle" 'Hide eight-layer oscillator editor'
engine="$(seek_vertical engine-nav 'Sound rack ENGINE' up 9)"
tap_desc "$engine" 'Sound rack ENGINE'
first="$(seek_vertical engine-first 'Select synthesis architecture ORBITAL WT' up 5)"
y="$(node_info "$first" 'Select synthesis architecture ORBITAL WT' y)"
printf 'engine-options\n' > "$phase"
for architecture in 'ORBITAL WT' LADDER SEM 'SIX-OP FM' 'PHASE DIST.'; do
  slug="$(printf '%s' "$architecture" | tr ' ' '_')"
  row="$(seek_horizontal "engine-$slug" "Select synthesis architecture $architecture" "$y" 6)"
  tap_desc "$row" "Select synthesis architecture $architecture"
  dump_ui "engine-selected-$slug"
  node_info "artifacts/compact350-adb-engine-selected-$slug.xml" "Select synthesis architecture $architecture" selected
  printf 'selected_engine=%s\n' "$architecture" >> "$summary"
done
dump_ui completed
assert_piano artifacts/compact350-adb-completed.xml
capture_real_png completed
printf 'all_five_engines_piano_and_osc8=PASS\n' >> "$summary"
printf 'complete\n' > "$phase"
