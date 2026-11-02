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
- Motion effects can be reduced; visual rendering must not starve audio thread.
- Actual running-app screenshot captured from Android emulator as separate artifact.

## G4 — Emulator CI
- GitHub-hosted Android emulator (API 35 x86_64) boots, installs APK, launches MainActivity and runs instrumentation tests.
- Host tests and emulator job split, with deterministic failure diagnostics.
- Screenshot, logcat excerpt and debug APK retained as CI artifacts (do not commit generated build output).
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
