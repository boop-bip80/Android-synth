# Real ORBITAL emulator framebuffers

These images are genuine screenshots captured by Android API 35 x86_64
from the debug APK in the same nine-job fully successful GitHub Actions run.
They are not the generated concept reference or a claim of physical-device testing.

- Verified source commit: [`ea0e295cde9a1fc2b86f0c5829b15ecb82341f5a`](https://github.com/boop-bip80/Android-synth/commit/ea0e295cde9a1fc2b86f0c5829b15ecb82341f5a)
- Successful CI: [run #734](https://github.com/boop-bip80/Android-synth/actions/runs/41952040531)
- Source artifact names: orbital-api35-compact-emulator-screenshot,
  orbital-api35-stage-emulator-screenshot,
  orbital-api35-visuals-emulator-screenshot.
- The portrait is from the stage lane's strict real-keyboard normal
  bootstrap, not the looser general screenshot artifact.

| Actual app state | Verified source artifact | File | Pixels | SHA-256 |
| --- | --- | --- | ---: | --- |
| Normal portrait (strict stage bootstrap) | `orbital-api35-stage-emulator-screenshot` | [orbital-portrait.png](orbital-portrait.png) | 1080 x 2400 | `a08a66d9077a1828e0616a1da1934eb5712ef4ec3ffa30051bc846a7ec0642c2` |
| 360 x 350dp compact SOUND | `orbital-api35-compact-emulator-screenshot` | [orbital-compact.png](orbital-compact.png) | 945 x 918 | `ad5caccc78969988bf3d9cb6b329cdbe256ce025c1d76c4f6b178606ccf10e4a` |
| FOCUS live held-note | `orbital-api35-stage-emulator-screenshot` | [orbital-focus-held-note.png](orbital-focus-held-note.png) | 1080 x 2400 | `0626757b3ab4c9b266f73066c15808f29ef9d0f86ef0b5bbad2aacdc953ba0a4` |
| OSC1 editing | `orbital-api35-visuals-emulator-screenshot` | [orbital-oscillator-workbench.png](orbital-oscillator-workbench.png) | 1080 x 2400 | `1a3fc033e744fced4f8cf2743166bf3592fb822a4bc078cb60a69d79966068c4` |
| Landscape | `orbital-api35-visuals-emulator-screenshot` | [orbital-landscape.png](orbital-landscape.png) | 2400 x 1080 | `40be134422fe96a61f1890cfe9393978d5f7897f39933414f4983b40ab8ef72b` |
| AURORA stage: held chord, live motion | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-play.png](orbital-aurora-play.png) | 1080 x 2400 | `5f29c4b5d3589cf16db9d4c11f6362cb8929d81879039d5d6d04060c99f9d4e4` |
| AURORA stage: follow-the-light lesson | `orbital-api35-visuals-emulator-screenshot` | [orbital-aurora-lesson.png](orbital-aurora-lesson.png) | 1080 x 2400 | `f3aed8ad290b17237b4d49349aaff40254d29be7868b978085fe53e3fd80bde0` |

The concept design remains at [design/orbital-ui-reference.png](../../design/orbital-ui-reference.png).
Physical Android audio latency, thermals, MIDI and accessibility remain separate device-level gates.
