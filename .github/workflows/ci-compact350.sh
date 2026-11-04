#!/usr/bin/env bash
# Fresh API35 isolation lane for strict short-landscape SOUND assertions,
# real oscillator editor scrolling, five ENGINE options and pinned piano notes.
# The full general suite excludes only this class (plus the separately verified
# immersive capture class), and this lane independently requires all four JUnit XML cases.
set -euo pipefail

mkdir -p artifacts
printf '%s\n' 'booted-waiting-for-package-manager' > artifacts/emulator-phase.txt
filtered_logcat_pid=
full_logcat_pid=
host_sampler_pid=

collect_diagnostics() {
  local status=$?
  trap - EXIT
  set +e
  for background_pid in "$filtered_logcat_pid" "$full_logcat_pid" "$host_sampler_pid"; do
    if [[ -n "$background_pid" ]]; then
      kill "$background_pid" >/dev/null 2>&1 || true
      wait "$background_pid" >/dev/null 2>&1 || true
    fi
  done
  # Device/ADB may already be dead: bound every diagnostic independently.
  printf '%s\n' "$status" > artifacts/emulator-exit-status.txt
  printf 'workflow_run_id=%s\ncommit=%s\nphase=%s\nscript_exit=%s\n' \
    "${GITHUB_RUN_ID:-unknown}" "${GITHUB_SHA:-unknown}" \
    "$(cat artifacts/emulator-phase.txt 2>/dev/null || printf unknown)" "$status" \
    > artifacts/emulator-provenance.txt
  timeout 12s adb logcat -d -t 1500 -v threadtime > artifacts/emulator-logcat.txt 2>&1
  timeout 12s adb shell dumpsys meminfo com.orbital.synth > artifacts/app-memory.txt 2>&1
  timeout 12s adb shell dumpsys activity activities > artifacts/activity-state.txt 2>&1
  # On a failed assertion, preserve the exact physical Android framebuffer
  # captured by the test (never a generated/mock image or post-crash substitute).
  if (( status != 0 )); then
    if timeout 12s adb exec-out run-as com.orbital.synth \
        cat files/orbital-compact350-capture-failure.png \
        > artifacts/diagnostic-screen.png 2>/dev/null &&
       [[ -s artifacts/diagnostic-screen.png ]] &&
       [[ "$(file -b --mime-type artifacts/diagnostic-screen.png)" == "image/png" ]]; then
      sha256sum artifacts/diagnostic-screen.png \
        > artifacts/compact350-failed-frame-sha256.txt
      echo 'Preserved real failed compact350 foreground screenshot in diagnostics.'
    else
      # `adb run-as` can print an error to stdout after UTP uninstalls the app.
      # Never label that text as a genuine Android framebuffer artifact.
      rm -f artifacts/diagnostic-screen.png
      echo 'No valid real compact350 failure PNG was available after test cleanup.'
    fi
  fi
  timeout 8s adb devices -l > artifacts/compact350-devices-after.txt 2>&1
  timeout 8s sudo dmesg --ctime > artifacts/compact350-host-kernel.txt 2>&1
  if [[ -s artifacts/emulator-full-logcat.txt ]] &&
     (( $(stat -c%s artifacts/emulator-full-logcat.txt) > 20000000 )); then
    tail -c 20000000 artifacts/emulator-full-logcat.txt > artifacts/emulator-full-logcat.tail
    mv artifacts/emulator-full-logcat.tail artifacts/emulator-full-logcat.txt
  fi
  printf '\n=== compact 350dp instrumentation phases ===\n'
  if [[ -s artifacts/compact350-live-logcat.txt ]]; then
    grep -E 'OrbitalCompact350|FATAL EXCEPTION|ANR in com[.]orbital[.]synth' \
      artifacts/compact350-live-logcat.txt | tail -n 100 || true
  fi
  printf '\n=== isolated test JUnit assertions ===\n'
  if [[ -d app/build/outputs/androidTest-results ]]; then
    find app/build/outputs/androidTest-results -name '*.xml' -type f -print
    # Show the actual assertion text so a failed lane is diagnosable from the log.
    while IFS= read -r report; do
      if grep -Eq '<failure|<error' "$report"; then
        printf 'XML failure excerpt: %s\n' "$report"
        grep -n -m 3 -A 6 -E '<failure|<error' "$report" | cut -c 1-420
      fi
    done < <(find app/build/outputs/androidTest-results -type f -name '*.xml' -print)
  fi
  exit "$status"
}
trap collect_diagnostics EXIT

ready=0
for attempt in {1..45}; do
  if timeout 8s adb shell pm list packages com.android.settings 2>/dev/null \
      | grep -q 'package:com.android.settings'; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" != 1 ]]; then
  echo 'Fresh compact350 emulator package manager unavailable.' >&2
  exit 1
fi

app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$app_apk" && -s "$test_apk" ]] || {
  echo 'Precompiled exact app and Android test APKs are required.' >&2
  exit 1
}
printf 'suite=compact350\nclass=com.orbital.synth.ui.Compact350SoundInstrumentedTest\nexpected_testcases=4\napp_sha256=%s\ntest_apk_sha256=%s\n' \
  "$(sha256sum "$app_apk" | cut -d' ' -f1)" \
  "$(sha256sum "$test_apk" | cut -d' ' -f1)" \
  > artifacts/instrumentation-suite.txt

# Continuous host-side OOM/CPU/RSS evidence, even if QEMU disappears and
# device-side dumpsys is no longer available. Sampling never changes test status.
(
  set +e
  while :; do
    printf '\n=== compact350-host-sample %s phase=%s ===\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      "$(cat artifacts/emulator-phase.txt 2>/dev/null)"
    free -m
    for path in /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.max \
      /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory.swap.current; do
      if [[ -r "$path" ]]; then
        printf '%s: ' "$path"
        tr '\n' ' ' < "$path"
        printf '\n'
      fi
    done
    ps -eo pid,ppid,comm,rss,vsz,etime --sort=-rss | head -n 22
    sleep 5
  done
) > artifacts/emulator-host-memory.txt 2>&1 &
host_sampler_pid=$!

timeout 18m adb logcat -v threadtime > artifacts/emulator-full-logcat.txt 2>&1 &
full_logcat_pid=$!
timeout 18m adb logcat -v threadtime -s \
  OrbitalCompact350:I AndroidRuntime:E ActivityManager:W \
  SurfaceFlinger:E HWUI:E libc:F DEBUG:E '*:S' \
  > artifacts/compact350-live-logcat.txt 2>&1 &
filtered_logcat_pid=$!

printf '%s\n' 'fresh-emulator-compact350-class-instrumentation' > artifacts/emulator-phase.txt
# Keep the actual Gradle connected test, with genuine UTP/JUnit XML. The
# instrumentation runner's class filter is checked again against XML below.
timeout 15m gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.orbital.synth.ui.Compact350SoundInstrumentedTest \
  --stacktrace --no-daemon --console=plain

printf '%s\n' 'strict-junit-verification' > artifacts/emulator-phase.txt
python3 - <<'PY'
import glob
import xml.etree.ElementTree as ET

files = glob.glob("app/build/outputs/androidTest-results/connected/**/*.xml", recursive=True)
cases = [c for path in files for c in ET.parse(path).findall(".//testcase")]
expected_class = "com.orbital.synth.ui.Compact350SoundInstrumentedTest"
expected_names = {
    "shortLandscapeSoundHasReachableEightLayerEditorAndAnchoredPiano",
    "shortLandscapeCanSelectAllFiveArchitecturesWithAnchoredPiano",
    "compactSoundPageRetainsSelectionAcrossOscillatorRoundTrips",
    "compactPatchArrowsReachableByRealVerticalScrollAndPianoRemainsPlayable",
}
if len(cases) != 4:
    raise SystemExit(f"Expected exactly four isolated compact350 tests; got {len(cases)} in {len(files)} XML files")
actual = {(c.get("classname"), c.get("name")) for c in cases}
if actual != {(expected_class, name) for name in expected_names}:
    raise SystemExit(f"Unexpected/ignored class filter, expected four distinct methods: {actual}")
if any(c.findall(tag) for c in cases for tag in ("failure", "error", "skipped")):
    raise SystemExit("A compact350 test failed, errored, or skipped")
print("Compact 350dp SOUND: exactly four real API35 emulator JUnit PASS cases")
PY
printf '%s\n' 'PASS: exactly 4 isolated real API35 JUnit cases; oscillator scroll, five ENGINE choices, page persistence, preset physical touch, balanced piano notes' \
  > artifacts/compact350-junit-summary.txt

# Gradle UTP's connected-test cleanup can clear app-private files after it
# reports its four JUnit cases. All four cases above MUST still pass/XML-match.
# Re-install the EXACT previously SHA-logged debug/test APKs and rerun ONLY
# the real OSC8 physical screenshot test with direct AndroidJUnitRunner, as
# proven in STAGE/VISUALS. Capture/stream both PNGs before any UTP cleanup.
printf '%s\n' 'reinstall-exact-apks-for-direct-real-frame-capture' > artifacts/emulator-phase.txt
timeout 40s adb install -r "$app_apk"
timeout 40s adb install -r "$test_apk"
printf '%s\n' 'direct-extra-real-compact350-screenshot-instrumentation' > artifacts/emulator-phase.txt
timeout 180s adb shell am instrument -w -r \
  -e class 'com.orbital.synth.ui.Compact350SoundInstrumentedTest#shortLandscapeSoundHasReachableEightLayerEditorAndAnchoredPiano' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/compact350-direct-capture-instrumentation.txt
grep -Eq 'OK \(1 test\)' artifacts/compact350-direct-capture-instrumentation.txt || {
  echo 'Extra genuine physical OSC8 screenshot capture method did not pass.' >&2
  exit 1
}
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' \
    artifacts/compact350-direct-capture-instrumentation.txt; then
  echo 'Direct compact350 instrumented capture reported failure.' >&2
  exit 1
fi
printf '%s\n' 'extra_direct_capture=1_strict_pass' >> artifacts/instrumentation-suite.txt
# Provenance: the test logs both actual Bitmap PNG lengths and this list
# confirms the app-private files exist under the same debug package.
timeout 12s adb shell run-as com.orbital.synth ls -l files \
  > artifacts/compact350-app-files-before-extract.txt 2>&1 || true

# Test #1 saves TWO genuine UiAutomation framebuffers: selected OSC8
# chip + anchored piano on the first screen, then physically swiped/sculpted
# OSC8 glowing waveform. Neither image may be absent/blank/invalid.
printf '%s\n' 'extract-two-physical-compact-oscillator-screenshots' > artifacts/emulator-phase.txt
for shot in orbital-compact350-osc8-selector.png orbital-compact350-luminous.png; do
  extracted=0
  for attempt in 1 2; do
    if timeout 20s adb exec-out run-as com.orbital.synth \
        cat "files/$shot" > "artifacts/$shot" &&
       [[ -s "artifacts/$shot" ]] &&
       [[ "$(file -b --mime-type "artifacts/$shot")" == "image/png" ]]; then
      extracted=1
      break
    fi
    printf 'retry=%s,shot=%s,bytes=%s\n' "$attempt" "$shot" \
      "$(stat -c%s "artifacts/$shot" 2>/dev/null || printf 0)" \
      >> artifacts/compact350-extract-retries.txt
    sleep 2
  done
  [[ "$extracted" == 1 ]] || {
    echo "Unable to extract genuine physical compact350 PNG: $shot" >&2
    exit 1
  }
done
python3 - <<'PY'
from pathlib import Path
import struct

names = ("orbital-compact350-osc8-selector.png", "orbital-compact350-luminous.png")
evidence = ["source=android.uiAutomation.takeScreenshot", "exact_test_cases=4",
            "test=OSC8-selected-pinned-piano-then-physical-wave-XY"]
for name in names:
    path = Path("artifacts") / name
    raw = path.read_bytes()
    if len(raw) < 4096 or raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"Missing/invalid actual API35 framebuffer PNG: {name}")
    width, height = struct.unpack(">II", raw[16:24])
    if width < 360 or height < 350:
        raise SystemExit(f"Real emulator framebuffer too small: {name} {width}x{height}")
    if raw[-12:] != b"\x00\x00\x00\x00IEND\xaeB\x60\x82":
        raise SystemExit(f"Truncated native screenshot with no PNG IEND: {name}")
    evidence.append(f"{name}={width}x{height};{len(raw)}bytes")
    print(f"Physical OSC8 screenshot PASS: {name}, {width}x{height}, {len(raw)} bytes")
Path("artifacts/compact350-native-framebuffer.txt").write_text(
    "\n".join(evidence) + "\n", encoding="utf8"
)
PY
printf '%s\n' 'complete' > artifacts/emulator-phase.txt
