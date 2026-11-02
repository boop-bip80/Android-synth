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

On 2026-11-02, an earlier baseline's host command was reported to pass locally: **89 core JVM tests, 31 Android JVM tests, lint with no errors, debug app APK and instrumentation test APK compilation**. These results **predate recent merged changes** and are not verification of the current `main`. The GitHub-hosted emulator and physical-device tests are separate gates and have **not** been confirmed green.

## CI emulator (no local emulator needed)

The [Android — build, JVM tests and emulator workflow](../.github/workflows/android.yml) runs on pushes to `main`, pull requests, and manual dispatches. Its jobs:

1. Run portable Kotlin DSP/music JUnit4 tests, Android app unit tests, Android lint, debug APK assembly and instrumentation test APK compilation.
2. Boot an Android API 35 x86_64 emulator, run instrumented smoke tests (including background/resume/recreate lifecycle and an Android audio-service/queued-sequencer probe), explicitly launch `com.orbital.synth/.MainActivity` and verify `am start -W` reports success. The audio-service probe tolerates a deliberately headless emulator without an output sink; it checks sequencer queue delivery only while the audio worker actually runs.
3. Verify `MainActivity` owns the foreground window after launch (with a bounded 45-second wait), then capture and validate a **real PNG** of the app via `adb exec-out screencap -p`.
4. Restart the real activity in an emulator display adjusted to **360 dp wide and 780 dp tall at 1.3× system font scale**, verify it regains focus, and capture a second PNG. The script restores the original display and font settings even after failure.

After **successful execution**, the run provides artifacts (14-day retention):

- `orbital-debug-apk`: the installable developer build.
- `orbital-api35-emulator-screenshot`: actual emulator screenshots of the default Pixel 6 profile and 360-dp / 1.3× font-size configuration, never the generated design mockup.
- `orbital-android-test-results`: instrumentation reports if generated.
- `orbital-api35-emulator-diagnostics`: recent logcat, activity/window state, launch result, SDK version, script exit status, **failed/last execution phase, workflow SHA/run provenance**, screenshot checksums, compact-layout launch/window state, bounded AudioFlinger/audio-policy/MIDI service dumps, app-memory summary, display size/density and diagnostic screen when the emulator script runs.
- `orbital-debug-android-test-apk`: compiled instrumentation APK to facilitate reproducing an emulator failure.
- `orbital-host-test-reports`: portable/core and Android JVM test reports, even when those tests fail and produced output.
- `orbital-lint`: lint report if generated.

The emulator script also has a 90-second package-manager readiness guard, a 30-minute instrumentation timeout, and a host-side `bash -n` syntax check. It retains a failing command's exit code while capturing diagnostics and restoring any display override. A second screenshot proves that the app launched and focused at the compact configuration; it does **not**, by itself, prove visual accessibility, absence of clipped controls or good touch ergonomics.

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
