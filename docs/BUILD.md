# Building and verifying ORBITAL

## Pinned toolchain

- JDK 17
- Gradle 8.11.1 (installed by CI; no unverified wrapper binary committed)
- Android Gradle Plugin 8.9.2
- Kotlin and Compose Compiler Gradle Plugin 2.1.20
- Android SDK platform 35 and build-tools 35.0.0
- Compose BOM 2025.04.00
- Minimum Android API 26; compile / target API 35

AGP 8.9's Android compatibility table requires Gradle 8.11.1 and JDK 17. Kotlin 2.x uses its dedicated Compose compiler plugin. Versions are intentionally pinned; no dynamic dependency selectors.

## Local build (optional)

Install Android SDK 35, JDK 17 and Gradle 8.11.1. Configure the SDK with `ANDROID_HOME` or Git-ignored `local.properties`.

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

After a successful build, the debug APK is `app/build/outputs/apk/debug/app-debug.apk` (Android 26+). It is a developer APK, not a Play Store release.

On 2026-11-02, [PR #55's green Android CI run](https://github.com/boop-bip80/Android-synth/actions/runs/41512839802) passed host JVM tests, lint, app and instrumentation APK assembly, **34/34 API35 instrumentation tests**, and normal/360dp 1.3× screenshots. [PR #56's green CI run](https://github.com/boop-bip80/Android-synth/actions/runs/41512794970) verified the updated emulator harness, which contains a strict bounded recovery path for a specific external Pixel Launcher ANR overlay (the failure path is not claimed as exercised in that green run). Those runs verify their respective commits; check a passing run at the latest `main` SHA after subsequent merges. Physical-device validation remains separate.

## CI emulator (no local emulator needed)

The [Android — build, JVM tests and emulator workflow](../.github/workflows/android.yml) runs on pushes to `main`, pull requests, and manual dispatches. Its jobs:

1. Run portable Kotlin DSP/music JUnit4 tests, Android app unit tests, Android lint, debug APK assembly and instrumentation test APK compilation.
2. Boot an Android API 35 x86_64 emulator and run instrumented smoke tests (including background/resume/recreate lifecycle and audio-service/queued-sequencer probes). Because the instrumentation runner can uninstall the application, **reinstall and verify the debug APK** before screenshot checks. A deliberately headless emulator may lack an output sink; the audio-service probe checks sequencer delivery only while the audio worker actually runs.
3. Launch MainActivity without relying on `am start -W` as proof of readiness. Poll for a **drawn and focused app window with its splash gone** for up to 90 seconds, then capture and validate a **real PNG** via `adb exec-out screencap -p`. Only a specifically identified external Pixel Launcher ANR overlay may receive a bounded BACK/force-stop/relaunch recovery; an ORBITAL crash or app ANR must fail.
4. Relaunch the real activity at **360 dp wide and 780 dp tall at 1.3× font scale**, verify the same drawn/focus conditions, and capture the compact PNG. The script restores display and font overrides even on failure. The UI instrumentation suite also checks compact preset/orbit boundaries and complete visibility of the modulation toggle.

After **successful execution**, the run provides artifacts (14-day retention):

- `orbital-debug-apk`: the installable developer build.
- `orbital-api35-emulator-screenshot`: actual emulator screenshots of the default Pixel 6 profile and 360-dp / 1.3× font-size configuration, never the generated design mockup.
- `orbital-android-test-results`: instrumentation reports if generated.
- `orbital-api35-emulator-diagnostics`: recent logcat, activity/window state, launch result, SDK version, script exit status, **failed/last execution phase, workflow SHA/run provenance**, screenshot checksums, compact-layout and bounded Pixel Launcher ANR-recovery state, AudioFlinger/audio-policy/MIDI service dumps, app-memory summary, display facts and a diagnostic screen when the emulator script runs.
- `orbital-debug-android-test-apk`: compiled instrumentation APK to facilitate reproducing an emulator failure.
- `orbital-host-test-reports`: portable/core and Android JVM test reports, even when those tests fail and produced output.
- `orbital-lint`: lint report if generated.

The emulator script also has a 90-second package-manager readiness guard, a 30-minute instrumentation timeout, and a host-side `bash -n` syntax check. It retains a failing command's exit code while collecting diagnostics and restoring display overrides. A second screenshot proves that the app launched at the compact configuration; **it does not alone certify visual accessibility or ergonomics**. Inspect the PNG and the Compose layout-bound test results. Successful jobs require their expected APKs/screenshots; failed jobs still upload diagnostics when available. In-progress `main` runs are not cancelled by later pushes, though stale pending runs may be superseded.

Uploads use an always-run step, so a failed build can still expose partial test or diagnostic artifacts. Artifact presence by itself does **not** mean the job passed. Emulator startup failures may happen before any logcat file can be captured.

### Interpreting status honestly

- **Queued / pending / waiting:** no build or emulator claim can be made. If the job shows no assigned runner, this is a scheduling/permissions/runner-capacity diagnostic, not a source-code error.
- **Failed:** inspect the first failed step and logs, fix in source, rerun; do not count tests as passed.
- **Cancelled:** not a pass or failure; check concurrency and newer runs.
- **Green:** confirms that toolchain/build, selected host tests, instrumentation and screenshot capture passed for that commit. Hardware low-latency, thermal, Bluetooth and physical-MIDI claims still need real devices.

A precise CI status report should include workflow run URL, commit SHA, job conclusion and artifacts. Link to artifacts only from a verified run.

## Real-device checklist

- Speaker and wired USB audio output, interruptions, audio focus and lifecycle.
- Median/p95 output latency and underruns during 16-voice plus effects stress.
- Physical MIDI keyboard connection/disconnection; note on/off, velocity, CC and pitch bend.
- Multi-touch keyboard release/cancel; portrait, landscape, small phones, large font scales.
- Thermal throttling, CPU and battery with long sessions.

## Troubleshooting

- `SDK location not found`: install SDK 35 and configure `ANDROID_HOME` or `local.properties`.
- `Gradle requires Java 17`: select JDK 17.
- `No emulator tests found`: inspect `:app` Android instrumentation source and test runner.
- No emulator runner assigned: inspect account/repository GitHub Actions settings and hosted-runner availability; changing Android code cannot make a queued job start.
- Emulator audio results are not substitutes for physical-device latency measurements.
