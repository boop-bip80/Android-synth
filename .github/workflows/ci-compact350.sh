#!/usr/bin/env bash
# Fresh API35 isolation lane for strict short-landscape SOUND assertions,
# real oscillator editor scrolling, five ENGINE options and pinned piano notes.
# The full general suite excludes only this class (plus the separately verified
# immersive capture class), and this lane independently requires both JUnit XML cases.
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
printf 'suite=compact350\nclass=com.orbital.synth.ui.Compact350SoundInstrumentedTest\nexpected_testcases=2\napp_sha256=%s\ntest_apk_sha256=%s\n' \
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
}
if len(cases) != 2:
    raise SystemExit(f"Expected exactly two isolated compact350 tests; got {len(cases)} in {len(files)} XML files")
actual = {(c.get("classname"), c.get("name")) for c in cases}
if actual != {(expected_class, name) for name in expected_names}:
    raise SystemExit(f"Unexpected/ignored class filter, expected two distinct methods: {actual}")
if any(c.findall(tag) for c in cases for tag in ("failure", "error", "skipped")):
    raise SystemExit("A compact350 test failed, errored, or skipped")
print("Compact 350dp SOUND: exactly two real-device JUnit PASS cases")
PY
printf '%s\n' 'PASS: exactly 2 isolated real API35 JUnit cases; oscillator scroll, five ENGINE choices, piano notes' \
  > artifacts/compact350-junit-summary.txt
printf '%s\n' 'complete' > artifacts/emulator-phase.txt
