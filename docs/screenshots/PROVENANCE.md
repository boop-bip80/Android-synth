# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same five-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`e81dd29f3c16dea16e46d10103c622374dcb127a`](https://github.com/boop-bip80/Android-synth/commit/e81dd29f3c16dea16e46d10103c622374dcb127a)
- Successful CI: [run #556](https://github.com/boop-bip80/Android-synth/actions/runs/41623944286)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `83644fde4474497affc9eec7ce8b3ac86d3f7034b9d43bfe2ca12037c45161f8` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `5313507d1dda018076a3fe555fc8d6eafd202dc4566fe31296fd8d70eb63349e` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `1cb09e1a461336723c6876a9b6efc46d407516eceb08d6ee7d6097c0a03bd76d` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `ab5ae3ab87c00d3c47f87ea5d4b3e46cc9180be1035c696455a453550dfcc035` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `5aea29c57b1e464c7c90bb821beb2b9c32319249ad587637c576f59fd72773f0` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
