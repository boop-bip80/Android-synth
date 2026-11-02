# ORBITAL — Android wavetable synthesizer

**Status (2026-11-02): ORBITAL's six-agent hardening work is merged through PR #59 on `main`.** The Kotlin/Jetpack Compose synth now has 16-voice wavetable synthesis, effects, MIDI sustain/sostenuto, resilient audio-focus recovery, phase-stable and losslessly saved sequences, and responsive orbital controls. A [fully green PR #55 emulator run](https://github.com/boop-bip80/Android-synth/actions/runs/41512839802) passed host tests, lint, both APK builds, **34/34 Android instrumentation tests** and normal/compact screenshot capture. The [CI recovery run](https://github.com/boop-bip80/Android-synth/actions/runs/41512794970) was also green. These verify those specific commits; a new combined `main` run is required after integration. Physical-device MIDI/audio latency, thermal profiling, release signing and licensing remain open.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Portable Kotlin engine with **16 voices**, **five band-limited wavetable families**, morphing and alias-conscious mip crossfades, slew-limited filter/pan controls, filter-preserving voice-stealing release tails, mono/poly/legato, portamento and pitch bend. Three-oscillator unison supports up to ±30 cents detune. Initialization avoids redundant wavetable trigonometry; held legato velocity and sustain changes now slew smoothly. | Measure device CPU, tuning, aliasing, cold-start and stress stability |
| Tone and movement | ADSR, resonant filter, LFO/modulation, drive, stereo chorus, delay and **eight-line stereo FDN reverb**. The live wet master bus supports a reversible 10 ms FX bypass; delay-time transitions use a 10 ms dual-tap crossfade. | Listening tests, hardware CPU/thermal and extended-session monitoring |
| Android audio and MIDI | AudioTrack worker with focus/lifecycle handling, MIDI source/port identity, CC64 sustain, CC66 sostenuto, CC120/121/123 semantics, hot-plug generation guards and transient audio-focus resume without stale-stop focus abandonment. | Test real speaker, wired/USB/Bluetooth routes, MIDI controllers, interruptions and latency |
| Interface | Native Compose sound/play/sequence/FX views based on committed artwork, interactive orbital surface, **25 accessible chromatic notes**, octave buttons, XY pad, presets, unison/detune and per-note 1–64 step editor. Compact/large-font layout moves patch controls outside the orbit, reduces orbit size and provides an expandable modulation chart. | Physical touch, accessibility/TalkBack, landscape and tablet playtests |
| Musical tools | Sample-addressed sequencer with worker-safe pattern loading, resizing, per-step note editing and muting; 12 factory patterns (including 32-step *Eclipse Shift* and 64-step *Deep Orbit*); swing, 20 scales, Euclidean patterns, polymetric layers and seeded melodic walks. | Verify timing under load, rapid edits and simultaneous MIDI/touch |
| Sounds and storage | **16 factory sound patches**, **12 factory patterns**, versioned patch import/export, app-private sequence storage and library. **V2 sequence saves preserve muted source notes and their enable-state**, read V1 data, and keep phase when editing. Delayed worker mute toggles are reconciled with acknowledgement tickets so rapid taps and saves retain the latest user intent. | On-device migration, storage corruption, rapid edit and restart playtests |
| Quality automation | Pinned JDK17/SDK35 Gradle builds, JVM tests, Android lint, app and instrumented APKs, API35 emulator tests and real normal/360dp 1.3× screenshots. [PR #55's green run](https://github.com/boop-bip80/Android-synth/actions/runs/41512839802) completed **34/34 instrumentation tests**. Main builds retain active validation, require build artifacts, and recover narrowly from external Pixel Launcher ANR overlays. | Verify latest merged `main` SHA; emulator data cannot replace real-device measurements |

These are descriptions of implementation and tested CI results, **not claims of a finished commercial instrument**. In particular, per-note MPE and guaranteed low-latency playback are not verified.

## November 2 deeper development: PRs #40–#46

- [#40](https://github.com/boop-bip80/Android-synth/pull/40) — MIDI device-reconnect epochs and sustain-aware channel-mode handling.
- [#41](https://github.com/boop-bip80/Android-synth/pull/41) — three-oscillator unison/detune with smooth live changes.
- [#42](https://github.com/boop-bip80/Android-synth/pull/42) — eight-line stereo FDN reverb and corrected delay-glide test setup.
- [#43](https://github.com/boop-bip80/Android-synth/pull/43) — strict, versioned user-sequence codec and atomic storage; patch compatibility.
- [#44](https://github.com/boop-bip80/Android-synth/pull/44) — API 35 instrumentation hardening, compact screenshot and SDK setup fix.
- [#45](https://github.com/boop-bip80/Android-synth/pull/45) — native step-note inspector, save-sequence dialog and accessible controls.
- [#46](https://github.com/boop-bip80/Android-synth/pull/46) — integrate DSP effects, step editing and sequence storage into the running Android app.

MainActivity integration connects the new UI editor to the audio worker, loads saved sequences off the UI thread and preserves factory sequences. The reverb is in the real audio bus; neither is merely an isolated demo class.

## November 2 six-agent hardening: PRs #47–#59

- [#47](https://github.com/boop-bip80/Android-synth/pull/47) — reinstall the app after emulator instrumentation and wait for a truly drawn, focused surface; [first complete green emulator run](https://github.com/boop-bip80/Android-synth/actions/runs/41508613821).
- [#48](https://github.com/boop-bip80/Android-synth/pull/48) — expose the full 25-note keyboard and octave controls to accessibility.
- [#49](https://github.com/boop-bip80/Android-synth/pull/49) — preserve filter state, pitch and timbre through voice-stealing tails.
- [#50](https://github.com/boop-bip80/Android-synth/pull/50) — delay-time crossfade with continuity tests.
- [#51](https://github.com/boop-bip80/Android-synth/pull/51) — MIDI sostenuto and controller-interaction safeguards.
- [#52](https://github.com/boop-bip80/Android-synth/pull/52) — lossless V2 sequence persistence with V1 compatibility and phase-stable edits.
- [#53](https://github.com/boop-bip80/Android-synth/pull/53) — faster, numerically identical wavetable generation.
- [#54](https://github.com/boop-bip80/Android-synth/pull/54) — equivalent orb geometry with less repeated draw computation.
- [#55](https://github.com/boop-bip80/Android-synth/pull/55) — responsive large-font preset/orbit layout and measured modulation-toggle visibility ([34/34 emulator tests green](https://github.com/boop-bip80/Android-synth/actions/runs/41512839802)).
- [#56](https://github.com/boop-bip80/Android-synth/pull/56) — CI artifact enforcement, active-main-run preservation and strict Pixel Launcher system-dialog recovery.
- [#57](https://github.com/boop-bip80/Android-synth/pull/57) — smooth legato velocity and sustain retargeting.
- [#58](https://github.com/boop-bip80/Android-synth/pull/58) — worker-acknowledged rapid mute toggles and consistent sequence saves.
- [#59](https://github.com/boop-bip80/Android-synth/pull/59) — retain audio focus through transient loss, resume correctly and serialize stop/start claims.

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

**Remaining limitations:** External MIDI event timing is audio-block-based. Per-note MPE, source-aware pitch-bend reset after MIDI unplug, WAV export, undo/redo, dedicated reverb parameter panels, release signing, deterministic low-latency guarantees and real-device validation are not implemented or verified.

## Project layout

- [Portable synthesis and musical core](core/src/main/kotlin/com/orbital/synth/core)
- [Android application](app/src/main/java/com/orbital/synth)
- [Original concept image](design/orbital-ui-reference.png)
- [Architecture contract](docs/ARCHITECTURE.md)
- [Verification gates](docs/QUALITY_GATES.md)
- [Build and CI instructions](docs/BUILD.md)

## Get the debug APK and actual UI screenshot

Use the [Android CI workflow](.github/workflows/android.yml) from the repository's [Actions tab](https://github.com/boop-bip80/Android-synth/actions). For example, [PR #55's fully green run](https://github.com/boop-bip80/Android-synth/actions/runs/41512839802) has real UI screenshots, APKs and instrumentation results. Select a green run at the latest `main` SHA for the latest code. Downloadable artifacts include:

- `orbital-debug-apk` — unsigned-for-Play debug install package, signed with the Android debug key.
- `orbital-debug-android-test-apk` — compiled instrumentation test APK.
- `orbital-api35-emulator-screenshot` — actual UI captured via `adb shell screencap` in default Pixel 6 and compact 360 dp / 1.3× font configurations, separate from the concept image.
- `orbital-api35-emulator-diagnostics` — emulator logcat, activity/window dumps, launch/exit status, screenshot checksum, bounded AudioFlinger/policy/MIDI/memory/display diagnostics and a diagnostic screen capture, when the emulator starts.
- `orbital-host-test-reports` and `orbital-android-test-results` — JVM and instrumentation test reports, when generated; Android lint report is separate.

Artifacts from failed runs can help diagnosis but **do not establish a passing build**. Queued, failed and cancelled runs do not verify their commits; a passing earlier run does not automatically verify later merges.

## Build yourself (optional)

Use **JDK 17**, **Android SDK 35** and **Gradle 8.11.1** (the workflow installs Gradle; no wrapper binary is committed):

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The repository uses AGP 8.9.2 and Kotlin/Compose Compiler 2.1.20. See the [build guide](docs/BUILD.md) for exact configuration. The app supports Android API 26+.

## Next validation milestones

1. Confirm API 35 emulator instrumentation and both screenshots on the **latest merged main** and visually inspect compact/large-font screens.
2. Profile real Android phones for reverb/unison CPU, audio underruns, latency, battery, thermals and MIDI controller compatibility.
3. Add undo/redo and deeper sound-design controls without compromising touch ergonomics; profile render-thread performance and held waveform-morph automation.
4. Complete accessibility/interaction playtests and reproducible release signing before claiming production readiness.

## License

No license has been selected yet. The code and generated visual design are not offered under an assumed open-source license.
