#!/usr/bin/env bash
# A second capture on the *same* API 35 emulator after instrumentation and
# portrait/compact screenshots. This exercises a wide, short Compose viewport.
# It intentionally does not relax the existing focused/drawn app requirement.
set -euo pipefail

mkdir -p artifacts
printf '%s\n' 'landscape-configuration' > artifacts/emulator-phase.txt
original_override=''

collect_landscape_diagnostics() {
  local status=$?
  trap - EXIT
  set +e
  # Restore the previous display override so later diagnostics cannot inherit it.
  if [[ -n "$original_override" ]]; then
    timeout 10s adb shell wm size "$original_override" >/dev/null 2>&1
  else
    timeout 10s adb shell wm size reset >/dev/null 2>&1
  fi
  printf '%s\n' "$status" > artifacts/landscape-exit-status.txt
  # Reflect the second stage's failure in the combined emulator evidence too.
  printf '%s\n' "$status" > artifacts/emulator-exit-status.txt
  printf 'run=%s\ncommit=%s\nphase=%s\nlandscape_exit=%s\n' \
    "${GITHUB_RUN_ID:-unknown}" "${GITHUB_SHA:-unknown}" \
    "$(cat artifacts/emulator-phase.txt 2>/dev/null || printf unknown)" "$status" \
    > artifacts/landscape-provenance.txt
  timeout 15s adb shell dumpsys window > artifacts/landscape-window-state.txt 2>&1
  timeout 15s adb logcat -d -t 600 -v threadtime > artifacts/landscape-logcat.txt 2>&1
  exit "$status"
}
trap collect_landscape_diagnostics EXIT

landscape_window_drawn() {
  local snapshot="$1"
  grep -Eq 'mCurrentFocus=.*com[.]orbital[.]synth/com[.]orbital[.]synth[.]MainActivity' "$snapshot" || return 1
  if grep -Eq 'mTopFullscreenOpaqueWindowState=.*Splash Screen com[.]orbital[.]synth' "$snapshot"; then
    return 1
  fi
  awk '
    /Window #[0-9]+ Window/ && /com[.]orbital[.]synth\/com[.]orbital[.]synth[.]MainActivity/ { in_main=1; next }
    in_main && /Window #[0-9]+ Window/ { in_main=0 }
    in_main && /Surface: shown=true/ { drawn=1 }
    END { exit !drawn }
  ' "$snapshot"
}

launch_orbital_landscape() {
  timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/landscape-activity-launch.txt
  if grep -Eq 'Error type [0-9]+|Error:|Exception|Permission Denial' artifacts/landscape-activity-launch.txt; then
    echo 'Android rejected the landscape Activity launch.' >&2
    return 1
  fi
}

# The portrait stage restored its overrides. Read physical geometry and preserve
# any explicit override in case the CI environment changes in future.
size_output="$(timeout 15s adb shell wm size | tr -d '\r')"
original_override="$(printf '%s\n' "$size_output" | sed -n 's/^Override size: //p' | head -1)"
physical_size="$(printf '%s\n' "$size_output" | sed -n 's/^Physical size: //p' | head -1)"
if [[ ! "$physical_size" =~ ^([0-9]+)x([0-9]+)$ ]]; then
  echo "Could not identify physical emulator size: $size_output" >&2
  exit 1
fi
physical_width="${BASH_REMATCH[1]}"
physical_height="${BASH_REMATCH[2]}"
if (( physical_width >= physical_height || physical_width < 600 || physical_height < 1000 )); then
  echo "Expected portrait Pixel 6 physical geometry, got $physical_size" >&2
  exit 1
fi

# A deterministic wide/short viewport tests actual responsive Compose layout.
# No orientation sensor is needed in this headless emulator.
landscape_size="${physical_height}x${physical_width}"
timeout 15s adb shell wm size "$landscape_size"
timeout 15s adb shell am force-stop com.orbital.synth
launch_orbital_landscape

printf '%s\n' 'landscape-wait-for-drawn-app' > artifacts/emulator-phase.txt
landscape_ready=0
launcher_anr_recoveries=0
for attempt in {1..90}; do
  timeout 15s adb shell dumpsys window > artifacts/landscape-window-state.txt 2>&1 || true
  if timeout 10s adb shell pidof com.orbital.synth >/dev/null 2>&1 &&
     landscape_window_drawn artifacts/landscape-window-state.txt; then
    landscape_ready=1
    break
  fi
  # Recover only a known external Pixel Launcher ANR; an ORBITAL ANR fails.
  if (( launcher_anr_recoveries < 2 )) &&
     grep -Eq 'mCurrentFocus=.*Application Not Responding: com[.]google[.]android[.]apps[.]nexuslauncher' artifacts/landscape-window-state.txt; then
    if (( launcher_anr_recoveries == 0 )); then
      timeout 10s adb shell input keyevent KEYCODE_BACK || true
    else
      timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher || true
      launch_orbital_landscape
    fi
    launcher_anr_recoveries=$((launcher_anr_recoveries + 1))
  fi
  sleep 1
done
if [[ "$landscape_ready" != 1 ]]; then
  echo 'ORBITAL failed to draw a focused MainActivity in the wide/short viewport.' >&2
  exit 1
fi

printf '%s\n' 'landscape-screenshot' > artifacts/emulator-phase.txt
sleep 2
timeout 30s adb exec-out screencap -p > artifacts/orbital-landscape-emulator.png
test -s artifacts/orbital-landscape-emulator.png
test "$(file -b --mime-type artifacts/orbital-landscape-emulator.png)" = 'image/png'
landscape_geometry="$(python3 - artifacts/orbital-landscape-emulator.png <<'PY'
import struct
import sys

image_path = sys.argv[1]
with open(image_path, 'rb') as image:
    header = image.read(24)
if len(header) != 24 or header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR':
    raise SystemExit(f'{image_path}: missing PNG signature/IHDR')
width, height = struct.unpack('>II', header[16:24])
if width < 1000 or height < 600 or width <= height:
    raise SystemExit(f'{image_path}: not a usable landscape capture: {width}x{height}')
print(f'{width}x{height}')
PY
)"
printf 'landscape_viewport=%s\nlandscape_geometry=%s\nlandscape_ready_poll=%s\nlandscape_screenshot_sha256=%s\n' \
  "$landscape_size" "$landscape_geometry" "$attempt" \
  "$(sha256sum artifacts/orbital-landscape-emulator.png | cut -d' ' -f1)" \
  >> artifacts/emulator-summary.txt
printf '%s\n' 'complete' > artifacts/emulator-phase.txt
