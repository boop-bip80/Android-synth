#!/usr/bin/env bash
# Real short-display end-to-end verification, independent of Compose test-rule
# semantics queries. The two required cases are SOUND keyboard visibility and
# FX -> SOUND navigation retaining a fully onscreen, usable piano keyboard.
set -euo pipefail

mkdir -p artifacts
stage_file=artifacts/compact-e2e-phase.txt
summary_file=artifacts/compact-e2e-summary.txt
printf '%s\n' 'boot' > "$stage_file"
printf '%s\n' 'suite=real-compact-geometry\nrequired_cases=2\n' > "$summary_file"

compact_display_changed=0
finish_compact_e2e() {
  local result=$?
  trap - EXIT
  set +e
  printf '\n=== Compact real-device geometry result: %s ===\n' "$result"
  printf 'stage=%s\nexit_status=%s\n' "$(cat "$stage_file" 2>/dev/null)" "$result" | tee -a "$summary_file"
  timeout 12s adb logcat -d -t 1200 -v threadtime > artifacts/compact-350-logcat.txt 2>&1
  if [[ "$result" != 0 ]]; then
    printf '%s\n' 'Last compact emulator logcat:' 
    tail -n 80 artifacts/compact-350-logcat.txt 2>/dev/null || true
    printf '%s\n' 'ADB devices:'
    timeout 8s adb devices -l || true
  fi
  if [[ "$compact_display_changed" == 1 ]]; then
    timeout 10s adb shell wm size reset >/dev/null 2>&1 || true
  fi
  exit "$result"
}
trap finish_compact_e2e EXIT

for attempt in {1..40}; do
  if timeout 8s adb shell pm list packages com.android.settings 2>/dev/null | grep -q 'package:com.android.settings'; then
    break
  fi
  sleep 2
done
printf '%s\n' 'build-install' > "$stage_file"
timeout 15m gradle :app:assembleDebug --no-daemon --console=plain
timeout 120s adb install -r app/build/outputs/apk/debug/app-debug.apk

raw_density="$(timeout 10s adb shell wm density | tr -d '\r' | grep -Eo '[0-9]+' | tail -n 1)"
if [[ ! "$raw_density" =~ ^[0-9]+$ ]] || (( raw_density < 120 || raw_density > 1000 )); then
  printf 'Invalid display density: %s\n' "$raw_density" >&2
  exit 1
fi
compact_width_px=$((360 * raw_density / 160))
compact_height_px=$((350 * raw_density / 160))
printf 'density=%s\nwidth_px=%s\nheight_px=%s\n' "$raw_density" "$compact_width_px" "$compact_height_px" | tee -a "$summary_file"

printf '%s\n' 'physical-350dp-display' > "$stage_file"
compact_display_changed=1
timeout 10s adb shell wm size "${compact_width_px}x${compact_height_px}"
sleep 2
timeout 15s adb shell am force-stop com.orbital.synth >/dev/null 2>&1 || true
timeout 25s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/compact-350-launch.txt
# A fresh Google APIs emulator can show an *external* Pixel Launcher ANR
# above a healthy ORBITAL Activity. Recover only that exact named system app;
# never ignore, dismiss or relaunch through an ORBITAL ANR or any other dialog.
launcher_anr_overlay_visible() {
  local snapshot="$1"
  grep -Eq 'mCurrentFocus=.*Application Not Responding: com[.]google[.]android[.]apps[.]nexuslauncher(}|$)' "$snapshot"
}

recover_launcher_anr_overlay() {
  local attempt="$1"
  timeout 12s adb shell dumpsys window > "artifacts/compact-350-launcher-anr-before-$attempt.txt" 2>&1 || true
  if (( attempt == 0 )); then
    printf 'attempt=%s action=back_external_pixel_launcher_anr\n' "$attempt" \
      >> artifacts/compact-350-launcher-recovery.txt
    timeout 10s adb shell input keyevent KEYCODE_BACK || true
  else
    printf 'attempt=%s action=force_stop_only_pixel_launcher_then_relaunch_orbital\n' "$attempt" \
      >> artifacts/compact-350-launcher-recovery.txt
    timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher || true
    timeout 25s adb shell am start -n com.orbital.synth/.MainActivity \
      > artifacts/compact-350-launcher-relaunch.txt
  fi
}

ready=0
launcher_anr_recoveries=0
for attempt in {1..60}; do
  timeout 12s adb shell dumpsys window > artifacts/compact-350-window.txt 2>&1 || true
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/compact-350-window.txt; then
    ready=1
    break
  fi
  if (( launcher_anr_recoveries < 2 )) &&
     launcher_anr_overlay_visible artifacts/compact-350-window.txt; then
    recover_launcher_anr_overlay "$launcher_anr_recoveries"
    launcher_anr_recoveries=$((launcher_anr_recoveries + 1))
    sleep 2
  fi
  sleep 1
done
printf 'launcher_anr_recoveries=%s\nfocus_ready_poll=%s\n' \
  "$launcher_anr_recoveries" "$attempt" >> "$summary_file"
if [[ "$ready" != 1 ]]; then
  timeout 15s adb shell dumpsys activity activities > artifacts/compact-350-focus-activity.txt 2>&1 || true
  timeout 15s adb shell dumpsys window > artifacts/compact-350-focus-failure.txt 2>&1 || true
  timeout 8s adb shell pidof com.orbital.synth > artifacts/compact-350-focus-pid.txt 2>&1 || true
  printf '%s\n' 'MainActivity did not become focused at 360x350dp display.' >&2
  printf '%s\n' 'Latest focus state:' >&2
  grep -m 2 -E 'mCurrentFocus|mFocusedApp' artifacts/compact-350-focus-failure.txt >&2 || true
  exit 1
fi
sleep 2

dump_hierarchy_and_screenshot() {
  local label="$1"
  printf 'dump-%s\n' "$label" > "$stage_file"
  timeout 35s adb shell uiautomator dump /sdcard/orbital-350-hierarchy.xml > "artifacts/compact-350-${label}-dump.txt" 2>&1
  timeout 15s adb exec-out cat /sdcard/orbital-350-hierarchy.xml > "artifacts/compact-350-${label}.xml"
  test -s "artifacts/compact-350-${label}.xml"
  timeout 15s adb exec-out screencap -p > "artifacts/compact-350-${label}.png"
  test -s "artifacts/compact-350-${label}.png"
  python3 - "artifacts/compact-350-${label}.png" "$compact_width_px" "$compact_height_px" <<'PY'
import struct
import sys
path, width, height = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
with open(path, "rb") as stream:
    header = stream.read(24)
if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
    raise SystemExit(f"{path}: invalid screenshot PNG")
actual = struct.unpack(">II", header[16:24])
if actual != (width, height):
    raise SystemExit(f"{path}: screenshot geometry {actual}, expected {(width, height)}")
print(f"Screenshot {path}: verified {width}x{height}")
PY
}

validate_keyboard_and_tab() {
  local label="$1"
  local active_tab="$2"
  python3 - "artifacts/compact-350-${label}.xml" "$active_tab" "$compact_width_px" "$compact_height_px" "$raw_density" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, active, width, height, density = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
tree = ET.parse(path)
nodes = list(tree.iter("node"))
def bounds(node):
    text = node.attrib.get("bounds", "")
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", text)
    if not match:
        raise AssertionError(f"Missing accessibility bounds on {node.attrib}")
    return tuple(int(v) for v in match.groups())

keys = [n for n in nodes if "multitouch piano keyboard" in n.attrib.get("content-desc", "").lower()]
if len(keys) != 1:
    raise AssertionError(f"Expected one accessible keyboard, got {len(keys)}; descriptions={[n.attrib.get('content-desc') for n in nodes if n.attrib.get('content-desc')]}")
keyboard = bounds(keys[0])
left, top, right, bottom = keyboard
if left < 0 or top < 0 or right > width or bottom > height or right <= left or bottom <= top:
    raise AssertionError(f"{active}: keyboard outside real screen {keyboard}, {width}x{height}")
min_px = 80 * density / 160
if bottom - top < min_px - 1:
    raise AssertionError(f"{active}: visible keyboard height {bottom-top}px < usable 80dp ({min_px:.1f}px)")
tab_nodes = [n for n in nodes if n.attrib.get("content-desc") == f"Tab {active}"]
# Android Compose intentionally exposes an ALREADY selected Role.Tab as
# non-clickable, while inactive tabs publish ACTION_CLICK. Preserve the true
# selection assertion here and require clickability when tapping an inactive tab.
if len(tab_nodes) != 1 or tab_nodes[0].attrib.get("selected") != "true":
    raise AssertionError(f"{active}: active tab must expose selected=true; matches={[n.attrib for n in tab_nodes]}")
print(f"PASS {active}: keyboard {keyboard}, screen={width}x{height}, min80dp={min_px:.1f}px, selected tab")
PY
}

tap_accessible_tab() {
  local label="$1"
  local next_tab="$2"
  local hierarchy="artifacts/compact-350-$label.xml"
  local attempt xy tap_x tap_y

  # Emulator windows occasionally drop a single adb tap despite a clickable
  # accessible target. After every physical attempt, re-read REAL UIAutomator
  # bounds and selected state; an input exit code never counts as success.
  # The strict final selected=true and keyboard-geometry assertions remain.
  for attempt in 1 2 3; do
    xy="$(python3 - "$hierarchy" "$next_tab" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
path, name = sys.argv[1], sys.argv[2]
nodes = [n for n in ET.parse(path).iter("node")
         if n.attrib.get("content-desc") == f"Tab {name}"]
if len(nodes) != 1:
    raise SystemExit(f"Cannot find exactly one Tab {name}: {len(nodes)}")
node = nodes[0]
if node.attrib.get("selected") == "true":
    print("ALREADY_SELECTED")
elif node.attrib.get("selected") == "false" and node.attrib.get("clickable") == "true":
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        raise SystemExit(f"Invalid Tab {name} bounds")
    left, top, right, bottom = (int(v) for v in match.groups())
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
else:
    raise SystemExit(f"Inactive Tab {name} must expose selected=false AND clickable=true: {node.attrib}")
PY
)"
    if [[ "$xy" == "ALREADY_SELECTED" ]]; then
      printf 'Tab %s selected after %s physical tap attempt(s)\n' "$next_tab" "$((attempt - 1))"
      return 0
    fi
    read -r tap_x tap_y <<< "$xy"
    printf 'Tap %s at (%s,%s), attempt %s/3\n' "$next_tab" "$tap_x" "$tap_y" "$attempt"
    timeout 10s adb shell input tap "$tap_x" "$tap_y"
    sleep 2
    # Preserve each post-tap hierarchy as evidence; retry only when the
    # accessible state STILL says the click was not delivered.
    printf 'tap-%s-%s\n' "$next_tab" "$attempt" > "$stage_file"
    timeout 35s adb shell uiautomator dump /sdcard/orbital-350-hierarchy.xml \
      > "artifacts/compact-350-$label-$next_tab-tap-$attempt-dump.txt" 2>&1
    hierarchy="artifacts/compact-350-$label-$next_tab-tap-$attempt.xml"
    timeout 15s adb exec-out cat /sdcard/orbital-350-hierarchy.xml > "$hierarchy"
    test -s "$hierarchy"
  done
  # A failed final state is still photographed, then rejected by the original
  # strict check. Never silently pass a lost tap.
  printf 'Tab %s unconfirmed after three real touch attempts; recording screenshot before strict failure\n' "$next_tab" >&2
}

# E2E case 1: initial SOUND keyboard remains actually visible at 360x350dp.
dump_hierarchy_and_screenshot sound
validate_keyboard_and_tab sound SOUND
printf '%s\n' 'case1_sound_keyboard=PASS' | tee -a "$summary_file"

# E2E case 2: real navigation FX and back to SOUND preserves the same
# strict usable 80dp keyboard while publishing the selected tab semantics.
tap_accessible_tab sound FX
dump_hierarchy_and_screenshot fx
validate_keyboard_and_tab fx FX
tap_accessible_tab fx SOUND
dump_hierarchy_and_screenshot sound-return
validate_keyboard_and_tab sound-return SOUND
printf '%s\n' 'case2_fx_sound_keyboard=PASS' | tee -a "$summary_file"
printf '%s\n' 'complete' > "$stage_file"
