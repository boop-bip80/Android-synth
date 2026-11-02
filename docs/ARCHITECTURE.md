# ORBITAL implementation contract

This document fixes interfaces for parallel work. A feature is not complete unless tested.

## Modules and package names
- `:core`: pure Kotlin/JVM DSP, music theory, presets, sequencer. Package `com.orbital.synth.core`.
- `:app`: Android UI and audio delivery. Application ID `com.orbital.synth`. Kotlin, Jetpack Compose, Android AudioTrack initially (48 kHz float or 16-bit fallback).
- Java/Kotlin target 17, compile/target SDK 35, min SDK 26. Kotlin 2.1.x + Compose compiler plugin, Gradle/AGP versions chosen consistently by build owner.

## Public DSP surface
In `core/src/main/kotlin/com/orbital/synth/core/OrbitalSynth.kt`:
```kotlin
enum class ParameterId { WAVE, MORPH, CUTOFF, RESONANCE, MOVEMENT, FX_AMOUNT, ATTACK, DECAY, SUSTAIN, RELEASE, LFO_RATE, LFO_DEPTH, GLIDE, UNISON, DETUNE }
class OrbitalSynth(sampleRate: Int = 48000, maxVoices: Int = 16) {
    fun noteOn(note: Int, velocity: Float = 1f)
    fun noteOff(note: Int)
    fun allNotesOff()
    fun setParameter(id: ParameterId, value: Float) // 0..1 inclusive, clamp invalid values
    fun renderStereo(output: FloatArray, frames: Int) // interleaved, at least frames*2, no allocation in inner sample loop
}
```
The audio thread exclusively calls `renderStereo`; UI parameters and note events must cross threads safely. No per-sample allocations, file/network I/O, locks, UI callbacks, or Android dependencies in DSP. Preserve sample-rate invariants and bound output.

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

`SequenceDirectoryStore` persists versioned `UserSequence` files under app-private `filesDir/sequences` with blocking I/O only on `Dispatchers.IO`. The UI saves the audible pattern (muted steps are encoded as rests); user selections are queued to the audio worker through `loadSequencerPattern`. This flattening is an explicit current limitation for future nondestructive mute-layer storage.

## Workstream ownership
1. DSP synth engine + wavetable/voices: `:core` root and oscillator/voice classes.
2. DSP filters/envelopes/LFO/effects: `:core` `dsp/processing` only, no edits to workstream 1 classes.
3. Android audio delivery + MIDI: `:app` `audio/` only, no MainActivity/manifest edits.
4. UI/design: `:app` `ui/` only.
5. Sequencer, scales, patches: `:core` `music/` and `preset/` only.
6. Gradle, AndroidManifest, instrumentation tests, GitHub Actions emulator, README/docs (except this file and reference image).

Changes must be integrated, compiled, unit-tested, emulator-tested, and documented; a queued workflow is not a green workflow. Hardware low-latency/MPE claims require device evidence.
