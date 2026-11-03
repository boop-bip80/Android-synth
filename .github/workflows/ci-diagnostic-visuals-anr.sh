#!/usr/bin/env bash
# Diagnostic-only reproduction: exact verified release candidate APKs, real
# API35 full-screen stage bootstrap, then strict force-stop/relaunch FOCUS proof.
# No app ANR is dismissed; any normal UI gate failure remains nonzero.
set -Eeuo pipefail
mkdir -p artifacts
expected_app_sha=54a9e2934a10deb724c0340ecc6b39cd8d1867c7b7616af2f879a1f374c5626e
expected_test_sha=1f12abeec1b3e0630182c3c66471316c6e9cb26b8209d3fee2e8b1fecbf09732
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
test -s "$app_apk"
test -s "$test_apk"
test "$(sha256sum "$app_apk" | cut -d' ' -f1)" = "$expected_app_sha"
test "$(sha256sum "$test_apk" | cut -d' ' -f1)" = "$expected_test_sha"
printf 'source_run=41633233561\nsource_pr_merge_sha=5ce8d91f51954ca116b9a54825aeaf8f40d61631\napp_sha256=%s\ntest_sha256=%s\n' "$expected_app_sha" "$expected_test_sha" > artifacts/diag-provenance.txt
printf 'starting\n' > artifacts/diag-phase.txt
logcat_pid=
sampler_pid=

on_exit() {
  local result=$?
  trap - EXIT
  set +e
  printf 'exit_status=%s\nphase=%s\n' "$result" "$(cat artifacts/diag-phase.txt 2>/dev/null)" >> artifacts/diag-provenance.txt
  # Capture BEFORE the Android emulator runner tears down QEMU. An actual
  # ORBITAL ANR is evidence; never click Wait/Close or swallow the exit code.
  timeout 10s adb devices -l > artifacts/diag-adb-devices.txt 2>&1
  timeout 10s adb shell dumpsys window > artifacts/diag-final-window.txt 2>&1
  timeout 15s adb shell dumpsys activity activities > artifacts/diag-final-activities.txt 2>&1
  timeout 15s adb shell dumpsys activity processes > artifacts/diag-final-processes.txt 2>&1
  timeout 15s adb shell dumpsys activity anr > artifacts/diag-anr-activity.txt 2>&1
  timeout 12s adb shell dumpsys input > artifacts/diag-final-input.txt 2>&1
  timeout 12s adb shell dumpsys media.audio_flinger > artifacts/diag-final-audio-flinger.txt 2>&1
  timeout 12s adb shell dumpsys media.audio_policy > artifacts/diag-final-audio-policy.txt 2>&1
  timeout 12s adb shell dumpsys midi > artifacts/diag-final-midi.txt 2>&1
  timeout 12s adb shell ls -la /data/anr > artifacts/diag-anr-directory.txt 2>&1
  # Unprivileged shell may be denied /data/anr. Record that honestly; never
  # escalate privileges or disguise a permission failure as stack evidence.
  timeout 12s adb shell 'for f in /data/anr/*; do if test -r "$f"; then echo "$f"; head -c 65536 "$f"; fi; done' > artifacts/diag-anr-readable-files.txt 2>&1
  app_pid="$(timeout 8s adb shell pidof com.orbital.synth 2>/dev/null | tr -d '\r\n')"
  printf 'pid=%s\n' "$app_pid" > artifacts/diag-app-pid.txt
  if [[ "$result" != 0 && "$app_pid" =~ ^[0-9]+$ ]]; then
    # A debug APK permits run-as on most API35 emulator images; a SIGQUIT from
    # the same UID asks ART to write genuine managed thread stacks. Failure is
    # only a diagnostic limitation, not a reason to downgrade UI assertions.
    timeout 12s adb shell run-as com.orbital.synth kill -3 "$app_pid"       > artifacts/diag-sigquit-result.txt 2>&1
    sleep 2
  fi
  timeout 15s adb logcat -d -b all -v threadtime > artifacts/diag-logcat-after-sigquit.txt 2>&1
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$sampler_pid" ]]; then
    kill "$sampler_pid" >/dev/null 2>&1 || true
    wait "$sampler_pid" >/dev/null 2>&1 || true
  fi
  if [[ -s artifacts/diag-continuous-logcat.txt ]] &&
     (( $(stat -c%s artifacts/diag-continuous-logcat.txt) > 25000000 )); then
    tail -c 25000000 artifacts/diag-continuous-logcat.txt > artifacts/diag-logcat-tail.txt
  fi
  exit "$result"
}
trap on_exit EXIT

# Continuous evidence survives Android's rolling logcat buffer and records
# ActivityManager/InputDispatcher ANR messages at the moment they happen.
timeout 20m adb logcat -b all -v threadtime > artifacts/diag-continuous-logcat.txt 2>&1 &
logcat_pid=$!

# Low-rate timeline: timing and focus across the exact second cold relaunch.
# A readable PID/focus does not count as UI success; original gate is untouched.
(
  set +e
  while :; do
    printf '\n=== sample %s phase=%s ===\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(cat artifacts/diag-phase.txt 2>/dev/null)"
    timeout 5s adb shell pidof com.orbital.synth
    timeout 6s adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|Application Not Responding|Splash Screen' | head -n 8
    free -m | head -n 3
    ps -eo pid,ppid,comm,rss,vsz --sort=-rss | head -n 5
    sleep 5
  done
) > artifacts/diag-lifecycle-timeline.txt 2>&1 &
sampler_pid=$!

printf 'strict-stage-bootstrap\n' > artifacts/diag-phase.txt
bash .github/workflows/ci-stage-bootstrap.sh

# Ten strict, independent force-stop/relaunch transitions on an unchanged
# production APK and the same live API35 emulator. Each round verifies real
# keybed, mode transitions, stage screenshot and return; never swallow failures.
printf 'source_run=41633233561\nrounds=10\n' > artifacts/diag-cold-relaunch-rounds.txt
for round in {1..10}; do
  printf 'round=%s_start_utc=%s\n' "$round" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> artifacts/diag-cold-relaunch-rounds.txt
  printf 'strict-immersive-cold-relaunch-round-%02d\n' "$round" > artifacts/diag-phase.txt
  bash .github/workflows/ci-immersive-stage.sh
  # Fresh verified Android UI hierarchy and framebuffer per round.
  cp artifacts/immersive-regular.xml "artifacts/diag-round-$round-normal.xml"
  cp artifacts/immersive-stage.xml "artifacts/diag-round-$round-stage.xml"
  cp artifacts/immersive-returned.xml "artifacts/diag-round-$round-returned.xml"
  cp artifacts/orbital-immersive-emulator.png "artifacts/diag-round-$round-stage.png"
  printf 'round=%s_pass_utc=%s app_pid=%s stage_png_sha=%s\n' \
    "$round" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(timeout 5s adb shell pidof com.orbital.synth | tr -d '\r\n')" \
    "$(sha256sum artifacts/orbital-immersive-emulator.png | cut -d' ' -f1)" \
    >> artifacts/diag-cold-relaunch-rounds.txt
done
printf 'complete-10-strict-cold-relaunch-rounds\n' > artifacts/diag-phase.txt
