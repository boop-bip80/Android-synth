#!/usr/bin/env bash
# Genuine Android framebuffers of the AURORA stage with real motion (sparks, ripples, comet
# trails) and a "follow the light" lesson. Runs AFTER ci-stage-bootstrap.sh so the production
# MainActivity already owns a focused display. Nothing is rendered or substituted on the host.
#
# NON-BLOCKING by design: these images illustrate the app; an optional capture must never hold
# back a release that otherwise passes every functional gate. On any failure this script
# records why in artifacts/aurora-gallery-summary.txt and exits 0, and the screenshot
# publisher only publishes gallery images that really exist.
set -uo pipefail
mkdir -p artifacts
summary=artifacts/aurora-gallery-summary.txt
printf 'run=%s\ncommit=%s\n' "${GITHUB_RUN_ID:-local}" "${GITHUB_SHA:-unknown}" > "$summary"

fail_soft() {
  printf 'result=skipped\nreason=%s\n' "$1" >> "$summary"
  echo "AURORA gallery skipped (non-blocking): $1" >&2
  exit 0
}

timeout 12s adb shell am force-stop com.orbital.synth >/dev/null 2>&1 || true
timeout 30s adb shell am start -n com.orbital.synth/.MainActivity > artifacts/aurora-gallery-launch.txt 2>&1 || fail_soft "could not launch MainActivity"
sleep 4

timeout 240s adb logcat -v threadtime > artifacts/aurora-gallery-logcat.txt 2>&1 &
logcat_pid=$!
timeout 200s adb shell am instrument -w -r \
  -e class 'com.orbital.synth.ui.AuroraGalleryCaptureTest#capturesTheLivingStageAndALesson' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/aurora-gallery-instrumentation.txt
kill "$logcat_pid" >/dev/null 2>&1 || true

grep -Eq 'OK \(1 test\)' artifacts/aurora-gallery-instrumentation.txt || fail_soft "instrumented capture did not pass"

for frame in orbital-aurora-play.png orbital-aurora-lesson.png; do
  extracted=0
  for attempt in 1 2; do
    if timeout 30s adb exec-out run-as com.orbital.synth cat "files/$frame" > "artifacts/$frame" &&
       test -s "artifacts/$frame" &&
       test "$(file -b --mime-type "artifacts/$frame")" = image/png; then
      extracted=1
      break
    fi
    sleep 2
  done
  [[ "$extracted" == 1 ]] || fail_soft "could not extract $frame"
done

# Evidence the frames really show a lit, colourful stage rather than a blank or foreign window.
python3 - artifacts/orbital-aurora-play.png artifacts/orbital-aurora-lesson.png <<'PY' >> "$summary" || fail_soft "frame evidence check failed"
import struct
import sys
import zlib

def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, width, height, idat = 8, 0, 0, b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and color in (2, 6), "unexpected PNG format"
        elif kind == b"IDAT":
            idat += body
        pos += length + 12
    channels = 4 if color == 6 else 3
    raw = zlib.decompress(idat)
    stride = width * channels
    rows, prev = [], bytearray(stride)
    i = 0
    for _ in range(height):
        filt = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = prev[x]
            c = prev[x - channels] if x >= channels else 0
            if filt == 1: line[x] = (line[x] + a) & 255
            elif filt == 2: line[x] = (line[x] + b) & 255
            elif filt == 3: line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif filt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append(line); prev = line
    return width, height, channels, rows

for path in sys.argv[1:]:
    width, height, channels, rows = read_png(path)
    vivid = 0
    samples = 0
    for y in range(int(height * 0.12), int(height * 0.88), 6):
        row = rows[y]
        for x in range(0, width, 5):
            r, g, b = row[x * channels], row[x * channels + 1], row[x * channels + 2]
            samples += 1
            if max(r, g, b) >= 120 and max(r, g, b) - min(r, g, b) >= 50:
                vivid += 1
    share = vivid / max(1, samples)
    print(f"{path}: {width}x{height} vivid_share={share:.4f}")
    assert share > 0.004, f"{path} does not look like a lit, colourful stage"
print("result=captured")
PY
echo "AURORA gallery frames captured." >&2
