#!/usr/bin/env bash
# Fresh API35 emulator preparation for real production screenshot proof.
# Stage and visuals run on independent clean devices so heavyweight screenshot
# capture never follows the complete 59+ test instrumentation suite.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/focus-bootstrap-phase.txt
summary=artifacts/focus-bootstrap-summary.txt
printf 'start\n' > "$phase"
printf 'run=%s\ncommit=%s\nsuite=%s\n' "$GITHUB_RUN_ID" "$GITHUB_SHA" "$ORBITAL_TEST_SUITE" > "$summary"
finish() {
  local status=$?
  trap - EXIT
  set +e
  printf 'exit_status=%s\nphase=%s\n' "$status" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  if [[ "$status" != 0 ]]; then
    timeout 10s adb devices -l > artifacts/focus-bootstrap-adb.txt 2>&1
    timeout 12s adb logcat -d -t 900 -v threadtime > artifacts/focus-bootstrap-logcat.txt 2>&1
    # Artifacts are not always reachable from the review tooling, so also put a
    # short, bounded digest in the job log itself. Purely diagnostic.
    printf '\n=== focus bootstrap failure digest (phase=%s) ===\n' "$(cat "$phase" 2>/dev/null)"
    tail -n 24 "$summary" 2>/dev/null | cut -c 1-260
    printf -- '--- crash / ANR / app lines from logcat ---\n'
    grep -E 'FATAL EXCEPTION|AndroidRuntime|ANR in|Application Not Responding|has died|am_crash|am_anr|Force finishing|Splash|StrictMode|OrbitalAurora|com[.]orbital[.]synth' \
      artifacts/focus-bootstrap-logcat.txt 2>/dev/null | tail -n 45 | cut -c 1-260
    printf -- '--- last accessibility dump (what the UI exposed) ---\n'
    if [[ -s artifacts/focus-bootstrap-normal.xml ]]; then
      python3 - artifacts/focus-bootstrap-normal.xml <<'PYDIG' 2>&1 | head -n 20 | cut -c1-200
import sys
import xml.etree.ElementTree as ET
try:
    nodes = list(ET.parse(sys.argv[1]).iter("node"))
except Exception as problem:
    print("unreadable dump:", problem)
    raise SystemExit(0)
labels = [n.attrib.get("content-desc") or n.attrib.get("text") or "" for n in nodes]
labels = [l for l in labels if l]
packages = sorted({n.attrib.get("package", "?") for n in nodes})
print("nodes=%d labelled=%d packages=%s" % (len(nodes), len(labels), packages))
for label in labels[:12]:
    print("  -", label)
PYDIG
    else
      printf 'no accessibility dump was captured\n'
    fi
    printf -- '--- focus / process state ---\n'
    timeout 8s adb shell pidof com.orbital.synth 2>&1 | head -n 2
    grep -E 'mCurrentFocus|mFocusedApp|mTopFullscreenOpaqueWindowState' \
      artifacts/focus-bootstrap-window.txt 2>/dev/null | head -n 6 | cut -c 1-260
  fi
  exit "$status"
}
trap finish EXIT

# Prints "x y" (centre of the Wait button) ONLY when the given uiautomator dump shows
# the emulator's *system* process ANR dialog ("... system isn't responding") together
# with a Wait button. An ANR of ORBITAL itself ("ORBITAL isn't responding") prints
# nothing, so it is never papered over.
system_anr_wait_xy() {
  python3 - "$1" <<'PYW' 2>/dev/null || true
import re
import sys
import xml.etree.ElementTree as ET
try:
    nodes = list(ET.parse(sys.argv[1]).iter("node"))
except Exception:
    raise SystemExit(0)
def norm(value):
    return value.replace("\u2019", "'").lower()
if not any("system isn't responding" in norm(n.attrib.get("text", "") + n.attrib.get("content-desc", ""))
           for n in nodes):
    raise SystemExit(0)
for node in nodes:
    if node.attrib.get("text", "").strip().lower() == "wait":
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            left, top, right, bottom = map(int, m.groups())
            print((left + right) // 2, (top + bottom) // 2)
            break
PYW
}

# Presses Wait on the emulator's system-process ANR dialog, but ONLY while ORBITAL's
# MainActivity is the focused app and the top fullscreen window and the process is alive,
# i.e. the app is healthy and an emulator dialog is merely covering it. Returns 0 if it
# tapped, 1 if there was nothing (or nothing safe) to recover.
recover_system_anr_if_present() {
  timeout 12s adb shell dumpsys window > artifacts/focus-bootstrap-window.txt 2>&1 || true
  grep -Eq 'mCurrentFocus=.*Application Not Responding: system([}]|$)' artifacts/focus-bootstrap-window.txt || return 1
  grep -Eq 'mFocusedApp=.*com[.]orbital[.]synth/[.]MainActivity' artifacts/focus-bootstrap-window.txt || return 1
  grep -Eq 'mTopFullscreenOpaqueWindowState=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt || return 1
  timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 || return 1
  local xy="" size width height
  if timeout 35s adb shell uiautomator dump /sdcard/orbital-system-anr.xml \
       > artifacts/focus-bootstrap-anr-dump.txt 2>&1 &&
     timeout 15s adb exec-out cat /sdcard/orbital-system-anr.xml \
       > artifacts/focus-bootstrap-anr.xml 2>/dev/null; then
    xy="$(system_anr_wait_xy artifacts/focus-bootstrap-anr.xml)"
  fi
  if [[ -z "$xy" ]]; then
    # Fall back to the dialog's observed position (centre-x, ~58% of the height).
    size="$(timeout 8s adb shell wm size 2>/dev/null | tr -d '\r' | grep -Eo '[0-9]+x[0-9]+' | tail -n 1)"
    width="${size%x*}"
    height="${size#*x}"
    [[ "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ ]] || return 1
    xy="$((width / 2)) $((height * 58 / 100))"
  fi
  printf 'focus_gate_system_anr_wait_tap=%s\n' "$xy" >> "$summary"
  timeout 10s adb shell input tap $xy || true
  sleep 2
  return 0
}

# Succeeds only when ORBITAL's MainActivity really is the focused window. If the only
# thing in the way is that emulator system dialog (over a healthy ORBITAL), press Wait
# and look again; anything else fails closed. Pass "quiet" to suppress the message.
require_orbital_focus() {
  local message="$1" mode="${2:-loud}" attempt
  for attempt in 1 2 3 4 5 6; do
    timeout 12s adb shell dumpsys window > artifacts/focus-bootstrap-window.txt 2>&1 || true
    if grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt; then
      return 0
    fi
    recover_system_anr_if_present || break
  done
  [[ "$mode" == quiet ]] || echo "$message" >&2
  return 1
}

# Host JVM/lint/APK build has already passed and uploaded both binaries.
# Never run Gradle compilation while QEMU owns this runner's RAM: recent API35
# runs repeatedly lost emulator-5554 just after duplicate APK assembly.
printf 'validate-previous-host-job-apk-artifacts\n' > "$phase"
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$app_apk" && -s "$test_apk" ]] || {
  echo 'Missing nonempty host-job APK artifacts: refuse to rebuild inside live emulator.' >&2
  exit 1
}
printf 'apk_origin=needs.build/actions.download-artifact@v4\napp_apk_sha256=%s\nandroid_test_apk_sha256=%s\n' \
  "$(sha256sum "$app_apk" | cut -d' ' -f1)" \
  "$(sha256sum "$test_apk" | cut -d' ' -f1)" >> "$summary"
# Diagnostics are best-effort and must not block a genuinely passing capture.
free -m > artifacts/focus-bootstrap-host-memory.txt 2>&1 || true
ps -eo pid,comm,rss --sort=-rss > artifacts/focus-bootstrap-host-processes.txt 2>&1 || true

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
launcher_anr_recoveries=0
system_anr_recoveries=0
for attempt in {1..70}; do
  timeout 10s adb shell dumpsys window > artifacts/focus-bootstrap-window.txt 2>&1 || true
  if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt &&
     ! grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' artifacts/focus-bootstrap-window.txt; then
    ready=1
    break
  fi
  # A fresh API35 Pixel Launcher can ANR while the real ORBITAL surface is
  # drawn. Recover ONLY this precise external, focused OS dialog. Other ANRs,
  # consent prompts and splash screens still fail the unchanged focus gate.
  if (( launcher_anr_recoveries < 2 )) &&
     grep -Eq 'mCurrentFocus=.*Application Not Responding: com[.]google[.]android[.]apps[.]nexuslauncher([}]|$)' artifacts/focus-bootstrap-window.txt; then
    if (( launcher_anr_recoveries == 0 )); then
      printf 'recovery=external_pixel_launcher_anr_back\n' >> "$summary"
      timeout 10s adb shell input keyevent KEYCODE_BACK || true
    else
      printf 'recovery=external_pixel_launcher_anr_force_stop\n' >> "$summary"
      timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher || true
      timeout 30s adb shell am start -n com.orbital.synth/.MainActivity \
        >> artifacts/focus-bootstrap-launch.txt 2>&1 || true
    fi
    launcher_anr_recoveries=$((launcher_anr_recoveries + 1))
    sleep 2
  fi
  # On a slow 2-core runner the emulator's own *system* process can raise an ANR
  # dialog over an otherwise healthy, drawn ORBITAL window (seen with ORBITAL as the
  # focused app and its MainActivity as the top fullscreen window, process alive).
  # Accept that ONLY under exactly those conditions, then press the dialog's "Wait".
  # An ANR of ORBITAL itself, a crash, a splash screen or any other dialog still
  # fails the unchanged focus gate below.
  if (( system_anr_recoveries < 3 )) &&
     grep -Eq 'mCurrentFocus=.*Application Not Responding: system([}]|$)' artifacts/focus-bootstrap-window.txt &&
     grep -Eq 'mFocusedApp=.*com[.]orbital[.]synth/[.]MainActivity' artifacts/focus-bootstrap-window.txt &&
     grep -Eq 'mTopFullscreenOpaqueWindowState=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt &&
     timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1; then
    system_anr_recoveries=$((system_anr_recoveries + 1))
    printf 'recovery=emulator_system_anr_wait_%s\n' "$system_anr_recoveries" >> "$summary"
    wait_xy=""
    if timeout 35s adb shell uiautomator dump /sdcard/orbital-system-anr.xml \
         > artifacts/focus-bootstrap-anr-dump.txt 2>&1 &&
       timeout 15s adb exec-out cat /sdcard/orbital-system-anr.xml \
         > artifacts/focus-bootstrap-anr.xml 2>/dev/null; then
      wait_xy="$(python3 - artifacts/focus-bootstrap-anr.xml <<'PY' 2>/dev/null || true
import re
import sys
import xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter("node"):
    if node.attrib.get("text", "").strip().lower() == "wait":
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            left, top, right, bottom = map(int, m.groups())
            print((left + right) // 2, (top + bottom) // 2)
            break
PY
)"
    fi
    if [[ -n "$wait_xy" ]]; then
      printf 'system_anr_wait_tap=%s\n' "$wait_xy" >> "$summary"
      timeout 10s adb shell input tap $wait_xy || true
    else
      timeout 10s adb shell input keyevent KEYCODE_BACK || true
    fi
    sleep 2
  fi
  sleep 1
done
[[ "$ready" == 1 ]] || {
  printf 'focus_gate=failed\nlast_window_lines:\n' >> "$summary"
  grep -E 'mCurrentFocus|mFocusedApp|Application Not Responding|Splash Screen' \
    artifacts/focus-bootstrap-window.txt | tail -n 18 >> "$summary" || true
  echo 'Fresh emulator never focused drawn MainActivity; inspect window-state and host memory artifacts.' >&2
  exit 1
}
timeout 8s adb shell cat /proc/meminfo > artifacts/focus-bootstrap-device-memory.txt 2>&1 || true
sleep 2

# Focused can still mean a blank SurfaceFlinger frame while Compose draws.
# Require the REAL normal performance controls and STAGE entry from Android's
# current accessibility hierarchy before accepting a normal-app screenshot.
# Both supported keybed variants are exclusive: legacy piano or liquid-glass.
printf 'verify-live-normal-keyboard-accessibility\n' > "$phase"
keyboard_ready=0
# The assertion below is unchanged and still fail-closed; only the patience grew.
# After an emulator system ANR the Compose accessibility tree can take well over
# ten seconds to reappear on this CPU-starved runner (seen: three dumps ~5 s apart
# all empty although MainActivity already held focus).
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if timeout 35s adb shell uiautomator dump /sdcard/orbital-baseline-normal.xml \
       > artifacts/focus-bootstrap-normal-dump.txt 2>&1 &&
     timeout 15s adb exec-out cat /sdcard/orbital-baseline-normal.xml \
       > artifacts/focus-bootstrap-normal.xml &&
     python3 - artifacts/focus-bootstrap-normal.xml <<'PY'
import sys
import xml.etree.ElementTree as ET
names = [n.attrib.get("content-desc", "") for n in ET.parse(sys.argv[1]).iter("node")]
legacy_keyboard = sum("multitouch piano keyboard" in n.lower() for n in names)
liquid_keybed = sum(n.lower().startswith("twelve live chromatic liquid-glass keys, octave ") for n in names)
enter = names.count("Enter immersive performance")
exit_count = names.count("Exit immersive performance")
# Accept exactly one genuinely rendered performance keybed, never neither or both.
if (legacy_keyboard, liquid_keybed) not in ((1, 0), (0, 1)) or enter != 1 or exit_count != 0:
    raise SystemExit(f"Normal app not genuinely interactive: legacy={legacy_keyboard} liquid={liquid_keybed} enter={enter} exit={exit_count}")
print("PASS real normal MainActivity performance keybed+STAGE entry accessibility")
PY
  then
    keyboard_ready=1
    break
  fi
  printf 'normal_keyboard_retry=%s\n' "$attempt" >> "$summary"
  # The system process stays unresponsive on this starved runner, so its ANR dialog
  # can come back after a single Wait tap. Press Wait again whenever the dump shows
  # exactly that dialog; the strict assertion above is not relaxed.
  anr_xy="$(system_anr_wait_xy artifacts/focus-bootstrap-normal.xml)"
  if [[ -n "$anr_xy" ]]; then
    printf 'normal_gate_system_anr_wait_tap=%s\n' "$anr_xy" >> "$summary"
    timeout 10s adb shell input tap $anr_xy || true
  fi
  sleep 3
done
[[ "$keyboard_ready" == 1 ]] || {
  echo 'Focused MainActivity did not expose its genuine normal keyboard and STAGE control.' >&2
  exit 1
}
printf 'normal_keyboard_accessibility=verified\n' >> "$summary"
require_orbital_focus 'Lost real MainActivity focus after normal keyboard became accessible.' || exit 1
sleep 2

printf 'capture-genuine-normal-app-baseline\n' > "$phase"
captured=0
for attempt in 1 2 3 4 5; do
  # A frame is accepted only if ORBITAL owned the focus both before it was taken (a
  # recurring emulator system-ANR dialog may be dismissed first) and right after it was
  # taken, so a frame that could contain that dialog is always discarded and retaken.
  require_orbital_focus 'ORBITAL not focused before baseline capture' quiet || {
    printf 'baseline_retry_not_focused=%s\n' "$attempt" >> "$summary"
    sleep 2
    continue
  }
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
if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
    raise SystemExit("Missing Android screenshot PNG header")
width, height = struct.unpack(">II", header[16:24])
# A normal multi-control keyboard view cannot be the 19-20 kB blank frame
# returned immediately after focus in past CI runs. Keep this bound generous
# and only for the normal UI; the genuinely black FOCUS idle PNG is separate.
if width < 600 or height < 1000 or width >= height or size < 65536:
    raise SystemExit(f"Invalid genuine portrait baseline {width}x{height} size={size}")
if ending != b"\x00\x00\x00\x00IEND\xaeB\x60\x82":
    raise SystemExit("Truncated genuine portrait PNG")
print(f"PASS genuine foreground normal-app framebuffer {width}x{height}, {size} bytes")
PY
  then
    timeout 12s adb shell dumpsys window > artifacts/focus-bootstrap-window.txt 2>&1 || true
    if grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' artifacts/focus-bootstrap-window.txt; then
      captured=1
      break
    fi
    printf 'baseline_retry_focus_lost_during_capture=%s\n' "$attempt" >> "$summary"
    rm -f artifacts/orbital-emulator.png
    sleep 2
    continue
  fi
  printf 'baseline_retry=%s\n' "$attempt" >> "$summary"
  timeout 8s adb devices -l >> "$summary" 2>&1 || true
  sleep 2
done
[[ "$captured" == 1 ]] || {
  echo 'No actual normal-app portrait baseline PNG from the emulator.' >&2
  exit 1
}
# (Focus was verified right after the accepted frame inside the loop above.)
printf 'normal_baseline_sha256=%s\n'   "$(sha256sum artifacts/orbital-emulator.png | cut -d' ' -f1)" >> "$summary"
printf 'ready-for-strict-real-android-screenshot-proofs\n' > "$phase"
