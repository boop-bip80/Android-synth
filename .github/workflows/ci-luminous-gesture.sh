#!/usr/bin/env bash
# Temporary, isolated API35 diagnostic for exactly ONE luminous editor rail test.
# Both APKs must be precompiled by the workflow BEFORE QEMU launches.
set -euo pipefail
mkdir -p artifacts
logcat_pid=
cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1
    wait "$logcat_pid" >/dev/null 2>&1
  fi
  printf 'test_exit_status=%s\n' "$status" > artifacts/luminous-probe-summary.txt
  timeout 8s adb devices -l >> artifacts/luminous-probe-summary.txt 2>&1
  timeout 10s adb shell dumpsys window windows \
    > artifacts/luminous-probe-window.txt 2>&1
  echo "=== REAL Android logcat: user-visible touch phases (bounded) ==="
  if [[ -s artifacts/luminous-probe-logcat.txt ]]; then
    grep -En 'LuminousGestureProbe|FATAL EXCEPTION|Fatal signal|OutOfMemoryError|lowmemorykiller|ANR in|killed process|am_crash' \
      artifacts/luminous-probe-logcat.txt | tail -n 75 || true
    echo "=== REAL Android logcat: final crash context (last 100 lines) ==="
    tail -n 100 artifacts/luminous-probe-logcat.txt
  fi
  exit "$status"
}
trap cleanup EXIT

app_apk="$(find artifacts/luminous-app-apk -type f -name '*.apk' -print -quit)"
test_apk="$(find artifacts/luminous-test-apk -type f -name '*.apk' -print -quit)"
test -n "$app_apk" && test -s "$app_apk"
test -n "$test_apk" && test -s "$test_apk"
printf 'app_sha256=%s\ntest_sha256=%s\n' \
  "$(sha256sum "$app_apk" | cut -d' ' -f1)" \
  "$(sha256sum "$test_apk" | cut -d' ' -f1)" \
  > artifacts/luminous-probe-provenance.txt

echo "LUMINOUS_PROBE_PHASE 01 installing already-built production and instrumentation APKs"
timeout 90s adb install -r "$app_apk" > artifacts/luminous-probe-app-install.txt
timeout 90s adb install -r "$test_apk" > artifacts/luminous-probe-test-install.txt
adb shell pm list instrumentation > artifacts/luminous-probe-runner.txt
adb logcat -c
adb logcat -v threadtime > artifacts/luminous-probe-logcat.txt 2>&1 &
logcat_pid=$!

echo "LUMINOUS_PROBE_PHASE 02 starting single 70dp TalkBack+finger method on COLD API35"
set +e
timeout 150s adb shell am instrument -w -r \
  -e class 'com.orbital.synth.ui.LuminousOscillatorWaveEditorTest#accessibleSeventyDpRailsUseTalkBackProgressAndRealFingerTaps' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/luminous-probe-instrumentation.txt
test_status=${PIPESTATUS[0]}
set -e
echo "LUMINOUS_PROBE_PHASE 03 instrumentation process exit: $test_status"
tail -n 25 artifacts/luminous-probe-instrumentation.txt
if (( test_status != 0 )); then
  echo "Single-method Android instrumentation failed on the real emulator" >&2
  exit "$test_status"
fi
grep -Fxq 'OK (1 test)' artifacts/luminous-probe-instrumentation.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' \
  artifacts/luminous-probe-instrumentation.txt; then
  echo "Single-method Android instrumentation reported a real failure" >&2
  exit 1
fi
echo "LUMINOUS_PROBE_PHASE 04 strict single-method real API35 PASS"
