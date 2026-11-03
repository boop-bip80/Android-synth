# ORBITAL physical Android release qualification

**Status: evidence required.** Passing the nine GitHub Actions jobs on API 35 emulators does not establish real-device output latency, speaker fidelity, reliable USB/Bluetooth MIDI, heat, battery impact, TalkBack usability, or production signing. Keep the commercial/Play Store gate open until actual phone measurements and review are recorded. This plan is designed to complement, not replace, `docs/QUALITY_GATES.md` and `docs/BUILD.md`.

## Before the session

1. Test an **APK built from the exact candidate commit**. Record the full source SHA, successful nine-job `main` run ID, APK SHA-256, package version, Android API level, phone model, firmware/build, audio route, and any external MIDI/audio equipment. Do not use the concept artwork as a screen capture.
2. Use at least one modern Android phone and a second different hardware/OS configuration before claiming general device compatibility. Test the documented minimum API 26 separately if that version remains a supported shipping target.
3. Record the initial battery percentage, thermal state, charging status, available storage and background audio apps. Do not use hidden developer or system-level privileges. Never upload a phone's personal logs, serial, contact details or notifications without review/redaction.
4. From a computer with Android SDK platform-tools, run `bash tools/collect-device-evidence.sh` once **before**, once **during** the sustained audio workload and once **after**. The read-only script writes to `~/ORBITAL-device-evidence` by default, not to the Git repository. Captures are diagnostic snapshots, not pass/fail results.

## Required device exercises

| Gate | Physical test | Evidence and failure policy |
| --- | --- | --- |
| **Audio/latency** | Cold-launch app, trigger single notes and chords from the real touchscreen on built-in speakers and wired/headphone/USB output where supported. Repeat at available 44.1/48 kHz routes. | Measure output onset, median/p95 touch-to-audio latency and jitter using a *documented external capture/loopback method*. Do not infer audible latency from `AudioTrack` buffer size or rendered-frame count. Log route and uncertainty. Any silence, severe click, stuck note or reproducible crash is a failure. |
| **Full-density stress** | For **30 minutes** on each selected output route, program and play 16 voices with eight oscillator slots, eight LFOs, 64 modulation routes and six live FX slots. Sweep parameters and alternate 192/256-frame render setups if instrumented. | Before/during/after `AudioTrack` underrun count, audio service diagnostics, CPU/memory, battery and thermal observations; listen and record external output where feasible. Missing metrics are **unverified**, not zero underruns. |
| **MIDI** | Attach and unplug real USB MIDI and, if supported, Bluetooth MIDI while notes/sustain/sostenuto/CC/pitch bend are active. Exercise two simultaneous controllers, CC64, CC66, CC120/121/123, rapid repeated notes, and panic. | No inherited stuck note, no surviving-controller cutoff, no stale bend on unplug, and no post-disconnect MIDI accepted. Note source ID/channel behavior and exact device make/model. Test the final integrated MIDI changes, not an older green build. |
| **Input/UX** | Ten-finger/multi-pointer piano and keyboardless FOCUS tapping, drag, cancellation, app background/foreground, device rotation and a compact/large-font setting. | Every touch that starts a note releases it on up/cancel/departure; all controls remain reachable; FOCUS exit restores live keyboard. Capture **actual device** screens with the tested source recorded. |
| **Lifecycle/routes** | Repeated launch/stop/restart; alarms, incoming-call or notification audio focus where safe, screen lock, speaker/wired switch, USB disconnect, low battery. | Track must recover or show an explicit error; no silent supposedly-running UI, leaked playback, hung renderer, or undismissable FOCUS screen. |
| **Accessibility** | Test TalkBack on a real device, large fonts, reduced-motion settings, keyboard navigation where applicable. | Screen reader announces meaningful controls and their values; FOCUS has a working accessible reveal/exit action. Emulator semantic assertions alone do not qualify this gate. |
| **Sound quality** | Record fixed reference patches and randomized extremes at 44.1/48 kHz; listen and inspect tuning, DC, distortion and high-note aliasing. | Store patch names, settings, rate, measured spectrum and audible observations. Architecture-inspired synthesizers are **not** certified circuit/ROM-accurate recreations. |
| **Persistence/data** | Save/import/overwrite/export/restore a user patch and sequencer, force-stop and relaunch, and corrupt one *test-only* bank file. | No silent overwrite, unintended data deletion, or patch migration loss. Use disposable test data, not a musician's existing library. |

**Thresholds:** no crash, data loss, stuck notes, or unrecoverable audio failure is acceptable in a qualification session. Numeric latency, CPU, underrun, thermal and battery thresholds must be stated in the release decision **before** a run is called a pass, along with the target device/route and measurement method. Publishing a pleasing number without a measurement method is not a pass. A 30-minute test establishes only that duration, not indefinite stability.

## Diagnostic capture and provenance

```bash
adb devices -l
# If several devices are attached, select the test target without recording
# its serial in the evidence filename:
export ANDROID_SERIAL='<device selected locally>'
bash tools/collect-device-evidence.sh
# Run again during sustained playback and again after the same session.
```

The script reads device OS/API/model/CPU ABI, app package/memory/rendering state, audio policy/flinger service, thermal service, and narrowly selected ORBITAL/crash log tags. It records individual capture success/failure and SHA-256 hashes. Some services differ or are restricted across Android versions; a missing capture is not a successful test. The script does **not** install anything, interact with touch/audio, record a waveform, report false latency numbers, or send data anywhere. All files are **local** and may still contain sensitive metadata: redact before sharing.

For actual audible latency, use a calibrated wired loopback or external high-speed capture method that timestamps the physical trigger and acoustic/electrical output. Record sample rate, output route, calibration offset, number of trials, raw onset distribution, median and p95. AudioTrack's `underrunCount` measures a different quantity; it cannot establish latency. If physical measurement gear is unavailable, mark latency **unverified**.

## Qualification record (one per device and APK)

| Field | Recorded value |
| --- | --- |
| Full source SHA / nine-job run URL / APK SHA-256 | **required** |
| App version, phone model, Android API/build, audio route | **required** |
| External USB/Bluetooth MIDI and audio gear | **required** (or `not tested`) |
| Latency method, calibration, trials, median/p95/jitter | **required** for latency pass |
| Underrun start/end, CPU/memory, thermal/battery observations | **required** for stress pass |
| 30-minute stress duration and actual live patch load | **required** for stress pass |
| Speaker/wired routing, MIDI, multitouch, TalkBack and recovery results | **required** or `unverified` |
| Reproducible defects and evidence links | **required** if found |
| Tester/date, decision, reviewer and explicit unresolved risks | **required** |

A release decision must link each record, the exact matching GitHub Release APK asset digest, five *genuine* API 35 emulator screenshots with provenance, and any actual-phone screenshots clearly labeled as such. Do not mark source CI green for a draft PR, a queued runner, or an older commit.

## Distribution boundary

The available source uses an Android debug signing key; that is for developer testing, not production. A commercial or Play Store release also needs a licensing decision, third-party notices, a privacy/data handling review, app metadata/icon/accessibility, versioning, Play-suitable signing/AAB, and repeatable production build evidence. These require deliberate owner and platform decisions. Physical-phone qualification cannot be replaced by more emulator CI jobs.
