# Building, testing and releasing ORBITAL

ORBITAL is a native Android multi-engine synth. This document distinguishes a **validated developer APK** from a production-signed release. A successful emulator pipeline is not physical-phone latency, thermal, USB MIDI, accessibility, or Play Store certification.

## Pinned toolchain

- JDK 17; Gradle 8.11.1; Android Gradle Plugin 8.9.2.
- Kotlin/Compose Compiler Gradle Plugin 2.1.20; Compose BOM 2025.04.00.
- Android SDK platform/build tools 35; minimum API 26, compile and target API 35.
- GitHub Actions provides Gradle on hosted runners; an unverified wrapper binary is not committed.

## Optional local build

With JDK 17, Android SDK 35 and Gradle 8.11.1 installed, set `ANDROID_HOME` or a Git-ignored `local.properties`, then run:

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The developer APK is `app/build/outputs/apk/debug/app-debug.apk`. It is debug signed, Android API 26+, and **not Play-signed**. The normal supported build/test route is [GitHub Actions](../.github/workflows/android.yml); a local Android emulator is optional.

## What counts as a verified main build

Only a **completed, successful, first-party `push` run on `main`** of [Android — build, JVM tests and emulator](../.github/workflows/android.yml), for the exact source commit being released, is acceptable. All six named jobs must exist and conclude `success`:

| Required job | What it establishes |
| --- | --- |
| `Kotlin tests, lint and debug APK` | Core DSP/music and Android JVM tests, lint, debug APK and Android test APK build |
| `API 35 general tests and emulator screenshots` | Real Android x86_64 instrumentation, gestures, lifecycle, audio service, STEP persistence/process-restart and general PNG evidence |
| `API 35 compact tests and emulator screenshots` | Compact 360dp / large-text composition, real controls, accessibility and image evidence |
| `API 35 stage tests and emulator screenshots` | A genuinely focused app, strict normal keyboard entry, keyboardless FOCUS, live held-note effects, exit, and foreground PNG |
| `API 35 visuals tests and emulator screenshots` | On-device oscillator workbench, landscape, live visual interaction and real framebuffer captures |
| `API 35 compact350 tests and emulator screenshots` | Fresh API35 QEMU process running **exactly two named JUnit PASS tests** on the 360 × 350dp short-landscape SOUND screen: (1) OSC far-edge swipe, layer 7 selection and anchored piano; (2) all five ENGINE architectures, a live parameter callback and anchored piano. Missing, skipped, failed or extra cases make this lane red. It produces diagnostics, not an additional gallery image |

Job conclusions for **the exact head SHA** are the release gate. A generated concept image, passing JVM tests alone, five of six green jobs, a stale workflow, an uploaded artifact from a red job, or a screenshot from another commit does not qualify.

The GENERAL lane precompiles Android test inputs **before launching QEMU** and records bounded host emulator memory, logcat and exact failure-phase diagnostics. It runs a real two-process STEP regression: configure/save a patch, force-stop the app, restart it into a new PID and confirm on-disk recall, clock progression, FOCUS/PLAY transition and voice release. Tests also exercise native touch ownership, selected engine and oscillator changes, audio startup, and input/lifecycle safety. The instrumented suite count changes as tests are added; never copy a past count into the current README.

Emulator failures require diagnosis, not a green status assumption:

- `Failed to find ColorBuffer`, ADB port 5554 refusal, SDK ZIP bootstrap errors or an external Pixel Launcher ANR may be emulator/runner infrastructure; inspect the log, connected JUnit XML, phase markers, and artifacts.
- A real Compose `AssertionError`, lost note, unresponsive oscillator editor, wrong keybed count or a captured blank screenshot is an application/test failure until fixed and retested.
- A failed-only rerun may be used for demonstrable runner failures while retaining the exact source commit and **all original assertions**. The final six-job result still must be green.
- Empty or absent JUnit results do not prove an application pass; an incompletely executed instrumentation suite is red.

Each workflow run uploads diagnostic and test artifacts even when a job fails, where possible. Look for the exact named artifacts `orbital-debug-apk`, `orbital-debug-android-test-apk`, `orbital-host-test-reports`, `orbital-lint`, the API35 suite-specific screenshot/diagnostic bundles, and instrumentation XML. GitHub Actions artifacts have finite retention (currently 14 days); do not treat artifact ZIP links as permanent releases.

## Genuine screenshots and their provenance

After a fully green **still-current main** run, [the screenshot publisher](../.github/workflows/publish-screenshots.yml) downloads captures only from that run and validates real PNG signature, dimensions, chunks/CRC and terminal IEND. It rejects a known blank-but-valid normal portrait and refuses stale source SHA, non-main workflows, missing files or concept-art substitutions. It adds five images and their hashes to [`docs/screenshots`](screenshots/PROVENANCE.md):

- `orbital-portrait.png`: strict normal SOUND keyboard + entry control.
- `orbital-compact.png`: constrained display.
- `orbital-focus-held-note.png`: live held-note, keyboardless FOCUS (not an idle-black fallback).
- `orbital-oscillator-workbench.png`: real on-device editable oscillator rack.
- `orbital-landscape.png`: landscape Android framebuffer.

Read [`PROVENANCE.md`](screenshots/PROVENANCE.md) for the **exact** source SHA, successful CI run, per-image dimensions and SHA-256. Screenshots may lag source changes until the next fully green current-main run. The original generated concept remains [`design/orbital-ui-reference.png`](../design/orbital-ui-reference.png) and must **never** be described as a running-app screenshot.

Because publisher commits use GitHub Actions' bot token, a screenshot-only child commit may follow the validated source SHA without itself launching a new source CI run. This is documented, not a claim that unrelated untested changes were validated.

## Publishing an immutable installable developer APK

[`publish-debug-prerelease.yml`](../.github/workflows/publish-debug-prerelease.yml) is deliberately **opt-in**. Ordinary source merges, PRs and screenshot bot commits must not create releases. Use one of:

1. Manual `workflow_dispatch` **from main** with a numeric `verified_run_id` for a successful first-party six-job main push.
2. Merge a documentation-only PR onto quiet main with a **squash commit message beginning exactly `[publish-debug-apk]`**. When that main push's six Android jobs pass, the publication workflow evaluates the matching run.

The release publisher re-fetches the source CI run and verifies its `push`/`main` repository identity, Android workflow path, exact head SHA, and all six successful named jobs. It rejects a changed current-main branch unless the only intervening commit is one immediate screenshot-only `github-actions[bot]` child with matching source/run provenance. No unrelated source edit can be smuggled into a released artifact.

It downloads the **one** `orbital-debug-apk` artifact from that exact verified host build, checks ZIP integrity and `AndroidManifest.xml`, computes SHA-256, and creates an immutable tag `orbital-debug-<SOURCE_SHA12>` with asset `ORBITAL-<SOURCE_SHA12>-debug.apk`. Release notes include the full source SHA, the six-job run and the APK SHA-256. Existing tags/releases must match provenance; they are never overwritten or retagged as an update. Check [GitHub Releases](https://github.com/boop-bip80/Android-synth/releases) for the **actual published asset**; a green workflow alone is not proof that an APK asset exists.

These assets are **developer debug prereleases**, installable for controlled testing. They are not signed with a production keystore, not Play Store-ready, and not certification of low-latency sound design on a real phone. Physical-device benchmarks and a production signing/distribution decision are separate release requirements.

## Device-level acceptance still required

- Speaker and wired USB audio, audio focus and interruptions; start/stop/rapid resume, speaker routing and actual sound output.
- Median/p95 latency, underruns and crackling over long 16-voice/effects/oscillator stress sessions on target hardware.
- Physical USB/Bluetooth MIDI attach/detach, note-on/off ownership, pitch bend, sustain/CC and panic.
- Multiple simultaneous touch contacts, stale-finger and cancellation behavior, portrait/landscape/short displays, large font scales, keyboardless FOCUS and return.
- CPU, memory, battery and thermal throttling over long sessions; accessibility with real assistive technology.
- Realistic patches and truthful architecture names; no unsupported claim of calibrated 1:1 emulation of proprietary analog or digital synthesizers.

## Troubleshooting and status reporting

- `SDK location not found`: install Android SDK 35 and set `ANDROID_HOME` or `local.properties`.
- `Gradle requires Java 17`: select JDK 17.
- `No emulator tests found`: inspect the instrumentation test runner and `:app` Android test sources.
- No runner assigned: check repository Actions permissions and hosted-runner capacity; it is not automatically an Android source defect.
- QEMU graphics or ADB loss: use the run's bounded logcat/window/phase/emulator memory diagnostics and distinguish from actual app assertions before retry.

When reporting readiness, always cite the **exact current main commit**, successful six-job workflow run, installed APK SHA-256, matching GitHub Release tag/asset, and screenshot provenance source SHA. If any is missing, state the missing gate explicitly. This project remains alpha until phone-level quality and distribution gates are achieved.
