# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same nine-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`4f2ae581492a193b9e9587e286194b0cdac37f6e`](https://github.com/boop-bip80/Android-synth/commit/4f2ae581492a193b9e9587e286194b0cdac37f6e)
- Successful CI: [run #730](https://github.com/boop-bip80/Android-synth/actions/runs/41942904837)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `128c4423151973593eb87e0c605b6fd3551dbdd6da6fe7131fc5b410773d425b` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `68ad6c6675053257950ce3bea5880a180b428544234398a575b6a2a1e93bd7b4` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `755285619d555d228182ce710968867ff3faab08328c14710c42ac0afa14d8e2` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `333d24889712e3567846a58204b9cd3a4688f5c6547e6e990e4809e35e42174c` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `be4784fb13843262d27532898b2331418fe924e818ab1eeb9ac742928dc91b7f` |
| AURORA stage: held chord, live motion | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-play.png](orbital-aurora-play.png) | 1080 x 2400 | `a0307d44e4509af70b30280caf9baa7037b84ffcb3ca5fa767e39f194f1f54a4` |
| AURORA stage: follow-the-light lesson | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-lesson.png](orbital-aurora-lesson.png) | 1080 x 2400 | `b438b64b86ba928d38779783a6de6ab6148aaf74700408833c7612c7b2c0ac7b` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
