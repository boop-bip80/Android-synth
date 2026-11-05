# Aurora stage preview

A tiny developer tool that renders the **exact** Aurora stage simulation
(`core/.../stage/glow/AuroraSim`) headlessly to PNG, so the touch-surface visuals can
be iterated on in seconds without an Android emulator.

The simulation draws only through the `GlowCanvas` interface. The Android app
implements it with Compose `DrawScope`; this tool implements it with plain Java2D
(no dependencies beyond the JDK). Anti-aliasing and gradient falloff differ slightly
from the phone, so treat these frames as a design aid, not as proof of how a device
looks — real emulator screenshots are produced by CI.

```sh
gradle -p tools/stage-preview render      # JDK 17+, writes tools/stage-preview/build/frames/*.png
```

Every scenario is written twice, `name.png` (dark) and `name-light.png` (normal mode, via
`GlowTheme.LIGHT`), except the black FOCUS canvas which has no light variant by design.

Scenarios (see `PreviewMain.kt`): idle surface, a melodic slide, a three-finger chord,
the "follow the light" guide, all twelve key palettes, the black FOCUS canvas
before/after a touch, a celebration finale and dense two-handed play.

This project is intentionally **not** included in the root `settings.gradle.kts`, so it
never affects the app build or CI.
