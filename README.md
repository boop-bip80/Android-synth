# ORBITAL — Android wavetable synthesizer

**Status (2026-11-02): development PRs #40–#45 are merged into `main`, adding unison, stereo reverb, resilient MIDI, editable sequence voices, saved user patterns and stronger Android CI.** The combined source, including app integration, **passed a local build** with JDK 17, Android SDK 35 and Gradle 8.11.1: **150 core JVM tests, 57 Android JVM tests, Android lint, debug APK and instrumentation APK assembly**. This is host-side verification, not a completed GitHub emulator run. Earlier hosted workflows exposed an obsolete `sdkmanager tools` default in the SDK setup action; the workflow now explicitly requests `platform-tools`. A **green API 35 emulator run and real-device measurements remain required** before calling this production-ready.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Portable Kotlin engine with **16 voices**, **five band-limited wavetable families**, oscillator morphing, alias-conscious mip crossfades, slew-limited filter/pan automation, voice stealing, mono/poly/legato, portamento and pitch bend. **Three-oscillator unison** has neutral defaults, up to ±30 cents detune and 10 ms slews; preallocated source/channel/note ownership keeps MIDI, touch and sequencer notes independent. | Profile aliasing, CPU, stereo imaging and tuning on real devices |
| Tone and movement | ADSR, resonant filter, LFO/modulation, drive, stereo chorus, delay and an **eight-line stereo FDN reverb** with diffusion, room decay, damping and width. The reverb is wired into the actual master wet path, behind the **10 ms reversible FX bypass**, with tail reset at exact dry. | Listening, long-session stability, CPU and thermal profiling still required |
| Android audio and MIDI | AudioTrack worker with focus/lifecycle handling; MIDI per-port source IDs, per-channel sustain (CC64), CC123 sustained note release, CC120 immediate panic and disconnect cleanup. **Per-device open epochs** reject stale hot-plug callbacks after device-ID reuse. | Confirm real output routes, USB/Bluetooth controllers, background behavior and latency |
| Interface | Native Compose sound/play/sequence/FX views based on the committed generated concept: interactive orbital surface, chromatic accessible keyboard, XY pad, patch library, **unison/detune controls**, responsive **1–64-step grid** and a **per-note step inspector** for pitch, velocity, gate, chance, ties and chord voices. The keyboard stays anchored while the editor scrolls. | Run the committed Compose instrumentation and inspect real emulator screenshots and touch/TalkBack behavior |
| Musical tools | Sample-addressed sequencer with worker-safe pattern loading, resizing, per-step note editing and muting; 12 factory patterns (including 32-step *Eclipse Shift* and 64-step *Deep Orbit*); swing, 20 scales, Euclidean patterns, polymetric layers and seeded melodic walks. | Verify timing under load, rapid edits and simultaneous MIDI/touch |
| Sounds and storage | **16 factory sound patches**, **12 factory sequencer patterns**, versioned patch import/export, local preset bank and save-as UI. User-created sequences have a validated, versioned codec, atomic app-private storage and a selectable library; Save Sequence captures the current audible grid. | Exercise on-device migration, user-file corruption and save/load flows |
| Quality automation | **207 JVM tests passed locally** (150 core, 57 Android); lint and both debug APK assemblies also passed. The **API 35 x86_64 GitHub emulator workflow** compiles instrumentation, exercises repeated lifecycle/audio tests, captures normal and 360 dp / 1.3× font screenshots, and retains bounded diagnostics and provenance. | Await a completed hosted `main` emulator run; host compilation alone cannot validate instrumentation or screenshots |

These are descriptions of code and a local host-build result, **not claims of a green GitHub emulator run or a finished commercial instrument**. In particular, per-note MPE and guaranteed low-latency playback are not verified.

## November 2 deeper development: PRs #40–#45

- [#40](https://github.com/boop-bip80/Android-synth/pull/40) — MIDI device-reconnect epochs and sustain-aware channel-mode handling.
- [#41](https://github.com/boop-bip80/Android-synth/pull/41) — three-oscillator unison/detune with smooth live changes.
- [#42](https://github.com/boop-bip80/Android-synth/pull/42) — eight-line stereo FDN reverb and corrected delay-glide test setup.
- [#43](https://github.com/boop-bip80/Android-synth/pull/43) — strict, versioned user-sequence codec and atomic storage; patch compatibility.
- [#44](https://github.com/boop-bip80/Android-synth/pull/44) — API 35 instrumentation hardening, compact screenshot and SDK setup fix.
- [#45](https://github.com/boop-bip80/Android-synth/pull/45) — native step-note inspector, save-sequence dialog and accessible controls.

MainActivity integration connects the new UI editor to the audio worker, loads saved sequences off the UI thread and preserves factory sequences. The reverb is in the real audio bus; neither is merely an isolated demo class.

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

**Remaining limitations:** Save Sequence stores the *audible* pattern—muted steps become rests rather than retaining hidden muted notes. External MIDI timing is handled at audio-block boundaries; per-note MPE, independent reverb parameter panels, release signing, deterministic low-latency guarantees and real-device validation are not implemented or verified.

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
- `orbital-api35-emulator-screenshot` — actual UI captured via `adb shell screencap` in default Pixel 6 and compact 360 dp / 1.3× font configurations, separate from the concept image.
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

1. Pass the API 35 emulator launch, instrumentation and both screenshot checks on the **new merged main** and inspect downloadable evidence.
2. Profile real Android phones for reverb/unison CPU, audio underruns, latency, battery, thermals and MIDI controller compatibility.
3. Preserve muted-note edit layers across sequence saves; add undo/redo and dedicated sound-design controls without compromising touch ergonomics.
4. Complete accessibility/interaction playtests and reproducible release signing before claiming production readiness.

## License

No license has been selected yet. The code and generated visual design are not offered under an assumed open-source license.
