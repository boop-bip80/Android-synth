# ORBITAL — Android wavetable synthesizer

**Status (2026-11-02): local Android build and test compilation passed; GitHub-hosted emulator execution is still unverified.** The local run passed 89 core JVM tests, 31 Android JVM tests, Android lint and both debug APK assemblies with JDK 17, Android SDK 35 and Gradle 8.11.1. This does not establish on-device installability, interaction correctness, latency or audio performance. The GitHub Actions artifact is available only once a workflow run actually uploads it.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Polyphonic portable Kotlin engine with **16 voices**, **five band-limited wavetable families**, oscillator morphing, improved polyphony transitions and idle rendering, note stealing, mono/poly/legato, portamento and pitch bend | Core JVM tests passed locally; measure pitch, aliasing and CPU on actual devices |
| Tone and movement | ADSR with smoother live edits, resonant filter, LFO/modulation matrix, drive, stereo chorus and delay; touch-controllable wave, cutoff, resonance, movement and FX | Listening tests, stress/sample-rate and actual-device checks |
| Android audio and MIDI | AudioTrack render worker with lifecycle handling; MIDI parsing with per-port note ownership and disconnect cleanup | Check real output routes, USB controllers, Bluetooth behavior and round-trip latency |
| Interface | Native Compose sound, play, sequence and FX views; orbital display, accessible keyboard, expressive XY pad, patch library and save-as dialog | Emulator UI capture plus actual multi-touch, font-scale and orientation playtests |
| Musical tools | Sixteen-step sequencer with velocity/gate/swing/ties/probability; **20 scales**, Euclidean rhythm composition and pattern layering | Verify timing under load, app lifecycle and MIDI interactions |
| Sounds and storage | **16 factory patches**, **8 factory patterns**, versioned patch model/import/export, local persistence and selectable user patch bank | Exercise migrations and real-device storage flows |
| Quality automation | **120 local JVM tests passed**, Android lint and debug/instrumentation APK compilation passed; **API 35 x86_64 emulator** instrumentation, launch check and screenshot capture workflow configured | A completed green GitHub Actions emulator run and linked artifacts |

These are descriptions of code in this repository, **not claims of a finished commercial instrument**. In particular, per-note MPE and guaranteed low-latency playback are not verified.

## Project layout

- [Portable synthesis and musical core](core/src/main/kotlin/com/orbital/synth/core)
- [Android application](app/src/main/java/com/orbital/synth)
- [Original concept image](design/orbital-ui-reference.png)
- [Architecture contract](docs/ARCHITECTURE.md)
- [Verification gates](docs/QUALITY_GATES.md)
- [Build and CI instructions](docs/BUILD.md)

## Get the debug APK and actual UI screenshot

Use the [Android CI workflow](.github/workflows/android.yml) from the repository's [Actions tab](https://github.com/boop-bip80/Android-synth/actions). Once a run is **green**, its downloadable artifacts include:

- `orbital-debug-apk` — unsigned-for-Play debug install package, signed with the Android debug key.
- `orbital-api35-emulator-screenshot` — actual UI captured via `adb shell screencap`, separate from the concept image.
- `orbital-api35-emulator-diagnostics` — emulator logcat, activity-state dump, launch result and a diagnostic screen capture, when the emulator starts.
- `orbital-host-test-reports` and `orbital-android-test-results` — JVM and instrumentation test reports, when generated; Android lint report is separate.

Artifacts from failed runs can be useful for diagnosis but **do not establish a passing build**. If no green run appears, the APK and emulator verification are **not yet available**. A queued job is neither a pass nor a compile failure.

## Build yourself (optional)

Use **JDK 17**, **Android SDK 35** and **Gradle 8.11.1** (the workflow installs Gradle; no wrapper binary is committed):

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The repository uses AGP 8.9.2 and Kotlin/Compose Compiler 2.1.20. See the [build guide](docs/BUILD.md) for exact configuration. The app supports Android API 26+.

## Next validation milestones

1. Confirm the locally passing host build on a GitHub Actions runner for this commit.
2. Pass API 35 emulator launch, instrumentation and screenshot checks with evidence.
3. Profile real Android phones for audio underruns, latency, battery, thermal behavior and MIDI controller compatibility.
4. Complete accessibility/interaction playtests and release signing before claiming production readiness.

## License

No license has been selected yet. The code and generated visual design are not offered under an assumed open-source license.
