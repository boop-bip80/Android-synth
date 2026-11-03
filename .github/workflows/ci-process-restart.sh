#!/usr/bin/env bash
# Mandatory cold-API35 two-process patch persistence proof, isolated from
# general Compose instrumentation, with exact host-produced APK provenance.
set -euo pipefail

mkdir -p artifacts
phase=artifacts/step-restart-phase.txt
summary=artifacts/step-restart-summary.txt
logcat_pid=
printf 'start\n' > "$phase"
printf 'source=%s\nrun=%s\nsuite=%s\n' \
  "${GITHUB_SHA:-unknown}" "${GITHUB_RUN_ID:-unknown}" \
  "${ORBITAL_TEST_SUITE:-unknown}" > "$summary"

finish() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" >/dev/null 2>&1 || true
  fi
  printf 'exit_status=%s\nphase=%s\n' \
    "$status" "$(cat "$phase" 2>/dev/null)" >> "$summary"
  timeout 9s adb devices -l > artifacts/step-restart-adb.txt 2>&1
  timeout 12s adb logcat -d -t 1800 -v threadtime \
    > artifacts/step-restart-logcat-tail.txt 2>&1
  timeout 12s adb shell dumpsys window \
    > artifacts/step-restart-window.txt 2>&1
  timeout 12s adb shell dumpsys meminfo com.orbital.synth \
    > artifacts/step-restart-app-memory.txt 2>&1
  # These diagnostics may fail when QEMU disappears; never mask the true code.
  exit "$status"
}
trap finish EXIT

app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$app_apk" && -s "$test_apk" ]] || {
  echo 'Missing exact validated host app or androidTest APK; refuse synthetic restart proof.' >&2
  exit 1
}
printf 'apk_origin=needs.build/actions.download-artifact\napp_sha256=%s\ntest_sha256=%s\n' \
  "$(sha256sum "$app_apk" | cut -d' ' -f1)" \
  "$(sha256sum "$test_apk" | cut -d' ' -f1)" >> "$summary"

# Stream before testing: post-crash adb may lose access to the device entirely.
timeout 18m adb logcat -v threadtime \
  > artifacts/step-restart-full-logcat.txt 2>&1 &
logcat_pid=$!

printf 'install-exact-apks\n' > "$phase"
timeout 120s adb install -r "$app_apk" > artifacts/step-restart-app-install.txt
timeout 120s adb install -r "$test_apk" > artifacts/step-restart-test-install.txt
installed="$(timeout 15s adb shell pm path com.orbital.synth | tr -d '\r')"
[[ "$installed" == *package:* ]] || {
  echo 'Production package was not installed in the fresh emulator.' >&2
  exit 1
}
# The test owns ActivityScenario launch; do not prelaunch another MainActivity
# or let a leftover host AudioTrack compete with the test's fresh worker.
timeout 20s adb shell am force-stop com.orbital.synth

runner=com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner
test_class=com.orbital.synth.ui.StepProcessRestartInstrumentedTest
printf 'prepare-real-step-patch-before-process-death\n' > "$phase"
timeout 180s adb shell am instrument -w -r \
  -e class "${test_class}#prepareStepPatchAndProveFocusPlayStateBeforeProcessDeath" \
  -e orbitalStepRestartPhase prepare "$runner" \
  | tee artifacts/step-restart-prepare.txt
grep -Eq 'OK \\(1 test\\)' artifacts/step-restart-prepare.txt || {
  echo 'STEP prepare did not complete one real AndroidJUnitRunner method.' >&2
  exit 1
}
grep -Eq 'INSTRUMENTATION_CODE:[[:space:]]*-1' artifacts/step-restart-prepare.txt || {
  echo 'AndroidJUnitRunner returned non-success after STEP preparation.' >&2
  exit 1
}
grep -q 'ORBITAL_STEP_PROCESS_PREPARED' artifacts/step-restart-prepare.txt || {
  echo 'STEP prepare did not prove persisted V5 patch and live PLAY/FOCUS.' >&2
  exit 1
}

printf 'actual-operating-system-force-stop\n' > "$phase"
timeout 20s adb shell am force-stop com.orbital.synth
# Require an ONLINE device as well as a genuinely absent target process.
timeout 8s adb shell getprop sys.boot_completed \
  | tr -d '\r\n' | grep -qx 1 || {
    echo 'Android disappeared before real process-death verification.' >&2
    exit 1
  }
if timeout 8s adb shell pidof com.orbital.synth >/dev/null 2>&1; then
  echo 'Production process survived explicit Android force-stop.' >&2
  exit 1
fi

# No pre-launch here. The second instrumentation's ActivityScenario launches
# the real MainActivity and asserts old PID != Process.myPid() from marker.
printf 'verify-different-os-pid-and-delete-only-test-patch\n' > "$phase"
timeout 180s adb shell am instrument -w -r \
  -e class "${test_class}#verifyAfterForcedDeathColdRestartAndDeleteOnlyOurPatch" \
  -e orbitalStepRestartPhase verify "$runner" \
  | tee artifacts/step-restart-verify.txt
grep -Eq 'OK \\(1 test\\)' artifacts/step-restart-verify.txt || {
  echo 'STEP V5 cold-process recall did not pass one true AndroidJUnitRunner method.' >&2
  exit 1
}
grep -Eq 'INSTRUMENTATION_CODE:[[:space:]]*-1' artifacts/step-restart-verify.txt || {
  echo 'AndroidJUnitRunner returned non-success after cold-process verification.' >&2
  exit 1
}
grep -q 'ORBITAL_STEP_PROCESS_VERIFIED' artifacts/step-restart-verify.txt || {
  echo 'Second OS PID and restored STEP audio clock were not proven.' >&2
  exit 1
}
printf 'step_v5_persistence=PASS\nprocess_death=REAL_FORCE_STOP\nfresh_android_pid=ASSERTED_BY_INSTRUMENTATION\nstep_audio_clock=PASS\nfocus_play_transition=PASS\nprepare_method=PASS\nverify_method=PASS\n' >> "$summary"
printf 'complete-real-two-process-proof\n' > "$phase"
