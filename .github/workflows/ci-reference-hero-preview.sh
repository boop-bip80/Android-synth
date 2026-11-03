#!/usr/bin/env bash
# Isolated, honest screenshot of the new reference hero Compose component.
# This captures a real API35 UiAutomation framebuffer of actual AGSL/Canvas
# production Composables, NOT proof the MainActivity hosts them yet.
set -euo pipefail

mkdir -p artifacts
app_apk="$(find artifacts/hero-debug-apk -type f -name '*.apk' -print -quit)"
test_apk="$(find artifacts/hero-debug-test-apk -type f -name '*.apk' -print -quit)"
test -n "$app_apk" && test -s "$app_apk"
test -n "$test_apk" && test -s "$test_apk"
printf 'app_sha256=%s\ntest_sha256=%s\n' \
  "$(sha256sum "$app_apk" | cut -d' ' -f1)" \
  "$(sha256sum "$test_apk" | cut -d' ' -f1)" \
  > artifacts/reference-hero-provenance.txt

timeout 90s adb install -r "$app_apk" > artifacts/reference-hero-install.txt
timeout 90s adb install -r "$test_apk" > artifacts/reference-hero-test-install.txt
timeout 150s adb shell am instrument -w \
  -e class 'com.orbital.synth.ui.ReferenceSoundHeroCaptureTest#captureActualAgslPlasmaAndLiveWidgetLayoutBeforeHostIntegration' \
  com.orbital.synth.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/reference-hero-instrumentation.txt

grep -Eq '^OK \(1 test\)' artifacts/reference-hero-instrumentation.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' \
  artifacts/reference-hero-instrumentation.txt; then
  echo "Real screenshot capture instrumentation failed" >&2
  exit 1
fi
timeout 30s adb exec-out run-as com.orbital.synth \
  cat files/orbital-reference-hero-standalone.png \
  > artifacts/reference-sound-hero-standalone.png
test -s artifacts/reference-sound-hero-standalone.png
python3 - <<'PY'
import hashlib
import struct
from pathlib import Path
p=Path('artifacts/reference-sound-hero-standalone.png')
data=p.read_bytes()
assert data.startswith(b'\x89PNG\r\n\x1a\n'), 'Not real PNG bytes'
w,h=struct.unpack('>II',data[16:24])
assert w >= 600 and h >= 1000 and h>w,(w,h)
assert len(data)>4096
with open('artifacts/reference-hero-provenance.txt','a') as out:
    out.write(f'screenshot_dimensions={w}x{h}\n')
    out.write(f'screenshot_bytes={len(data)}\n')
    out.write(f'screenshot_sha256={hashlib.sha256(data).hexdigest()}\n')
print(f'Genuine stand-alone hero screenshot captured: {w}x{h}, {len(data)} bytes')
PY
