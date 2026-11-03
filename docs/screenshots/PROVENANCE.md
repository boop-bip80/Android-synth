# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same fully successful, all-required-gates GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`0494c2dcb39cad0feecd5808e26471c55f967564`](https://github.com/boop-bip80/Android-synth/commit/0494c2dcb39cad0feecd5808e26471c55f967564)
- Successful CI: [run #669](https://github.com/boop-bip80/Android-synth/actions/runs/41677043446)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `17e91543aaf9078ce29ae2f93936aebcf621583c87942aa5ea4b6388c65a88b6` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `216edc22117c73204b91e600d86fa123acc045c26a58cf2743a101964b107d19` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `5d1f776184e0851ee26103eab8d9fffdc984fe5b54480392025e0c71924a9f98` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `dd7c0d9cde8b4989d5fce823ebe31062f8d3a9cb3c4c81b5328e78a3ba97d310` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `34ac82668b7d3b46381a58fc2d44ddb8da5b461d76da67fcaee3795bf5e79b8e` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
