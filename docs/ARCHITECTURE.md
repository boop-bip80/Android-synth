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

## AURORA stage and physical-model engine (v0.3.0)

### Diatonic touch stage (`:core` `stage` package, no Android dependency)
- `StageKey` / `StageScales` / `NoteSpelling`: a tonic plus a scale. Seven-note scales are spelled
  one letter per degree (D major reads F♯, never G♭), other scales fall back to a sharp/flat
  preference from the tonic.
- `DiatonicGrid`: a vertical ladder of in-key MIDI notes. **Lane 0 is the bass and sits at the bottom
  of the screen; the top lane is the highest note**, so any touch is in key. The span shrinks
  rather than exceed MIDI 127.
- `ChordVoicer` / `ChordAssist`: one-finger harmony built by stacking *scale steps* (solo, octave,
  power, triad, jazz 7th), with chord symbols and Roman numerals; thirds are dropped below MIDI 50.
- `StageSurface`: the multitouch state machine. Every sounding MIDI note is reference counted, a
  slide starts the new note before releasing the old (legato), every `NoteOn` is balanced by a
  `NoteOff`, and expression is emitted as raw gesture axes that the host applies *as offsets around
  the patch's own parameters*, restoring the patch exactly when the last finger lifts.
- `Song` / `SongBook` / `GuideSession`: public-domain tunes stored as **scale steps** (so they are
  correct in any key and can be heard in other modes). The guide waits for the player, scores
  combo/accuracy/stars, can re-lay a song on a new grid mid-lesson (`withGrid`), and produces a timed
  demo timeline for the ghost finger.
- `glow.AuroraSim` + `GlowCanvas`: the living surface (lane strings that lean toward fingers and ring
  when plucked, racing light pulses, ripples, comet trails, sparks, fireflies, audio-reactive
  brightness, melody ribbon). The simulation is pure Kotlin and draws only through `GlowCanvas`:
  Compose `DrawScope` in the app (`ComposeGlowCanvas`), Java2D in `tools/stage-preview`.
  Continuous motion runs only when Android animations are enabled; otherwise the stage redraws on
  events, and FOCUS idles with **no per-frame work** and a pure-black field.

### SOURCE parameter, physical models and the master limiter
- `ParameterId.SOURCE` (appended last; optional in patch files; neutral 0): `0` = the selected
  engine's own oscillator (the legacy path, untouched and bit-exact), `0.5` = plucked **STRING**,
  `1` = struck **GLASS**/bell. A physical source replaces the oscillator but keeps the engine's
  filter, ADSR, glide, pan and effects, so a plucked string can run through the ladder, SEM or
  state-variable filter. Patches without the key load with SOURCE reset to 0.
- `KarplusStrongVoice`: extended Karplus-Strong waveguide with 4-point Lagrange fractional delay and
  the loop filter's exact phase delay subtracted (pitch is checked within 6 cents, C2-C6),
  T60-based sustain, loop gain capped below unity, velocity/brightness-shaped excitation.
- `ModalBellVoice`: six decaying modes whose partial ratios morph marimba -> glass -> bell; higher
  modes decay faster; modes above 45% of the sample rate are muted rather than aliased.
- `MasterLimiter`: zero-latency stereo-linked limiter with an absolute 0.985 ceiling as the last
  stage. Bit-identical below the ceiling (every golden-PCM test is unchanged); gain state is double
  precision because a float version stalled just short of unity.

Fidelity boundary: STRING/GLASS are original models *inspired by* plucked strings, marimbas, glass
and bells. They are not sampled instruments and are not calibrated against a measured instrument.

## Jazz catalogue, per-tune presets and stage looks (unreleased)

### Jazz catalogue (`:core` `jazz` package, no Android dependency)
- `RealBookVol1Data` is one validated text file: a `[A]` section heading per book section and one
  `Title | Composer | key | feel [| voice=… bpm=…]` line per tune, in the book's order. `?` (composer,
  key) and `-` (feel) mean *not recorded*; the model carries that through (`JazzStandard.key == null`,
  `feelKnown == false`) so the UI shows nothing instead of a guess. A malformed line throws with its
  line number, so a bad edit fails the unit tests. **Only facts belong in it; never melodies or charts.**
  Titles come from a supplied contents list; composer/key/feel are unverified annotations.
- `JazzStandard` is metadata only. `JazzCatalogue` parses, searches (every term must match title,
  composer, key, feel or instrument) and files tunes under the book's own sections.
- `JazzVoices` defines 13 voices on the existing engine only. Each writes all 18 engine parameters
  so loading one fully replaces the previous sound. `JazzPresets.patchFor` tailors a voice to a tune
  deterministically (tempo → release/decay/wash; minor mode → darker; feel → reverb/decay/movement)
  and stores the tune's BPM as the patch's modulation tempo. The result is an ordinary `SynthPatch`
  (`rb1-<id>`); it is *not* added to `FactoryPatches.ALL`, so the voice stepper is unchanged.
- App side: `ImmersiveStage.playStandard` loads the patch through `onApplyPatch`
  (`MainActivity.applyStagePatch`, which releases notes and arpeggiator, reconciles the FX rack and
  routes the patch BPM to the master tempo, exactly as a factory patch load does), retunes the stage to
  the tune's key when recorded, and selects JAZZ harmony.

### Stage looks (dark / normal / auto)
- `GlowTheme` (`:core`, `stage.glow`) wraps a `GlowCanvas`. `DARK` is the original canvas untouched.
  `LIGHT` returns `LightGlowCanvas`, which re-inks every primitive: additive light becomes ordinary
  alpha, neon hues become deep inks of roughly equal perceived darkness, white-hot highlights become
  the darkest ink. The simulation (`AuroraSim`) is unchanged; Android and the Java2D preview get the
  look through the same wrapper. `LightInk` is allocation-free like the rest of the draw path.
- `StagePalette` (`:app`, `ui/aurora/StageTheme.kt`) holds every colour the chrome uses. The dark
  palette is exactly the colours the chrome had before themes existed. `StageThemeMode` is DARK
  (default), LIGHT or SYSTEM and is stored in `AuroraPrefs`; `OrbitalScreen` provides the palette as
  `LocalStagePalette`. FOCUS and the audio-reactive field stay black/dark by design; in normal mode
  the field is not composed (light added to black cannot show on paper).
- Not themed: the main SOUND / PLAY / SEQ / FX screens and the stage configuration dialog's own panel.

