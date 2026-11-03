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

Only a **completed, successful, first-party `push` run on `main`** of [Android — build, JVM tests and emulator](../.github/workflows/android.yml), for the exact source commit being released, is acceptable. All nine named jobs must exist and conclude `success`:

| Required job | What it establishes |
| --- | --- |
| `Kotlin tests, lint and debug APK` | Core DSP/music and Android JVM tests, lint, debug APK and Android test APK build |
| `API 35 general-0 tests and emulator screenshots` | First AndroidX `numShards=2` instrumentation shard on a fresh API35 emulator; independently records the APK-discovered method manifest and real Gradle XML cases |
| `API 35 general-1 tests and emulator screenshots` | Second disjoint AndroidX instrumentation shard on another fresh emulator; records its own APK-discovered method manifest and real Gradle XML cases |
| `API 35 compact tests and emulator screenshots` | Compact 360dp / large-text composition, real controls, accessibility and image evidence |
| `API 35 stage tests and emulator screenshots` | A genuinely focused app, strict normal keyboard entry, keyboardless FOCUS, live held-note effects, exit, and foreground PNG |
| `API 35 visuals tests and emulator screenshots` | On-device oscillator workbench, landscape, live visual interaction and real framebuffer captures |
| `API 35 compact350 tests and emulator screenshots` | Fresh 360 × 350dp API35 emulator running **exactly four distinct JUnit PASS cases**: OSC far-edge/layer-7 physical touch and piano; all five ENGINE architectures/parameter callback and piano; OSC/page round-trip persistence for ENGINE/VOICE/TONE; physically scrolled preset arrows with real callbacks and playable piano. Missing, duplicate, failed, skipped or extra cases are red; this lane provides diagnostics, not an additional gallery image |
| `API 35 restart tests and emulator screenshots` | Independent fresh emulator performs a **real Android force-stop and cold process restart**, checking a different PID, on-disk STEP patch recall, audio-clock advance, and FOCUS/PLAY transition; both opt-in phases must pass |
| `Verify exact two-shard AndroidX test method union` | Required aggregate gate: both shard manifests and recorded test-case results must come from one source SHA, be disjoint, and cover **every discovered non-STAGE/non-COMPACT350 method exactly once**, with only the two separately tested STEP phases assumed/skipped in shards |

Job conclusions for **the exact head SHA** are the release gate. A generated concept image, passing JVM tests alone, eight of nine green jobs, a stale workflow, an uploaded artifact from a red job, or a screenshot from another commit does not qualify.

Both GENERAL shards precompile Android test binaries **before launching QEMU**, then run real AndroidJUnitRunner/Gradle instrumentation with explicit `numShards=2` ownership and independently captured UTP/JUnit XML. Each emulator enumerates the *full* applicable AndroidX test manifest. A separate required aggregate job verifies the two manifests match, the executed method sets do not overlap, and their union covers every expected method exactly once; extra/missing methods or unexpected skips fail. The two opt-in STEP process-death methods are deliberately assumed only inside these shards and must instead execute successfully in the dedicated fresh RESTART lane, which installs the exact build APKs, prepares a patch, performs a true Android OS force-stop, and verifies recall after a different process ID. Tests additionally exercise native touch ownership, architecture/oscillator selection, audio startup, and lifecycle safety. Host RSS, QEMU/logcat, failure phases and test evidence remain diagnostic artifacts. The instrumented suite count changes as tests are added; never copy a past count into the current README.

The dedicated COMPACT350 gate checks **four exact methods** in `Compact350SoundInstrumentedTest`: `shortLandscapeSoundHasReachableEightLayerEditorAndAnchoredPiano`, `shortLandscapeCanSelectAllFiveArchitecturesWithAnchoredPiano`, `compactSoundPageRetainsSelectionAcrossOscillatorRoundTrips`, and `compactPatchArrowsReachableByRealVerticalScrollAndPianoRemainsPlayable`. Its checker refuses success without exactly these four distinct, non-skipped, non-failing JUnit XML cases. Merely compiling the test APK or seeing a nonempty screenshot does not satisfy it.

Emulator failures require diagnosis, not a green status assumption:

- `Failed to find ColorBuffer`, ADB port 5554 refusal, SDK ZIP bootstrap errors or an external Pixel Launcher ANR may be emulator/runner infrastructure; inspect the log, connected JUnit XML, phase markers, and artifacts.
- A real Compose `AssertionError`, lost note, unresponsive oscillator editor, wrong keybed count or a captured blank screenshot is an application/test failure until fixed and retested.
- A failed-only rerun may be used for demonstrable runner failures while retaining the exact source commit and **all original assertions**. The final nine-job result still must be green.
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

## Publishing a SHA-addressed installable developer APK

[`publish-debug-prerelease.yml`](../.github/workflows/publish-debug-prerelease.yml) is deliberately **opt-in**. Ordinary source merges, PRs and screenshot bot commits must not create releases. Use one of:

1. Manual `workflow_dispatch` **from main** with a numeric `verified_run_id` for a successful first-party nine-job main push.
2. Merge a documentation-only PR onto quiet main with a **squash commit message beginning exactly `[publish-debug-apk]`**. When that main push's nine Android jobs pass, the publication workflow evaluates the matching run.

The release publisher re-fetches the source CI run and verifies its `push`/`main` repository identity, Android workflow path, exact head SHA, and all nine successful named jobs. It rejects a changed current-main branch unless the only intervening commit is one immediate screenshot-only `github-actions[bot]` child with matching source/run provenance. No unrelated source edit can be smuggled into a released artifact.

It downloads the **one** `orbital-debug-apk` artifact from that exact verified host build, checks ZIP integrity and `AndroidManifest.xml`, computes SHA-256, and publishes a *SHA-addressed* tag `orbital-debug-<SOURCE_SHA12>` with asset `ORBITAL-<SOURCE_SHA12>-debug.apk`. Release notes include the full source SHA, the nine-job run and the APK SHA-256. After publication the workflow re-queries the actual GitHub Release and requires the correct tag target, prerelease state, **one uploaded APK of the expected name and byte length**, and GitHub's server-reported `sha256:` asset digest matching the verified local APK. On retries, a missing/corrupt/mismatched asset is a hard failure, never an assumed success. Existing tags and release assets are not overwritten or retagged by this workflow. Check [GitHub Releases](https://github.com/boop-bip80/Android-synth/releases) for the **actual published APK asset**; a green build workflow alone is not proof that one exists.

**GitHub-enforced immutability is a separate repository setting**: enable **Settings → General → Releases → Enable release immutability** *before* publishing if the release and tag must be platform-locked against modification or deletion. Without that setting, SHA-addressed tags and the workflow's no-overwrite rule provide a write-once *automation policy*, not a GitHub-enforced immutable artifact. A repository-settings check is not implied by passing CI.

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

When reporting readiness, always cite the **exact current main commit**, successful nine-job workflow run, installed APK SHA-256, matching GitHub Release tag/asset, and screenshot provenance source SHA. If any is missing, state the missing gate explicitly. This project remains alpha until phone-level quality and distribution gates are achieved.
