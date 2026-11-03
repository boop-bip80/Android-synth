# ORBITAL implementation contract

This document fixes interfaces for parallel work. A feature is not complete unless tested.

## Modules and package names
- `:core`: pure Kotlin/JVM DSP, music theory, presets, sequencer. Package `com.orbital.synth.core`.
- `:app`: Android UI and audio delivery. Application ID `com.orbital.synth`. Kotlin, Jetpack Compose, Android AudioTrack initially (48 kHz float or 16-bit fallback).
- Java/Kotlin target 17, compile/target SDK 35, min SDK 26. Kotlin 2.1.x + Compose compiler plugin, Gradle/AGP versions chosen consistently by build owner.

## Public DSP surface
In `core/src/main/kotlin/com/orbital/synth/core/OrbitalSynth.kt`:
```kotlin
enum class ParameterId { WAVE, MORPH, CUTOFF, RESONANCE, MOVEMENT, FX_AMOUNT, ATTACK, DECAY, SUSTAIN, RELEASE, LFO_RATE, LFO_DEPTH, GLIDE, UNISON, DETUNE, ENGINE, CHARACTER }
class OrbitalSynth(sampleRate: Int = 48000, maxVoices: Int = 16) {
    fun noteOn(note: Int, velocity: Float = 1f)
    fun noteOff(note: Int)
    fun allNotesOff()
    fun setParameter(id: ParameterId, value: Float) // 0..1 inclusive, clamp invalid values
    fun renderStereo(output: FloatArray, frames: Int) // interleaved, at least frames*2, no allocation in inner sample loop
}
```
The audio thread exclusively calls `renderStereo`; UI parameters and note events must cross threads safely. No per-sample allocations, file/network I/O, locks, UI callbacks, or Android dependencies in DSP. Preserve sample-rate invariants and bound output.

## Multi-architecture voice routing (v0.2.0)

The normalized `ENGINE` patch/UI parameter is quantized into five real audio paths
at render-block boundaries:

| Value | Architecture | Sound-generation path |
| --- | --- | --- |
| 0.00 | ORBITAL WT | Original mip-aware band-limited wavetable bank + TPT state-variable LPF |
| 0.25 | LADDER | 3-oscillator wavetable/unison excitation through a nonlinear four-pole zero-delay-feedback virtual-analog ladder |
| 0.50 | SEM | 3-oscillator excitation through a 12 dB multimode state-variable virtual-analog filter |
| 0.75 | SIX-OP FM | 6-operator algorithmic phase modulation, eight routings; WAVE selects algorithm |
| 1.00 | PHASE DISTORTION | Phase-distortion oscillator, six shape families; WAVE selects shape |

`CHARACTER` is a normalized timbral macro. `MORPH` shapes wavetable and digital
timbres; `CUTOFF` and `RESONANCE` remain shared performance controls.
Every architecture shares the parent voice's ADSR, velocity slew, glide,
source ownership, pan and master effects. Recycled analog voices use copied
preallocated filter state to keep the 64-sample steal tail smooth; mode changes
use an 8 ms held-output de-click bridge (not a dual-engine crossfade) without
allocations on the audio thread. UNISON/DETUNE currently affect wavetable and
analog modes, not digital FM/phase-distortion. Existing patch files
without ENGINE/CHARACTER load in legacy wavetable mode. These are original
architecture-*inspired* models; no 1:1 circuit calibration, ROM contents,
chip-exact DX FM or guaranteed alias-free operation is claimed. High-register
spectral aliasing and 16-voice Android CPU/thermal behavior require measurement.

## Public UI surface
In `app/src/main/java/com/orbital/synth/ui/OrbitalScreen.kt`, supply:
```kotlin
enum class PlayMode { POLY, MONO, LEGATO }
data class OrbitalUiState(
    val patchName: String = "AURORA",
    val playMode: PlayMode = PlayMode.POLY,
    val parameters: Map<ParameterId, Float> = emptyMap(),
    val octave: Int = 4
)
@Composable fun OrbitalScreen(
    state: OrbitalUiState,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onParameterChange: (ParameterId, Float) -> Unit,
    onModeChange: (PlayMode) -> Unit,
    onOctaveChange: (Int) -> Unit
)
```
Do not render the full mockup PNG as the app screen: build real functional Android controls, with the artwork as reference. Include accessible semantics, device-size adaptation, reduced-motion behavior.

The current sequencer UI also accepts immutable `sequenceNotesByStep` data (lists of `SequencerNote`), `onSequencerStepNotesChange(step, notes)` and `onSaveSequence(name)` callbacks. The host supplies these and must not block the main thread for persistence. Changes to MIDI pitch, gate, chance, velocity, ties or chords must cross into the worker-owned `AudioSequencerTransport` queue; a direct mutable UI sequencer is not authoritative.

## App/audio integration
`AndroidAudioOutput` under `com.orbital.synth.audio` owns AudioTrack lifecycle, a dedicated render thread, reliable start/stop, defensive audio focus. `MainActivity` (integrator-owned) wires UI callbacks to the engine and audio output.

The master wet bus is `Drive → StereoChorus → StereoDelay → StereoReverb → FxBypassCrossfade`, using preallocated stereo scratch frames. The bypass crossfade must finish before chorus, delay and reverb tails are invalidated. Both unison controls default to zero and old patch files must remain sonically neutral when loaded.

`SequenceDirectoryStore` persists versioned `UserSequence` files under app-private `filesDir/sequences` with blocking I/O only on `Dispatchers.IO`. The V2 codec preserves source notes, velocities and enable/mute state without flattening hidden notes into rests; V1 files remain readable. MainActivity restores V2 step enable-state through the worker-owned queue. The UI's pending notes and step-toggle intents reconcile with render-worker snapshots: accepted toggle commands have monotonically ordered acknowledgement tickets so rapid OFF→ON taps cannot be erased by a delayed poll, and live edits preserve playback phase. Malformed persistence metadata is rejected.

The Android audio-focus policy owns transient interruption and resume explicitly: the AudioManager focus claim is retained until a transient loss is resolved, while an explicit app stop or permanent loss abandons it. Focus request and abandonment are serialized with audio lifecycle transitions. Physical phone-call/headset behavior still requires device testing.

The Compose sound view adapts to compact widths/large font scales by moving patch metadata and controls outside the orbit and presenting a fully visible expandable modulation chart control. Android UI instrumentation asserts bounds on a 360 dp / 1.3× font configuration, while screenshot capture and manual inspection remain separate validation steps.

## Live performance routing (v0.2.0)

The Android worker owns `PerformanceArpTransport`. All physical touch and MIDI
note-on/off events enter its bounded queue through `AndroidAudioOutput.noteOn` /
`noteOff`, keeping their source/channel identity. Arpeggiated output uses
a distinct source ID `-7`, direct touch remains `-1`, sequencer `-2` and
fallback MIDI `-3`. The performance arpeggiator supports six traversal
directions, adjustable subdivisions, swing, octaves, ratchets, gate,
probability and latch; instrument controls expose an initial subset. On stop,
focus-loss, patch change or panic, outstanding notes are released by source
rather than cutting other inputs. The existing step sequencer remains
sample-addressed on the same audio render worker. This is not a claim
of sample-accurate external USB-MIDI timestamp ingestion.

## Workstream ownership
1. DSP synth engine + wavetable/voices: `:core` root and oscillator/voice classes.
2. DSP filters/envelopes/LFO/effects: `:core` `dsp/processing` only, no edits to workstream 1 classes.
3. Android audio delivery + MIDI: `:app` `audio/` only, no MainActivity/manifest edits.
4. UI/design: `:app` `ui/` only.
5. Sequencer, scales, patches: `:core` `music/` and `preset/` only.
6. Gradle, AndroidManifest, instrumentation tests, GitHub Actions emulator, README/docs (except this file and reference image).

Changes must be integrated, compiled, unit-tested, emulator-tested, and documented; a queued workflow is not a green workflow. Hardware low-latency/MPE claims require device evidence.
