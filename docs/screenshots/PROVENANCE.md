# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same nine-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`8b8344eafafdffe52f3ada5c4b8ed9a3bb58629d`](https://github.com/boop-bip80/Android-synth/commit/8b8344eafafdffe52f3ada5c4b8ed9a3bb58629d)
- Successful CI: [run #750](https://github.com/boop-bip80/Android-synth/actions/runs/41976575159)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `408ad9473665b3fb069a0cd008fb0ba7805f8ab1b84e710c0642df715d711e01` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `63b76283f62b655601e6c98bee89355d7b6dbcebaa182b7b06d58c436f3005a1` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `8a8a1651aa28b62695359510888c9e7fea11a801c50fe99b96e4fd245878d170` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `3a203571eb66161eb761df2f0704b7ae67334b5fd5909a145d4d847a34e28bfe` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `e13b0daea076b2e6e32806a5baba23734dd09dffd7df8d015d1ac42ca7adef76` |
| AURORA stage: held chord, live motion | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-play.png](orbital-aurora-play.png) | 1080 x 2400 | `92a572fc6c16e952bc313873c6061746e4dff7276775b22f5037d7920edde751` |
| AURORA stage: follow-the-light lesson | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-lesson.png](orbital-aurora-lesson.png) | 1080 x 2400 | `510fb1ce15b369f8a35bbe2dc60c6dc6eb8589a3f768db83ea885cd352db030d` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
