# ORBITAL — Android wavetable synthesizer

A native, touch-first Android synthesizer with an evolving wavetable engine and an ambitious, functional cosmic UI. The original concept art is included as a visual brief. **It is not a screenshot of the running app.**

![Original Orbital visual concept — generated design reference, not app screenshot](design/orbital-ui-reference.png)

## Design and interaction

The target is a luminous central orbital wavetable visualizer, blue/violet/gold controls, a modulation-curve editor, an interactive expressive keyboard, and compact but deep sound-shaping panels. The app UI is built in Kotlin/Jetpack Compose so knobs and keys really control synthesis rather than showing a static mockup.

## Architecture

- **Core** — portable Kotlin/JVM polyphonic synthesis engine, modulators, FX, musical tools and patch model.
- **App** — Android AudioTrack rendering, MIDI, Compose UI and interaction.
- **Automation** — GitHub Actions host tests, Android lint, API 35 emulator instrumentation, launch check, screenshot capture and downloadable debug APK.

See [architecture](docs/ARCHITECTURE.md) and [build / test instructions](docs/BUILD.md).

## Build and test

With JDK 17, Android SDK 35 and Gradle 8.11.1:

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The CI workflow also runs an Android emulator; no emulator is required on your own computer.

- [Android CI workflow](.github/workflows/android.yml)
- [GitHub Actions runs](https://github.com/boop-bip80/Android-synth/actions)
- [Real emulator screenshot artifact](https://github.com/boop-bip80/Android-synth/actions) — downloadable from a **successful** workflow run
- [Debug APK artifact](https://github.com/boop-bip80/Android-synth/actions) — downloadable from a **successful** workflow run

## Status

The project is in initial development. **Do not confuse design goals with validated functionality.** This README will be revised as implementation, sound output, test evidence and Android screenshots land. A passing emulator job does not certify on-device latency, MIDI controller interoperability, battery usage or production audio quality.

## Licensing

License has not yet been selected. Do not assume code/assets are available under an open-source license without an explicit LICENSE file.
