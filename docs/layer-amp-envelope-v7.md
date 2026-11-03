# Optional per-oscillator VCA / ADSR (V7 draft)

This branch is deliberately isolated and **not part of the currently released APK**.
It depends on the unmerged real OSC1–8 gain/pitch branch
`feature/real-per-osc-modulation-20261103` at `706ba226`.

## DSP and patch contract

- Eight independent additive OSC layers per Voice have optional normalized
  `LayerAmpEnvelope(enabled, attack, decay, sustain, release)`. All default
  to `enabled=false` (exact original V6 wavetable/noise/filter/PCM arithmetic).
- On gate-on, an enabled layer attacks from zero, decays to its sustain target,
  and on gate-off releases from its actual running level. Layer VCAs are
  multiplied **inside** the existing master Voice ADSR; a short master release
  truncates a longer layer release. No independent longer polyphony/tails.
- Time maps at sample rate: attack 1–4001ms, decay 5–3005ms, release
  5–4005ms using quadratic normalized controls. Sustain is linear 0–100%.
  Toggling envelope enable/bypass while notes play crossfades over ~8ms;
  changing sustain uses the same slew. No per-sample heap allocation.
- All eight envelope banks are preallocated per Voice (eight primitive lanes
  each), so a 16-voice device configuration has at most **128 active envelopes**.
  The public core also permits up to 64 Voices; configurations above 16 can
  use up to 512 lanes, **not validated for phone thermal/latency**.
- Live UI edits are immutable OscillatorSlot snapshots; apply worker side at
  block boundaries. Legacy seven-parameter setter preserves VCA and V6 noise values rather than reverting them.
- V7 is emitted only for non-default envelope config, including bypassed but
  edited metadata. V1–V6 headers and unchanged wire bytes remain unchanged.
  V7 oscillator rows extend V6's eight fields to 13 (V6 noise preserved,
  plus amp-enabled, A/D/S/R). Decode enforces finite values and strict flags.
  Recall of any older patch clears prior VCA configuration.
- The new Compose envelope contour is intentionally **illustrative programmed
  ADSR state**, not measured oscilloscope, FFT or microphone capture. Four
  accessible >=48dp sliders and an on/off switch publish full immutable slots.

## Verification before release

Additive bank tests cover exact bypass, per-sample stage math, click-free
transition, two render-block partitions, and 16x8 stress.
Actual OrbitalSynth tests cover five synthesis engines, V6 colored noise,
OSC gain/pitch route bypass, bit-exact stereo PCM in 192/512 frames, 16-voice
stealing, safety and rapid UI edits. V7 codec tests include V1–V6 exact
roundtrip, malformed field rejection and physical disk load; Compose tests
exercise real tap/drag plus TalkBack SetProgress.

**Status:** These are authored offline tests, not claimed passing. Local
Gradle/Android execution is not available in the current environment. This
draft must receive host JVM + all emulator CI gates on a future dedicated PR
before merge, plus actual midrange-phone CPU profiling. At 48k x 16 voices x
8 layers, the oscillator itself may require 6.144 million layer updates/second
before FX and unison; “unlimited layers” would be irresponsible.
