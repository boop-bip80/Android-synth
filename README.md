# ORBITAL — Android wavetable synthesizer

**Status (2026-11-02): fourteen development and hardening PRs (#15–#28) are merged to `main`, but the updated build and GitHub-hosted emulator are not yet verified.** The earlier baseline (`e64632f`) was locally reported to pass 89 core JVM tests, 31 Android JVM tests, Android lint and both debug APK assemblies with JDK 17, Android SDK 35 and Gradle 8.11.1. **Those results predate the new changesets; do not count them as current CI results.** Device installability, live interaction, latency and audio performance remain unverified.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Polyphonic portable Kotlin engine with **16 voices**, **five band-limited wavetable families**, oscillator morphing, alias-conscious wavetable mip crossfades, two-millisecond live filter coefficient slewing, five-millisecond pan slewing on legato/voice stealing, improved polyphony transitions and idle rendering, note stealing, mono/poly/legato, portamento and pitch bend | Core JVM tests passed locally; measure pitch, aliasing and CPU on actual devices |
| Tone and movement | ADSR with smoother live edits, resonant filter, LFO/modulation matrix, drive, stereo chorus and delay with ten-millisecond live wet/depth/feedback smoothing and dry-safe wet-gain reset after bypass; touch-controllable wave, cutoff, resonance, movement and FX | Listening tests, stress/sample-rate and actual-device checks |
| Android audio and MIDI | AudioTrack render worker with lifecycle handling; MIDI parsing with per-port note ownership, per-channel sustain pedal (CC64), controller reset/panic and disconnect cleanup | Check real output routes, USB controllers, Bluetooth behavior and round-trip latency |
| Interface | Native Compose sound, play, sequence and FX views; interactive touch/drag orbital morph/movement surface, chromatic keyboard TalkBack actions, expressive XY pad, accessible factory-sequence picker, patch library and save-as dialog | Emulator UI capture plus actual multi-touch, font-scale and orientation playtests |
| Musical tools | On-screen sixteen-step sequencer with velocity/gate/swing/ties/probability and selectable factory sequences; **20 scales**, Euclidean rhythms, core polymetric layering up to 64 steps and seeded scale-constrained melodic walks | Verify timing under load, app lifecycle and MIDI interactions |
| Sounds and storage | **16 factory patches**, **10 selectable factory patterns**, versioned patch model/import/export, local persistence and selectable user patch bank | Exercise migrations and real-device storage flows |
| Quality automation | Baseline: **120 local JVM tests previously passed**; additional JVM/instrumentation regression cases merged but not yet run on CI; **API 35 x86_64 emulator** flow now validates a focused app window and PNG, captures diagnostics/checksums and uploads instrumentation APK | A completed green build plus GitHub Actions emulator run on the updated `main`, with linked artifacts |

These are descriptions of code in this repository, **not claims of passing tests on the latest commit or a finished commercial instrument**. In particular, per-note MPE and guaranteed low-latency playback are not verified.

## November 2 development merges

- [#19](https://github.com/boop-bip80/Android-synth/pull/19) — oscillator anti-alias crossfades and voice filter automation.
- [#16](https://github.com/boop-bip80/Android-synth/pull/16) — click-resistant chorus and delay control smoothing.
- [#17](https://github.com/boop-bip80/Android-synth/pull/17) — polymetric patterns, generative melodic walk and two factory sequences.
- [#15](https://github.com/boop-bip80/Android-synth/pull/15) — per-channel MIDI sustain, controller reset and port cleanup.
- [#20](https://github.com/boop-bip80/Android-synth/pull/20) — interactive orbital expression and accessible chromatic keyboard.
- [#18](https://github.com/boop-bip80/Android-synth/pull/18) — emulator foreground/screenshot checks, evidence artifacts and concurrent patch tests.

## Hardening and app integration

- [#21](https://github.com/boop-bip80/Android-synth/pull/21) — honor the starting pitch of generated melodies.
- [#22](https://github.com/boop-bip80/Android-synth/pull/22) — unify orbital touch and drag gestures.
- [#23](https://github.com/boop-bip80/Android-synth/pull/23) and [#27](https://github.com/boop-bip80/Android-synth/pull/27) — reset stale FX wet gains and wire safe re-enable behavior into the synth engine.
- [#24](https://github.com/boop-bip80/Android-synth/pull/24) — avoid stereo discontinuities during legato and voice stealing.
- [#25](https://github.com/boop-bip80/Android-synth/pull/25), [#26](https://github.com/boop-bip80/Android-synth/pull/26) and [#28](https://github.com/boop-bip80/Android-synth/pull/28) — worker-safe selection of all ten factory sequences, native UI picker and activity integration.

Known limitations: abrupt transition to `FX_AMOUNT=0` still lacks a master-bus crossfade; voice identity is pitch-based rather than fully per-source/channel, so simultaneous same-pitch input from touch, sequencer and multiple MIDI devices needs deeper routing work. The UI currently edits sixteen steps even though the core supports composing longer patterns.

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
- `orbital-debug-android-test-apk` — compiled instrumentation test APK.
- `orbital-api35-emulator-screenshot` — actual UI captured via `adb shell screencap`, separate from the concept image.
- `orbital-api35-emulator-diagnostics` — emulator logcat, activity/window-state dumps, launch/exit status, screenshot checksum and a diagnostic screen capture, when the emulator starts.
- `orbital-host-test-reports` and `orbital-android-test-results` — JVM and instrumentation test reports, when generated; Android lint report is separate.

Artifacts from failed runs can be useful for diagnosis but **do not establish a passing build**. If no green run appears, the APK and emulator verification are **not yet available**. A queued job is neither a pass nor a compile failure.

## Build yourself (optional)

Use **JDK 17**, **Android SDK 35** and **Gradle 8.11.1** (the workflow installs Gradle; no wrapper binary is committed):

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The repository uses AGP 8.9.2 and Kotlin/Compose Compiler 2.1.20. See the [build guide](docs/BUILD.md) for exact configuration. The app supports Android API 26+.

## Next validation milestones

1. Run all host tests, Android lint and APK compilation on the **new merged main**, and inspect failures.
2. Pass API 35 emulator launch, instrumentation and screenshot checks with evidence.
3. Profile real Android phones for audio underruns, latency, battery, thermal behavior and MIDI controller compatibility.
4. Complete accessibility/interaction playtests and release signing before claiming production readiness.

## License

No license has been selected yet. The code and generated visual design are not offered under an assumed open-source license.
