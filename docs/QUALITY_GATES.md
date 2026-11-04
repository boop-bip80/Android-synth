# Verification gates (ORBITAL)

This is a release gate, not a claim that a gate has passed.

## G0 — Repository/toolchain
- All Gradle plugins and Android dependencies pinned and resolving.
- `gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes on CI.
- Debug APK is uploaded with immutable workflow run provenance.
- License/third-party source notices tracked; no bundled proprietary samples.

## G1 — DSP behavior
- Sample rate / buffer guards; output finite and bounded for all `ParameterId` values including NaN/infinity/out-of-range.
- 16-note polyphony with explicit deterministic voice stealing, retrigger and note-off behavior.
- Note A4 has measured fundamental near 440 Hz; tuning remains stable across sample rates.
- Long renders under extreme resonance, effects and modulation do not diverge; no per-sample heap allocations.
- UI/control-thread messages and render-thread events cannot mutate voice state unsafely.
- Silence after release, no stuck notes after panic, no DC accumulation.
- Audio behavior measured with objective signals and supplemented by listening; tests alone do not establish hardware fidelity.

## G2 — Android lifecycle
- Repeated app open/background/foreground and audio start/stop never leak output tracks or threads.
- MIDI USB/Bluetooth device attach/detach, note-on velocity-zero semantics, multi-byte streams and interrupted connections safely handled.
- No AudioTrack blocking on main/UI thread; audio focus and route changes handled reasonably.
- On-screen keyboard sends note-off on pointer up/cancel and multitouch release.
- Error state surfaces to user instead of silently claiming playback.

## G3 — Visual/interaction
- Real Compose implementation inspired by `design/orbital-ui-reference.png` (not a static full-screen image).
- Layout works at 360–480 dp widths, common font scales, landscape/short-height screens, dark mode.
- Controls have descriptive accessibility labels, units/ranges, touch targets and live feedback.
- The OSCILLATOR CONSTELLATION editor is exercised on an actual running API35 emulator with a dedicated instrumented **production MainActivity** test: use Compose's real scroll semantics to reach the deep SOUND editor, tap to activate OSC1, assert the accessible active state, choose the SAW waveform, and capture the actual display via Android UiAutomation. The CI harness extracts that framebuffer via debug-app `adb run-as`, validates PNG geometry and a SHA-256 distinct from the starting screenshot, and attaches the real PNG and instrumentation log. Screenshots prove UI interaction/rendering, not real-device audio performance or precise synthetic quality.
- Motion effects can be reduced; visual rendering must not starve audio thread.
- The keyboardless immersive sound stage is a distinct black performance surface (not an overlay that leaves keyboard hit targets active). It exposes an accessible entry and exit; tap/drag gestures reach the live synth and release all notes on cancellation and departure. Gesture control values stay finite and normalized.
- A separate **production MainActivity** API35 instrumentation capture keeps a real Compose finger *down* across drag events, checks non-silent coherent RMS/peak telemetry sourced from PCM actually accepted by Android AudioTrack, captures the physical framebuffer *while the note is still held* using Android UiAutomation, checks real visual pixel luminance/color growth against an independently captured idle-stage frame, then releases touch and asserts EXIT restores the keyboard while the audio worker stays alive. The CI harness extracts both genuine PNGs and PCM/pixel evidence via debug-only `adb run-as`; validates exact portrait dimensions, complete PNG framing, distinct SHA-256 values, and attaches originals. This is real emulator visual+PCM-path proof, **not** a measured physical-phone audio latency, speaker output, or an objective match to the original concept art.
- Actual running-app screenshots captured from Android emulator as separate artifacts: default portrait, 360 dp / 1.3× font compact portrait, and a wide/short landscape-size viewport. The landscape capture verifies a focused, drawn Activity and horizontal PNG geometry; **it does not certify interaction quality or visual similarity to the concept**, which still require human review.

## G4 — Emulator CI
- GitHub-hosted Android emulator (API 35 x86_64) boots, installs APK, launches MainActivity and runs instrumentation tests.
- Host tests and emulator job split, with deterministic failure diagnostics; device-facing instrumentation probes Android audio service route enumeration, output lifecycle/closure and sequencer command delivery when a virtual sink is available.
- Screenshots must be captured only after the app owns the focused, drawn window, with PNG format checked. The separate immersive-stage proof enters via accessibility bounds from the real UI hierarchy, verifies that the keyboard disappears, records a full-size PNG **immediately after a completed, genuine Android touch swipe** (when the canvas is individually accessible), with all ADB operations serialized to protect capture reliability, validates its PNG dimensions and SHA-256 distinct from the regular screen, exits, and verifies that the keyboard returns. The evidence is an emulator screenshot, not a pixel-perfect visual-fidelity certificate. Exercise the default Pixel 6 portrait, **360 dp width at 1.3× font scale**, and a third **wide/short landscape-sized** viewport on the same API 35 emulator. The landscape pass checks that a screenshot is genuinely wider than tall, reports its geometry and SHA-256, and restores the prior size override even on failure. The separate portrait/compact pass restores the font setting. These are launch/layout smoke gates, not pixel-perfect comparison, visual-fidelity certification, or TalkBack accessibility testing.
- Bounded ADB diagnostics include window state (including landscape), script status and stage, per-stage workflow run/SHA provenance, logcat excerpts, AudioFlinger/policy, MIDI state, memory and display facts, plus debug and instrumentation APKs (do not commit generated build output). A failed landscape stage must fail the overall emulator job without disguising a successful first-stage run.
- Validate the emulator harness syntax before host builds, bound package-manager readiness and instrumentation runtime, and preserve failure exit codes while collecting artifacts.
- If emulator workflow fails/does not finish, report unverified; do not mark G4 complete.

## G5 — Real-device validation (not satisfied by an emulator)
- On at least one modern Android phone, measure first-audio time, sustained underruns, latency, CPU/battery and thermal behavior.
- Test speaker, wired USB audio, Bluetooth audio caveats, MIDI controllers, orientation and interrupts.
- Field-test sample rate selection and output routes. Claim low latency only with measured device evidence.

## G6 — Shipping readiness
- Persist/restore and migrate patch files, smoke-test a factory sound bank and sequencer.
- Ensure app icon, privacy posture, crash reporting choice, signed release configuration.
- Reproducible release artifact and changelog tied to commit SHA.

Keep the README status honest: a successfully built debug APK is not a production release or evidence of low-latency performance.

## G5 — AURORA stage and physical-model engine (v0.3.0)

These are *definitions of what is checked*; whether they pass is shown by the CI run for the exact
commit, never by this document.

- **Musical correctness (JVM, `:core`)**: every lane of every offered scale in every key is strictly
  ascending and in key; bottom of the screen is the lowest note; songs fit every key on the default and
  two-octave grids; Ode to Joy is the real melody in C and D major; chord names/Roman numerals for
  every diatonic triad of C major.
- **No stuck or illegal notes (JVM fuzz)**: a 20,000-operation multitouch fuzz (down, move, up,
  cancel, key/chord reconfiguration across ten pointers) must leave nothing sounding and never emit an
  unbalanced or out-of-range note.
- **Renderer safety (JVM fuzz)**: a 6,000-operation hostile-input fuzz (NaN/infinite/out-of-range
  positions, grid changes mid-touch, zero-length frames) must produce only finite, in-range draw calls.
  The FOCUS idle surface must draw *nothing visible*.
- **FOCUS pixel contract (device)**: the existing `ImmersiveLiveFrameCaptureTest` thresholds still
  apply: an untouched FOCUS stage is genuinely black in its central region, and a held note adds real
  colourful pixels.
- **Engine (JVM)**: STRING pitch within 6 cents across C2-C6 and monotonic brightness; GLASS prime at the
  note played; both bounded and finite under hostile settings; SOURCE=0 renders bit-identically to a
  synth that never touched the parameter; every engine x source combination and **all 36 factory
  patches** stay finite and under the 0.985 limiter ceiling on a dense chord; switching source on a
  held note is click-free.
- **On-device stage behaviour**: `AuroraStageInstrumentedTest` (bass below treble, in-key touches, key
  and mode pickers, triad release balance, same-lane fingers, a whole lesson to a 3-star result, wrong
  notes sound but never advance a lesson, ghost-finger playback).
- **Gallery (non-blocking)**: `AuroraGalleryCaptureTest` captures real framebuffers with Android
  animations enabled. It is deliberately non-blocking; a published gallery image only exists if it was
  genuinely captured and passed PNG integrity checks.

Not established by any gate above: physical-phone audio latency, thermals, speaker output, real MIDI
hardware, TalkBack usability, or a perceptual/listening evaluation of the physical models.
