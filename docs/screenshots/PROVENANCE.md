# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same nine-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`ff0797c514315cf787e25c4b80feff7664748419`](https://github.com/boop-bip80/Android-synth/commit/ff0797c514315cf787e25c4b80feff7664748419)
- Successful CI: [run #746](https://github.com/boop-bip80/Android-synth/actions/runs/41972730526)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `af51953ea7fa5d4f0597787c0015d722fbe2725a561269bc1a2e7a7c304b01fc` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `030026ba1fa0a4a0e95a99831b3b2a4d74c629330e88e4429f836e7afa454f8b` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `170b914cfa6a4403db32f6b8c152901dd6be5cc0c6736abe5fbf6cdf6d658ccb` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `6991a512d372ecfcd7b34844ffad4c09e67dcaaa09d5fd7659895127d9c013ce` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `8dd0fb8df1e039734518f6317256f6d35b2c6aba7e2b01b297a27f7da622f2f7` |
| AURORA stage: held chord, live motion | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-play.png](orbital-aurora-play.png) | 1080 x 2400 | `9a21335a3e373e64af632cca08d99c2fd3ac96868630c480c8dd2e71e5f159a5` |
| AURORA stage: follow-the-light lesson | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-lesson.png](orbital-aurora-lesson.png) | 1080 x 2400 | `baa3c24e10c0130f2bf547114cd7697deb6123388494f7931d734b68f3c5178f` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
