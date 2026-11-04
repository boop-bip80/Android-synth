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

# sys.boot_completed=1 is not proof that package-manager can install APKs.
# Match the established GENERAL emulator readiness gate on this fresh runner.
printf 'waiting-for-package-manager\n' > "$phase"
pm_ready=0
for attempt in {1..45}; do
  if timeout 8s adb shell pm list packages com.android.settings 2>/dev/null \
      | grep -q 'package:com.android.settings'; then
    pm_ready=1
    printf 'ready_after_attempt=%s\n' "$attempt" > artifacts/step-restart-package-manager-ready.txt
    break
  fi
  sleep 2
done
[[ "$pm_ready" == 1 ]] || {
  echo 'Fresh Android package manager failed bounded 90-second readiness gate.' >&2
  exit 1
}

# Real APK install must succeed; bounded retries only recover cold-boot PM races
# and retain every error for diagnostics (never skip or substitute an APK).
install_verified_apk() {
  local apk="$1"
  local name="$2"
  local attempt=0
  local log=""
  for attempt in 1 2 3; do
    log="artifacts/step-restart-${name}-install-${attempt}.txt"
    if timeout 90s adb install -r "$apk" > "$log" 2>&1 \
        && tr -d '\r' < "$log" | grep -Fxq 'Success'; then
      printf 'apk=%s\nverified_install_attempt=%s\n' "$apk" "$attempt" \
        > "artifacts/step-restart-${name}-install.txt"
      return 0
    fi
    echo "Attempt ${attempt}/3 failed installing validated ${name} APK; preserved ${log}" >&2
    sleep 3
  done
  echo "Failed all 3 real ADB installations of validated ${name} APK." >&2
  return 1
}
printf 'install-exact-apks\n' > "$phase"
install_verified_apk "$app_apk" app
install_verified_apk "$test_apk" test
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
run_verified_instrumentation() {
  local method="$1" phase_name="$2" out="$3"
  local attempt
  for attempt in 1 2 3; do
    # The fresh API35 emulator occasionally drops ADB's transport while ART
    # loads the test dex (am instrument exits 255 without a single byte).
    # Retry ONLY an empty-output transport failure. Never retry, overlook or
    # reclassify an assertion failure emitted by AndroidJUnitRunner.
    if timeout 180s adb shell am instrument -w -r \
      -e class "${test_class}#${method}" \
      -e orbitalStepRestartPhase "$phase_name" "$runner" \
      | tee "$out"; then
      grep -Eq 'OK [(]1 test[)]' "$out" || {
        echo "$phase_name returned without one passing instrumentation test." >&2
        return 1
      }
      grep -Eq 'INSTRUMENTATION_CODE:[[:space:]]*-1' "$out" || {
        echo "$phase_name returned a non-success instrumentation result." >&2
        return 1
      }
      return 0
    fi
    [[ ! -s "$out" ]] || {
      echo "$phase_name runner failed after emitting test output; no retry." >&2
      return 1
    }
    printf '%s_empty_adb_transport_attempt=%s\n' "$phase_name" "$attempt" >> "$summary"
    [[ "$attempt" -lt 3 ]] || break
    # If Android finished the prepare phase but ADB lost the reply, a marker
    # may already exist. Do not re-run a mutating save or overrun its result.
    if timeout 8s adb shell run-as com.orbital.synth \
      test -e files/orbital-step-process-smoke.marker >/dev/null 2>&1; then
      echo 'STEP marker exists after lost ADB response; refusing duplicate prepare.' >&2
      return 1
    fi
    timeout 20s adb wait-for-device || return 1
    timeout 12s adb shell getprop sys.boot_completed | tr -d '\r\n' | grep -qx 1 || return 1
    sleep 5
  done
  echo "$phase_name could not reach AndroidJUnitRunner after three actual transports." >&2
  return 1
}
printf 'prepare-real-step-patch-before-process-death\n' > "$phase"
run_verified_instrumentation \
  prepareStepPatchAndProveFocusPlayStateBeforeProcessDeath \
  prepare artifacts/step-restart-prepare.txt

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
run_verified_instrumentation \
  verifyAfterForcedDeathColdRestartAndDeleteOnlyOurPatch \
  verify artifacts/step-restart-verify.txt
printf 'step_v5_persistence=PASS\nprocess_death=REAL_FORCE_STOP\nfresh_android_pid=ASSERTED_BY_INSTRUMENTATION\nstep_audio_clock=PASS\nfocus_play_transition=PASS\nprepare_method=PASS\nverify_method=PASS\n' >> "$summary"
printf 'complete-real-two-process-proof\n' > "$phase"
