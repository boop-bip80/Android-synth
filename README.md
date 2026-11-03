# ORBITAL — Android multi-engine synthesizer

**Status (2026-11-03): active alpha development, not a production-ready instrument.** The currently merged source has **five selectable synthesis architectures, up to 16 voices, 25 factory patches, eight editable oscillator layers, eight programmable LFOs, 64 modulation routes, MIDI, arpeggiation/sequencing, and six routable stereo FX slots with seven selectable effect types**. These are bounded implementation capacities, **not unlimited oscillators, modulations or processing power**; simultaneous maximum-load operation on a physical phone remains unprofiled. The keyboardless **FOCUS** mode now has a genuinely black idle surface that lights up in response to real AudioTrack output and a held touch. **Latest verified combined PR build:** [GitHub Actions #41602201081](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081), all **five required jobs green** (host plus four independent API35 emulator lanes), merged via [PR #120](https://github.com/boop-bip80/Android-synth/pull/120). Check the [latest successful `main` build](https://github.com/boop-bip80/Android-synth/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess) for newer changes. Physical-device audio latency/CPU/thermals, Play signing, calibrated 1:1 analog/digital circuit emulation and visual parity with the original concept remain **unverified**.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer built in native Jetpack Compose. The picture above is **the original generated concept artwork** and the aesthetic target, **not a running app screenshot**. The present SOUND page is **still substantially simpler and visually far behind that reference**: the new audio-reactive stage, sound controls and oscillator/modulation workbenches are functional, but the proposed richer plasma backdrop, denser holographic controls and redesigned touch keybed are still work in progress. Do not judge implementation fidelity from the reference image; use the independently captured emulator screenshots linked below.

## Install the latest tested APK and compare real Android screenshots

- **Installable, debug-signed APK:** [download `orbital-debug-apk` from the all-green combined run](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215426510) (Android debug APK ZIP artifact). This is the exact verified PR source preceding [the squash merge of #120](https://github.com/boop-bip80/Android-synth/pull/120), including the seven-effect DSP, persistent stage configuration, oscillators, modulation, live stereo FX controls and keyboardless FOCUS interaction. **Not** a Play-signed release or a guarantee of real-phone latency/thermal headroom. Later app/UI PRs are **not included** until merged and separately verified.
- **Real Android 15 / API35 screenshots (from genuinely running APK):** [normal portrait + compact font](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215052562), [360×350dp compact physical-device-geometry gates](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215860389), [keyboardless STAGE + physical EXIT + OSC1/SAW oscillator workbench + landscape](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215637063), and [FOCUS untouched-black → real held-note neon live framebuffer pair](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215192111). The pictures are real emulator framebuffers from current code, not the generated concept shown above.
- **Five-job quality gate:** [full successful build, JVM/lint/APKs and four independent API35 emulator lanes](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081) — **59/59** general connected instrumentation tests, a separate strictly verified AudioTrack/FOCUS held-note instrumentation test, independent compact UI checks, and independent physical stage/oscillator/landscape screenshots. [Latest successful `main` APK](https://github.com/boop-bip80/Android-synth/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess) may be newer. GitHub Actions screenshot and APK artifacts expire after the configured retention window (currently 14 days); the original design reference in `design/` remains in the repository.

### November 3: layered sound design and keyboardless performance

- **Additive voice layers:** eight independently toggleable, tunable wavetable oscillator slots per voice, plus the five existing architecture choices; layer wave, level, coarse/fine tuning and morph in the native SOUND rack. This is polyphonic DSP, not a mock UI.
- **Live modulation:** eight shape/sync/retrigger-configurable LFOs, 64 editable cables and touch X/Y/pressure modulation sources. MORPH, PITCH, CUTOFF, RESONANCE, AMP and PAN destinations are audible in the real render path; unused destinations are marked reserved. V4 patches retain V3 oscillator/LFO/route/tempo fields and additionally persist FX order, type and parameters, while decoding legacy V1–V3 data.
- **Programmable stereo FX:** six live rack slots with a touch-driven visual workbench for saturation, tremolo, chorus, delay, stereo FDN reverb, four-stage phaser and sample-rate-aware bitcrusher. Add/remove, reordering, bypass, wet/depth/rate/stereo edits now reach **the same real stereo audio engine** as the keyboard/MIDI sound. Worker-acknowledged reorder tickets prevent immediate slider edits from changing the wrong slot; patch saves wait briefly for a pending reorder or report a retryable error if the audio worker cannot settle. [Integrated and verified in PR #108](https://github.com/boop-bip80/Android-synth/pull/108).
- **Black canvas performance mode:** a keyboardless performance stage with an optional **FOCUS** action that hides stage controls and chrome, leaving the responsive black touch surface; Android Back reveals controls safely. Pressure-aware note attacks, scales/octaves/pitch axes/glide and X/Y/pressure modulation are live. Gestures draw harmonic ribbons and tap disturbances while real-output PCM feeds reactive light. [PCM host integration #99](https://github.com/boop-bip80/Android-synth/pull/99) and [FOCUS mode #106](https://github.com/boop-bip80/Android-synth/pull/106) both passed emulators. Stage settings are saved in app-private storage and restored after Activity recreation ([PR #110](https://github.com/boop-bip80/Android-synth/pull/110)); rapid gestures coalesce into ordered atomic writes, while corrupt/unknown-version data is preserved.
- **Reference versus reality:** the original ≈2 MB generated image above is the *permanent intended aesthetic*, and **the current live SOUND interface does not match it yet**. Real running-APK screenshots document progress but do not prove visual equivalence. Exact pixel parity, sustained 16-voice/eight-layer CPU headroom, device pressure behavior, frame-time, touch-to-sound latency and thermal/battery limits still require physical-device evaluations.

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
- GitHub Actions builds debug/test APKs, executes Kotlin/JVM tests and Android lint, and requires **four separate API35 emulators**: `general` (59 connected instrumentation cases plus normal/compact portrait proofs), `compact` (real 360×350dp touch/FX-tab/keyboard layout), `stage` (genuine FOCUS black→held AudioTrack PCM-lit Android screenshots), and `visuals` (actual normal→keyboardless STAGE→physical EXIT, oscillator editor, landscape). No lane replaces real-device validation. Switching architecture still uses an 8 ms de-click bridge rather than simultaneous-engine crossfading; high-register nonlinear/FM aliasing, Android audio underruns and hardware MIDI latency remain unmeasured.

**Current feature lineage:** [Immersive stage #74](https://github.com/boop-bip80/Android-synth/pull/74) · [Extra oscillators #75](https://github.com/boop-bip80/Android-synth/pull/75) · [Modulation audio #80](https://github.com/boop-bip80/Android-synth/pull/80) · [Oscillator editor #81](https://github.com/boop-bip80/Android-synth/pull/81) · [Modulation editor #94](https://github.com/boop-bip80/Android-synth/pull/94) · [V3 patches #96](https://github.com/boop-bip80/Android-synth/pull/96) · [Live FX bus #89](https://github.com/boop-bip80/Android-synth/pull/89) · [FX workbench #98](https://github.com/boop-bip80/Android-synth/pull/98) · [PCM visual fields #91](https://github.com/boop-bip80/Android-synth/pull/91) · [Real PCM host #99](https://github.com/boop-bip80/Android-synth/pull/99) · [Reactive visuals #103](https://github.com/boop-bip80/Android-synth/pull/103) · [Filter/amp/pan mod #102](https://github.com/boop-bip80/Android-synth/pull/102) · [Safe stage settings codec #97](https://github.com/boop-bip80/Android-synth/pull/97) · [V4 FX patches #101](https://github.com/boop-bip80/Android-synth/pull/101) · [Live six-target modulation UI #104](https://github.com/boop-bip80/Android-synth/pull/104) · [Clean FOCUS mode #106](https://github.com/boop-bip80/Android-synth/pull/106) · [FX UI→audio host #108](https://github.com/boop-bip80/Android-synth/pull/108) · [Stage settings restart #110](https://github.com/boop-bip80/Android-synth/pull/110) · [Seven FX types #112](https://github.com/boop-bip80/Android-synth/pull/112) · [Real held-note framebuffer proof #107](https://github.com/boop-bip80/Android-synth/pull/107) · [Four-emulator CI + genuine black FOCUS proof #120](https://github.com/boop-bip80/Android-synth/pull/120).

**Real held-note PCM → visually lit FOCUS proof (Android 15, 1080×2400):** [download the actual black idle and held-touch colorful PNGs](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215192111). The untouched FOCUS frame had **0 illuminated / 0 chromatic** sampled central-canvas pixels; while a finger was **down/moving** and non-silent PCM was **accepted by Android AudioTrack**, the genuinely photographed frame measured **25,004 illuminated / 11,359 chromatic** pixels among 98,640 samples. Live PNG SHA-256: `13324a05c55313c1c6e3b049a0d64549d76901eb03a1f9f4838de7e731dcc714`. A real Back action revealed EXIT; note-up and keyboard return passed. **This proves interaction and genuine framebuffer provenance, not speaker output, real handset latency or visual matching to the generated artwork.**

**Further real display captures:** [OSC1/SAW oscillator editor + physical STAGE enter/exit + wide landscape](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215637063) (each captured and dimension/SHA checked on a fresh API35 emulator); [normal portrait and compact font](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215052562); [small-height compact UI](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081/artifacts/13215860389). GitHub screenshot artifacts are short-retention CI evidence; the original concept artwork above remains permanently in the repository.

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
| Quality automation | JDK17/SDK35 Gradle host tests, Android lint, debug app/instrumented APKs and **five required CI jobs**. General API35 runs **59/59** connected cases, `stage` separately runs the full real AudioTrack/held FOCUS test (together covering all 60); `compact` checks 360×350dp real tab/keyboard behavior; `visuals` asserts keyboardless stage + physical EXIT, oscillator workbench and landscape. PNG dimensions/hashes and PCM/luminance/chroma evidence come from actual running Android emulators. [Latest fully green run](https://github.com/boop-bip80/Android-synth/actions/runs/41602201081). | Validate all later PR merges; emulator checks do not measure real-device audio latency, thermal or visual parity |

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
