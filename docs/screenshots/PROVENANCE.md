# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same nine-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`30a7c56918f6e51306941b34efe2744aef7bf602`](https://github.com/boop-bip80/Android-synth/commit/30a7c56918f6e51306941b34efe2744aef7bf602)
- Successful CI: [run #717](https://github.com/boop-bip80/Android-synth/actions/runs/41895231770)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `37a89ba3ae90242024ba2e430d1d2a9f5534aee2e50c21e6ca141e499b5f1e4e` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `9ab2efc1f40bfbfbfc482d69d422b45579126b965ca7298983d05ee649a6b68c` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `6f438fa0c2050c510d7dccad7bd583567c031673d8ba93957483af8b9a5390c4` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `e34946fc6da77468d33dad5e0da79a7c3cabd14a32b80add59b498722f127fe5` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `91387d8a6c16c9eb7172de88bccea0b48eefddeee075837d1871b6190e0c37f7` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
