# ORBITAL — Android multi-engine synthesizer

**Status (2026-11-03): active alpha development, not a production-ready instrument.** Five selectable synthesis architectures, 16 voices, 25 factory patches, eight editable oscillator layers, eight programmable LFOs, 64 modulation routes, MIDI, arpeggiation, sequencing, and six user-editable stereo FX slots are in source. Native touch controls now drive the live FX processors, and a dedicated FOCUS option hides the keyboard and controls for black-canvas performance. Live PCM feeds the visual renderer. **Latest fully verified live-FX integration build:** [GitHub Actions run #41591627449](https://github.com/boop-bip80/Android-synth/actions/runs/41591627449) (Kotlin/JVM/lint/APK plus both API35 emulator lanes green). See the [newest successful main workflow](https://github.com/boop-bip80/Android-synth/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess) for later builds. Physical-device latency, thermal performance, Play signing, model-accurate analog emulation and visual parity with the original concept **are not yet verified**.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Install the latest tested APK and compare real Android screenshots

- **Installable debug APK:** [download `orbital-debug-apk`](https://github.com/boop-bip80/Android-synth/actions/runs/41591627449/artifacts/13210696953) — debug-signed; this fully tested build includes the real PCM-reactive immersive stage, live touch-to-FX workbench integration, editable oscillators, modulation, V4 FX patch saving and keyboardless FOCUS mode. Not a signed Play Store release.
- **Real Android 35 screenshots:** [standard portrait/compact-font/landscape captures](https://github.com/boop-bip80/Android-synth/actions/runs/41591627449/artifacts/13210228085) and [independent compact-height capture](https://github.com/boop-bip80/Android-synth/actions/runs/41591627449/artifacts/13210612233). These are emulator framebuffers from the actual APK, **not** the concept art above. A separate held-note live-performance framebuffer proof is under validation; these stage screenshots should not be misrepresented as that proof.
- **Verification and newer downloads:** [full passing run](https://github.com/boop-bip80/Android-synth/actions/runs/41591627449) and [newest successful `main` build](https://github.com/boop-bip80/Android-synth/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess). Artifact links have limited retention.

### November 3: layered sound design and keyboardless performance

- **Additive voice layers:** eight independently toggleable, tunable wavetable oscillator slots per voice, plus the five existing architecture choices; layer wave, level, coarse/fine tuning and morph in the native SOUND rack. This is polyphonic DSP, not a mock UI.
- **Live modulation:** eight shape/sync/retrigger-configurable LFOs, 64 editable cables and touch X/Y/pressure modulation sources. MORPH, PITCH, CUTOFF, RESONANCE, AMP and PAN destinations are audible in the real render path; unused destinations are marked reserved. V4 patches retain V3 oscillator/LFO/route/tempo fields and additionally persist FX order, type and parameters, while decoding legacy V1–V3 data.
- **Programmable stereo FX:** six live rack slots with a touch-driven visual workbench for saturation, tremolo, chorus and delay. Add/remove, reordering, bypass, wet/depth/rate/stereo edits now reach **the same real stereo audio engine** as the keyboard/MIDI sound. Worker-acknowledged reorder tickets prevent immediate slider edits from changing the wrong slot; patch saves wait briefly for a pending reorder or report a retryable error if the audio worker cannot settle. [Integrated and verified in PR #108](https://github.com/boop-bip80/Android-synth/pull/108).
- **Black canvas performance mode:** a keyboardless performance stage with an optional **FOCUS** action that hides stage controls and chrome, leaving the responsive black touch surface; Android Back reveals controls safely. Pressure-aware note attacks, scales/octaves/pitch axes/glide and X/Y/pressure modulation are live. Gestures draw harmonic ribbons and tap disturbances while real-output PCM feeds reactive light. [PCM host integration #99](https://github.com/boop-bip80/Android-synth/pull/99) and [FOCUS mode #106](https://github.com/boop-bip80/Android-synth/pull/106) both passed emulators. Stage settings currently survive stage re-entry, while Activity/process restart persistence is under development.
- **Reference versus reality:** the 2 MB generated art above is the original **aesthetic reference**. The downloadable Android screenshots are the honest running-UI evidence. Exact pixel parity, sustained 16-voice CPU headroom, stylus/device pressure range, frame-time, touch latency and thermal behavior still require physical-device evaluation.

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

**Current feature lineage:** [Immersive stage #74](https://github.com/boop-bip80/Android-synth/pull/74) · [Extra oscillators #75](https://github.com/boop-bip80/Android-synth/pull/75) · [Modulation audio #80](https://github.com/boop-bip80/Android-synth/pull/80) · [Oscillator editor #81](https://github.com/boop-bip80/Android-synth/pull/81) · [Modulation editor #94](https://github.com/boop-bip80/Android-synth/pull/94) · [V3 patches #96](https://github.com/boop-bip80/Android-synth/pull/96) · [Live FX bus #89](https://github.com/boop-bip80/Android-synth/pull/89) · [FX workbench #98](https://github.com/boop-bip80/Android-synth/pull/98) · [PCM visual fields #91](https://github.com/boop-bip80/Android-synth/pull/91) · [Real PCM host #99](https://github.com/boop-bip80/Android-synth/pull/99) · [Reactive visuals #103](https://github.com/boop-bip80/Android-synth/pull/103) · [Filter/amp/pan mod #102](https://github.com/boop-bip80/Android-synth/pull/102) · [Safe stage settings codec #97](https://github.com/boop-bip80/Android-synth/pull/97) · [V4 FX patches #101](https://github.com/boop-bip80/Android-synth/pull/101) · [Live six-target modulation UI #104](https://github.com/boop-bip80/Android-synth/pull/104) · [Clean FOCUS mode #106](https://github.com/boop-bip80/Android-synth/pull/106) · [FX UI→audio host #108](https://github.com/boop-bip80/Android-synth/pull/108).

**Real workbench screenshot proof:** [production oscillator editor after OSC1/SAW Android tap (1080×2400)](https://github.com/boop-bip80/Android-synth/actions/runs/41586812611/artifacts/13208054305). **Visual-field screenshot proof:** [fullscreen stage from Android 35](https://github.com/boop-bip80/Android-synth/actions/runs/41588116874/artifacts/13209092607). Screenshots live in short-retention CI artifacts (14 days); the concept artwork is preserved permanently above.

**Earlier upgrade PRs:** [Visual #61](https://github.com/boop-bip80/Android-synth/pull/61) · [UI #67](https://github.com/boop-bip80/Android-synth/pull/67) · [Analog #62](https://github.com/boop-bip80/Android-synth/pull/62) · [Digital #65](https://github.com/boop-bip80/Android-synth/pull/65) · [Arp core #64](https://github.com/boop-bip80/Android-synth/pull/64) · [Arp audio #68](https://github.com/boop-bip80/Android-synth/pull/68) · [Arp UI #69](https://github.com/boop-bip80/Android-synth/pull/69) · [CI proof #60/#63](https://github.com/boop-bip80/Android-synth/pull/63) · [Runtime integration #66](https://github.com/boop-bip80/Android-synth/pull/66).

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Portable Kotlin engine with **16 voices**, **five selectable synthesis architectures** and five band-limited wavetable families, morphing and alias-conscious mip crossfades, slew-limited filter/pan controls, filter-preserving voice-stealing release tails, mono/poly/legato, portamento and pitch bend. Three-oscillator unison supports up to ±30 cents detune in the wavetable and analog modes (not yet FM/phase distortion). Initialization avoids redundant wavetable trigonometry; held legato velocity and sustain changes now slew smoothly. | Measure device CPU, tuning, aliasing, cold-start and stress stability |
| Tone and movement | ADSR, resonant filter, LFO/modulation, drive, stereo chorus, delay and **eight-line stereo FDN reverb**. The live wet master bus supports a reversible 10 ms FX bypass; delay-time transitions use a 10 ms dual-tap crossfade. | Listening tests, hardware CPU/thermal and extended-session monitoring |
| Android audio and MIDI | AudioTrack worker with focus/lifecycle handling, MIDI source/port identity, CC64 sustain, CC66 sostenuto, CC120/121/123 semantics, hot-plug generation guards and transient audio-focus resume without stale-stop focus abandonment. | Test real speaker, wired/USB/Bluetooth routes, MIDI controllers, interruptions and latency |
| Interface | Native Compose sound/play/sequence/FX views based on committed artwork, engine architecture selector, holographic interactive orbital surface, **25 accessible chromatic notes**, octave buttons, XY pad, presets, unison/detune and per-note 1–64 step editor. Compact-width/large-font portrait layout moves patch controls outside the orbit, reduces orbit size and provides an expandable modulation chart. Extremely short (<500dp) viewports use paged live sound and FX racks so the touch keyboard remains available. | Physical touch, accessibility/TalkBack, landscape and tablet playtests |
| Musical tools | Source-aware sample-addressed performance arpeggiator (ratchets/probability/latch/swing) and sample-addressed sequencer with worker-safe pattern loading, resizing, per-step note editing and muting; 12 factory patterns (including 32-step *Eclipse Shift* and 64-step *Deep Orbit*); swing, 20 scales, Euclidean patterns, polymetric layers and seeded melodic walks. | Verify timing under load, rapid edits and simultaneous MIDI/touch |
| Sounds and storage | **25 factory sound patches**, **12 factory patterns**, versioned patch import/export, app-private sequence storage and library. **V2 sequence saves preserve muted source notes and their enable-state**, read V1 data, and keep phase when editing. Delayed worker mute toggles are reconciled with acknowledgement tickets so rapid taps and saves retain the latest user intent. | On-device migration, storage corruption, rapid edit and restart playtests |
| Quality automation | Pinned JDK17/SDK35 Gradle builds, JVM tests, Android lint, app and instrumented APKs, API35 general emulator **45+ instrumentation tests on the recent verified runs** plus **2 real 360×350dp keyboard/tab E2E cases**; actual normal, compact 360dp / 1.3× font and landscape screenshots. Current green test results, source commit, and APK evidence are linked at the top; the previous v0.1 baseline passed **34/34 instrumentation tests**. Main builds retain active validation, require build artifacts, and recover narrowly from external Pixel Launcher ANR overlays. | Keep verifying future app-code changes; emulator data cannot replace real-device measurements |

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
