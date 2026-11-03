# ORBITAL — Android multi-engine synthesizer

**Status (2026-11-02): ORBITAL v0.2.0 alpha.** The touch-first Android app now has a refreshed luminous interface, **five sound-generation architectures**, 16 polyphonic voices, 25 factory patches, MIDI input, live performance arpeggiation, sequencing and stereo effects. **Latest verified combined build:** [GitHub Actions run #41569270066](https://github.com/boop-bip80/Android-synth/actions/runs/41569270066) from [commit `91853a0`](https://github.com/boop-bip80/Android-synth/commit/91853a04b00bdb6033e12ae9e67a2dd2944a1477). Physical-device latency, thermal benchmarks, Play signing and circuit/ROM-exact emulation remain open.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Install the new APK and inspect actual Android screenshots

- **v0.2.0 alpha APK:** [`orbital-debug-apk` download](https://github.com/boop-bip80/Android-synth/actions/runs/41569270066/artifacts/13200811397) — debug-signed, installable Android package. Not a Play Store release. GitHub Actions artifacts have limited retention; if the direct artifact link expires, open the [newest successful `main` workflow](https://github.com/boop-bip80/Android-synth/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess).
- **Real emulator screenshots:** [`orbital-api35-general-emulator-screenshot` download](https://github.com/boop-bip80/Android-synth/actions/runs/41569270066/artifacts/13201485996) — standard Pixel portrait, compact 360 dp / 1.3× font, and landscape; captured from the **running** APK by ADB, never synthesized from the concept. The independent real-display compact-height lane also publishes [`orbital-api35-compact-emulator-screenshot`](https://github.com/boop-bip80/Android-synth/actions/runs/41569270066/artifacts/13200153571) after its layout checks pass.
- **Test report and emulator diagnostics:** [full successful workflow](https://github.com/boop-bip80/Android-synth/actions/runs/41569270066). Screenshot geometry and SHA provenance are checked automatically.

### Five playable synthesis architectures

| Engine mode | Implementation | Fidelity boundary |
| --- | --- | --- |
| ORBITAL WT | Original five-family mip-aware wavetable bank, morphing and optional triple unison | Native digital wavetable synth |
| LADDER | Nonlinear four-pole virtual-analog ladder with controllable feedback/drive | Inspired by Model-D-style topology, not calibrated 1:1 |
| SEM | 12 dB state-variable lowpass/notch/highpass multimode analog filtering | Inspired by SEM-style topology, not calibrated 1:1 |
| SIX-OP FM | Six operators, eight FM/phase-modulation routings and feedback | DX-series principles, not a bit-identical DX7 or ROM recreation |
| PHASE DISTORTION | Six phase-distortion shapes and tone controls | CZ-series principles, not a chip/ROM-identical model |

The SOUND page includes a prominent **Engine Architecture** selector and **CHARACTER** timbre macro. `WAVE` selects FM routing or phase-distortion shape in the digital modes. Nine additional architecture-oriented patches complement the original 16; patch import/export preserves older files by resetting missing engine keys to the original wavetable mode.

### Performance and display

- New dimensional holographic orb, multicolour parameter-linked harmonic traces, layered lighting, upgraded neon knobs, translucent functional panel layout, and a touch keyboard. The original concept image above remains in the README **as reference artwork**; the real screenshot link is explicitly separate.
- A real worker-owned arpeggiator supports UP/DOWN/UP-DOWN/AS-PLAYED/RANDOM/CHORD, 1–16 beat subdivisions, latch, swing, ratchets, octave expansion, gate and probability. The PLAY page exposes its primary controls; touch/MIDI note ownership stays isolated from the sequencer.
- GitHub Actions builds APKs, checks JVM tests/lint and runs two required fresh API35 emulator lanes (35+ Compose tests and two physical-display 350dp keyboard/tab end-to-end checks), collecting genuine normal, compact and landscape captures. Switching architecture uses an 8 ms de-click bridge rather than true concurrent-engine crossfading; high-register nonlinear/FM aliasing, physical Android audio underruns, hardware MIDI latency and thermals still require device measurement.

**Upgrade PRs:** [Visual #61](https://github.com/boop-bip80/Android-synth/pull/61) · [UI #67](https://github.com/boop-bip80/Android-synth/pull/67) · [Analog #62](https://github.com/boop-bip80/Android-synth/pull/62) · [Digital #65](https://github.com/boop-bip80/Android-synth/pull/65) · [Arp core #64](https://github.com/boop-bip80/Android-synth/pull/64) · [Arp audio #68](https://github.com/boop-bip80/Android-synth/pull/68) · [Arp UI #69](https://github.com/boop-bip80/Android-synth/pull/69) · [CI proof #60/#63](https://github.com/boop-bip80/Android-synth/pull/63) · [Runtime integration #66](https://github.com/boop-bip80/Android-synth/pull/66).

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Portable Kotlin engine with **16 voices**, **five selectable synthesis architectures** and five band-limited wavetable families, morphing and alias-conscious mip crossfades, slew-limited filter/pan controls, filter-preserving voice-stealing release tails, mono/poly/legato, portamento and pitch bend. Three-oscillator unison supports up to ±30 cents detune in the wavetable and analog modes (not yet FM/phase distortion). Initialization avoids redundant wavetable trigonometry; held legato velocity and sustain changes now slew smoothly. | Measure device CPU, tuning, aliasing, cold-start and stress stability |
| Tone and movement | ADSR, resonant filter, LFO/modulation, drive, stereo chorus, delay and **eight-line stereo FDN reverb**. The live wet master bus supports a reversible 10 ms FX bypass; delay-time transitions use a 10 ms dual-tap crossfade. | Listening tests, hardware CPU/thermal and extended-session monitoring |
| Android audio and MIDI | AudioTrack worker with focus/lifecycle handling, MIDI source/port identity, CC64 sustain, CC66 sostenuto, CC120/121/123 semantics, hot-plug generation guards and transient audio-focus resume without stale-stop focus abandonment. | Test real speaker, wired/USB/Bluetooth routes, MIDI controllers, interruptions and latency |
| Interface | Native Compose sound/play/sequence/FX views based on committed artwork, engine architecture selector, holographic interactive orbital surface, **25 accessible chromatic notes**, octave buttons, XY pad, presets, unison/detune and per-note 1–64 step editor. Compact-width/large-font portrait layout moves patch controls outside the orbit, reduces orbit size and provides an expandable modulation chart. Extremely short (<500dp) viewports use paged live sound and FX racks so the touch keyboard remains available. | Physical touch, accessibility/TalkBack, landscape and tablet playtests |
| Musical tools | Source-aware sample-addressed performance arpeggiator (ratchets/probability/latch/swing) and sample-addressed sequencer with worker-safe pattern loading, resizing, per-step note editing and muting; 12 factory patterns (including 32-step *Eclipse Shift* and 64-step *Deep Orbit*); swing, 20 scales, Euclidean patterns, polymetric layers and seeded melodic walks. | Verify timing under load, rapid edits and simultaneous MIDI/touch |
| Sounds and storage | **25 factory sound patches**, **12 factory patterns**, versioned patch import/export, app-private sequence storage and library. **V2 sequence saves preserve muted source notes and their enable-state**, read V1 data, and keep phase when editing. Delayed worker mute toggles are reconciled with acknowledgement tickets so rapid taps and saves retain the latest user intent. | On-device migration, storage corruption, rapid edit and restart playtests |
| Quality automation | Pinned JDK17/SDK35 Gradle builds, JVM tests, Android lint, app and instrumented APKs, API35 general emulator **35/35 instrumentation tests** plus **2 real 360×350dp keyboard/tab E2E cases**; actual normal, compact 360dp / 1.3× font and landscape screenshots. Current green test results, source commit, and APK evidence are linked at the top; the previous v0.1 baseline passed **34/34 instrumentation tests**. Main builds retain active validation, require build artifacts, and recover narrowly from external Pixel Launcher ANR overlays. | Keep verifying future app-code changes; emulator data cannot replace real-device measurements |

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

## Build yourself (optional)

Use **JDK 17**, **Android SDK 35** and **Gradle 8.11.1** (the workflow installs Gradle; no wrapper binary is committed):

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The repository uses AGP 8.9.2 and Kotlin/Compose Compiler 2.1.20. See the [build guide](docs/BUILD.md) for exact configuration. The app supports Android API 26+.

## Next validation milestones

1. Preserve the green CI baseline on every future application-code change; visually inspect compact/large-font screens and landscape/touch accessibility.
2. Profile real Android phones for reverb/unison CPU, audio underruns, latency, battery, thermals and MIDI controller compatibility.
3. Add undo/redo and deeper sound-design controls without compromising touch ergonomics; profile render-thread performance and held waveform-morph automation.
4. Compare synthesizer outputs against legally obtained hardware reference recordings, measure high-register aliasing, optimize/oversample the nonlinear and FM paths where justified, and benchmark sustained 16-voice CPU/thermal load before claiming model-accurate sound.
5. Complete accessibility/interaction playtests and reproducible release signing before claiming production readiness.

## License

No license has been selected yet. The code and generated visual design are not offered under an assumed open-source license.
