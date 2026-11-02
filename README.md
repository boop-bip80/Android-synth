# ORBITAL — Android wavetable synthesizer

**Status (2026-11-02): twenty-four development PRs (#15–#38) are merged into `main`, including source-isolated MIDI/touch/sequence notes, the 1–64-step sequencer, native length controls, and smooth FX bypass.** The current GitHub Actions build is **queued without an assigned runner**; its JVM tests, Android lint, APK assembly and API 35 emulator have not executed. The earlier baseline (`e64632f`) was locally reported to pass 89 core JVM tests, 31 Android JVM tests, Android lint and both debug APK assemblies with JDK 17, Android SDK 35 and Gradle 8.11.1. **Those results predate the newer changes and must not be treated as verification of current `main`.** Real-device installability, audio latency, controller interoperability and performance remain unverified.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Portable Kotlin engine with **16 voices**, **five band-limited wavetable families**, oscillator morphing, alias-conscious mip crossfades, slew-limited filter/pan modulation, voice stealing, mono/poly/legato, portamento and pitch bend. New bounded, preallocated source/channel/note ownership preserves independent holds across MIDI ports, keyboard and sequencer—even when voices are stolen. | New source-ownership regressions are in source; run current JVM tests and measure CPU/pitch/aliasing on devices |
| Tone and movement | ADSR, resonant filter, LFO/modulation, drive, stereo chorus and delay with smoothed live controls. The master bus now uses a **10 ms reversible FX bypass crossfade**, retaining the wet path during fade-out and clearing echo state at exact dry. | End-to-end FX comparison tests are in source; listening, load and sample-rate testing still required |
| Android audio and MIDI | AudioTrack worker with focus/lifecycle handling; MIDI per-port source IDs and per-channel sustain (CC64), reset/panic and disconnect cleanup. Touch (`-1`), sequencer (`-2`) and positive MIDI port IDs reach the same source-aware synth engine without cross-releasing notes. | Confirm real output routes, USB/Bluetooth controllers, background behavior and latency |
| Interface | Native Compose sound/play/sequence/FX views based on the committed generated concept: interactive orbital morph/motion surface, chromatic accessible keyboard, XY pad, patch library, factory-sequence picker, responsive **1–64-step grid**, 16/32/64 length presets and ±1 controls. The keyboard stays anchored while the editor scrolls. | Compose instrumentation cases are in source; actual emulator screenshot and real touch/TalkBack/font/orientation testing pending |
| Musical tools | Sample-addressed sequencer with worker-safe **1–64-step** pattern loading, resizing, per-step notes and muting; 12 factory patterns (including 32-step *Eclipse Shift* and 64-step *Deep Orbit*); velocity/gates/ties/probability/swing; 20 scales, Euclidean patterns, polymetric layering and seeded melodic walks. Full per-note editing is currently available to code, while on-screen cells toggle steps. | Verify timing under load, app lifecycle, rapid edits and concurrent touch/MIDI |
| Sounds and storage | **16 factory sound patches**, **12 factory sequencer patterns**, versioned patch model/import/export, local preset persistence, selectable user patch bank and save-as UI. | Exercise migration, file access and saved-user-patch flows on Android |
| Quality automation | Prior baseline: **120 local JVM tests** passed at `e64632f`. Numerous further core/JVM/Compose/instrumentation regressions are committed but not yet run. The **API 35 x86_64 GitHub emulator workflow** compiles instrumentation, exercises repeated resume cycles and Android audio services, checks focused window/PNG and retains bounded system diagnostics/artifacts. | Await a completed hosted run of the latest `main` and inspect test reports, emulator image, APK and failures |

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

## Deeper development: PRs #29–#38

- [#29](https://github.com/boop-bip80/Android-synth/pull/29) and [#38](https://github.com/boop-bip80/Android-synth/pull/38) — tested 10 ms FX crossfade utility and its integration into the master audio path, including rapid-toggle behavior.
- [#30](https://github.com/boop-bip80/Android-synth/pull/30) and [#37](https://github.com/boop-bip80/Android-synth/pull/37) — responsive scrollable long-pattern grid, fixed keyboard and accessible 1–64-step length controls.
- [#31](https://github.com/boop-bip80/Android-synth/pull/31), [#34](https://github.com/boop-bip80/Android-synth/pull/34) and [#36](https://github.com/boop-bip80/Android-synth/pull/36) — MIDI port source identity, source-owned synth voices and wiring into the real Android touch/MIDI/sequencer pathways.
- [#33](https://github.com/boop-bip80/Android-synth/pull/33) and [#35](https://github.com/boop-bip80/Android-synth/pull/35) — 1–64-step worker-safe editing, 32/64-step polymetric factory loops, stale-edit guards and lifecycle tests.
- [#32](https://github.com/boop-bip80/Android-synth/pull/32) — Android audio device/lifecycle instrumentation and time-bounded AudioFlinger/policy/MIDI diagnostics.

**Remaining limitations:** The UI currently toggles steps but does not provide per-note velocity/gate/probability editing or persistent user sequence storage; external MIDI timing is processed at audio-block boundaries; per-note MPE, release signing, deterministic low-latency guarantees and real-device validation are not implemented or verified.

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
- `orbital-api35-emulator-diagnostics` — emulator logcat, activity/window dumps, launch/exit status, screenshot checksum, bounded AudioFlinger/policy/MIDI/memory/display diagnostics and a diagnostic screen capture, when the emulator starts.
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
