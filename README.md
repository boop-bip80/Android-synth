# ORBITAL — Android wavetable synthesizer

**Status: working source implementation in active development; an Android build/emulator pass has not yet been confirmed.** A debug APK should be treated as installable only once the linked GitHub Actions run succeeds and publishes the artifact. Production audio performance has not been measured on hardware.

![Original ORBITAL generated concept artwork — UI reference, not a running-app screenshot](design/orbital-ui-reference.png)

ORBITAL is a touch-first, sound-design-oriented Android synthesizer. The interface recreates the *language* of the concept image — a dark cosmic surface, an orbital sound display, neon control rings, modulation curves and an expressive touch keyboard — in native Jetpack Compose. The picture above is not used as an inert full-screen app UI.

## Implemented in source

| Area | Code currently present | Verification still required |
| --- | --- | --- |
| Synthesis | Polyphonic portable Kotlin engine with **16 voices**, **five band-limited wavetable families**, oscillator morphing, note stealing, mono/poly/legato, portamento and pitch bend | Run host DSP tests; measure pitch, aliasing and CPU on actual devices |
| Tone and movement | ADSR, resonant filter, LFO/modulation, drive and stereo delay processors; touch-controllable wave, cutoff, resonance, movement and FX | Automated DSP integration and listening tests; stress/sample-rate checks |
| Android audio and MIDI | AudioTrack render worker with lifecycle handling; Android MIDI device/message handling | Check real output routes, USB controllers, Bluetooth behavior and round-trip latency |
| Interface | Native Compose sound, play, sequence and FX views; orbital display, controls, accessible touch keyboard, patch navigation | Emulator UI capture plus actual multi-touch, font-scale and orientation playtests |
| Musical tools | Sixteen-step sequencer with velocity/gate/swing/ties/probability, musical scales and quantization | Verify timing under load, app lifecycle and MIDI interactions |
| Sounds and storage | **16 factory patches**, **6 factory patterns**, versioned patch model/import/export and local persistence code | Exercise data migration and user-flow tests |
| Quality automation | JVM/JUnit suites, Android lint, APK build job, **API 35 x86_64 emulator** instrumentation, launch check and screenshot capture workflow | A completed green GitHub Actions run and linked artifacts |

These are descriptions of code in this repository, **not claims of a finished commercial instrument**. In particular, per-note MPE and guaranteed low-latency playback are not verified.

## Project layout

- [Portable synthesis and musical core](core/src/main/kotlin/com/orbital/synth/core)
- [Android application](app/src/main/java/com/orbital/synth)
- [Original concept image](design/orbital-ui-reference.png)
- [Architecture contract](docs/ARCHITECTURE.md)
- [Verification gates](docs/QUALITY_GATES.md)
- [Build and CI instructions](docs/BUILD.md)

## Get the debug APK and actual UI screenshot

Use the [Android CI workflow](.github/workflows/android.yml) from the repository's [Actions tab](https://github.com/boop-bip80/Android-synth/actions). Once a run is **green**, its downloadable artifacts include:

- `orbital-debug-apk` — unsigned-for-Play debug install package, signed with the Android debug key.
- `orbital-api35-emulator-screenshot` — actual UI captured via `adb shell screencap`, separate from the concept image.
- Instrumentation results and Android lint report, if generated.

If no green run appears, the APK and emulator verification are **not yet available**. A queued job is neither a pass nor a compile failure.

## Build yourself (optional)

Use **JDK 17**, **Android SDK 35** and **Gradle 8.11.1** (the workflow installs Gradle; no wrapper binary is committed):

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The repository uses AGP 8.9.2 and Kotlin/Compose Compiler 2.1.20. See the [build guide](docs/BUILD.md) for exact configuration. The app supports Android API 26+.

## Next validation milestones

1. Resolve any Gradle/unit-test/lint failures exposed by the first executing CI runner.
2. Pass API 35 emulator launch, instrumentation and screenshot checks with evidence.
3. Profile real Android phones for audio underruns, latency, battery, thermal behavior and MIDI controller compatibility.
4. Complete accessibility/interaction playtests and release signing before claiming production readiness.

## License

No license has been selected yet. The code and generated visual design are not offered under an assumed open-source license.
