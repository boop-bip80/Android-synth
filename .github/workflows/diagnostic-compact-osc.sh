#!/usr/bin/env bash
# Read-only diagnostic of already-built PR #149 APKs on a truly FRESH API35
# emulator. The job never updates production branches and never rebuilds
# Gradle source while 3GB QEMU is resident.
set -euo pipefail
mkdir -p artifacts
phase=artifacts/isolated-compact-phase.txt
printf 'bootstrapping-fresh-emulator\n' > "$phase"
finish() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "${live_logcat_pid:-}" ]]; then kill "$live_logcat_pid" >/dev/null 2>&1; fi
  if [[ -n "${watchdog_pid:-}" ]]; then kill "$watchdog_pid" >/dev/null 2>&1; fi
  if [[ -n "${live_logcat_pid:-}" ]]; then wait "$live_logcat_pid" >/dev/null 2>&1; fi
  if [[ -n "${watchdog_pid:-}" ]]; then wait "$watchdog_pid" >/dev/null 2>&1; fi
  printf '\n=== PRECRASH LIVE ANDROID LOGCAT LAST 160 ===\n'
  tail -n 160 artifacts/isolated-compact-live-logcat.txt 2>/dev/null || true
  printf '\n=== LAST HOST WATCHDOG ===\n'
  tail -n 130 artifacts/isolated-compact-host-watchdog.txt 2>/dev/null || true

  printf 'script_status=%s\nphase=%s\n' "$status" "$(cat "$phase" 2>/dev/null)" > artifacts/isolated-compact-summary.txt
  timeout 12s adb devices -l > artifacts/isolated-compact-adb.txt 2>&1 || true
  timeout 12s adb logcat -d -t 2500 -v threadtime > artifacts/isolated-compact-logcat.txt 2>&1 || true
  timeout 12s adb shell dumpsys meminfo com.orbital.synth > artifacts/isolated-compact-app-memory.txt 2>&1 || true
  timeout 12s adb shell dumpsys window > artifacts/isolated-compact-window.txt 2>&1 || true
  free -m > artifacts/isolated-compact-host-memory.txt 2>&1 || true
  ps -eo pid,comm,rss --sort=-rss > artifacts/isolated-compact-host-processes.txt 2>&1 || true
  exit "$status"
}
trap finish EXIT
# Stream the 8-10 seconds BEFORE QEMU vanishes; postmortem adb logcat -d
# cannot connect after native emulator exit. Never mask instrument result.
adb logcat -v threadtime -s OrbitalCompactTest:I AndroidRuntime:E SurfaceFlinger:E HWUI:E DEBUG:E libc:F ActivityManager:W '*:S' > artifacts/isolated-compact-live-logcat.txt 2>&1 &
live_logcat_pid=$!
(
  set +e
  while :; do
    printf '\n=== watchdog %s ===\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    free -m
    printf 'cgroup-events='
    cat /sys/fs/cgroup/memory.events 2>/dev/null | tr '\n' ' '
    printf '\n'
    ps -eo pid,comm,rss --sort=-rss | head -n 10
    timeout 3s adb devices -l | tail -n 3
    sleep 3
  done
) > artifacts/isolated-compact-host-watchdog.txt 2>&1 &
watchdog_pid=$!


app=app/build/outputs/apk/debug/app-debug.apk
testapk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$app" && -s "$testapk" ]] || { printf 'missing exact host APKS\n' >&2; exit 1; }
sha256sum "$app" "$testapk" > artifacts/isolated-compact-apk-sha256.txt
printf 'install exact host apk\n' > "$phase"
timeout 120s adb install -r "$app" > artifacts/isolated-compact-app-install.txt
timeout 120s adb install -r "$testapk" > artifacts/isolated-compact-test-install.txt
printf 'single real compose test with 360x350 layout\n' > "$phase"
free -m > artifacts/isolated-compact-memory-before.txt
timeout 180s adb shell am instrument -w -r \
  -e class 'com.orbital.synth.ui.OrbitalScreenTest#shortLandscapeSoundHasReachableEightLayerEditorAndAnchoredPiano' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/isolated-compact-instrumentation.txt
grep -Eq 'OK \(1 test\)' artifacts/isolated-compact-instrumentation.txt || {
  printf 'Isolated compact Compose test did not fully pass.\n' >&2
  exit 1
}
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' artifacts/isolated-compact-instrumentation.txt; then
  printf 'Isolated instrumentation reported an actual failed assertion.\n' >&2
  exit 1
fi
printf 'single test passed on cold emulator\n' > "$phase"
