# ORBITAL — Android wavetable synthesizer

A touch-first, performance-oriented Android wavetable synthesizer. The visual direction is based on the original [Orbital concept artwork](design/orbital-ui-reference.png): a luminous orbital sound display, modulation graph, neon controls, and an expressive playable keyboard.

## Development status
Project bootstrap in progress. This repository previously contained only a placeholder README. The design image is a **reference mockup**, not a screenshot of a running app.

## Target
- Native Android app using Kotlin, Jetpack Compose, and a realtime-safe stereo synthesis engine.
- 16-voice wavetable morphing; envelopes, filters, LFOs, effects, MPE/MIDI foundations, sequencer, and patch storage.
- Touch keyboard with scales, octave shifting, glide, and performance modes.
- Host unit tests, Android emulator instrumentation tests, and downloadable debug APKs from GitHub Actions.

See [architecture and workstreams](docs/ARCHITECTURE.md). Builds and feature claims will be updated only after validation.

## Visual reference
![Orbital design reference](design/orbital-ui-reference.png)
