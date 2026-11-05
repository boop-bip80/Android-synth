# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same nine-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`54eeff57710b7b1b9adbb3a6d61a7d701ee956ed`](https://github.com/boop-bip80/Android-synth/commit/54eeff57710b7b1b9adbb3a6d61a7d701ee956ed)
- Successful CI: [run #738](https://github.com/boop-bip80/Android-synth/actions/runs/41958140685)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `10fb6a191fdba27886c0d224b00c76769edbe6ce0b2b77248640794f537226bb` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `070eda57fdc494a1695bc506854ab3ed43e8dea0d5f0a06a2b09a2953772add8` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `4cf116eff82310c442059e4a033b9fd054bf0a78f9c09933f784e3e0232085fc` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `4fa83e50434393cde93c418e61826caa4dd3600becdc51de63bac60251d8ff35` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `ba1f5b480c308c46899044025150e9583a07f057587b8d9cc58d30a84ba99e70` |
| AURORA stage: held chord, live motion | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-play.png](orbital-aurora-play.png) | 1080 x 2400 | `25351e43dcbb1231d554af81985e28322ba4edb443efec4a57b61ed1d8051a3b` |
| AURORA stage: follow-the-light lesson | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-lesson.png](orbital-aurora-lesson.png) | 1080 x 2400 | `e46d962690ebd2247861ff75a2959e35a48f8981a8e020772e2e422b54b6a2f9` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
