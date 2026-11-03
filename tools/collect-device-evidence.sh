#!/usr/bin/env bash
# ORBITAL physical-device diagnostics. No uploads, installation or device changes.
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: collect-device-evidence.sh [OUTPUT_PARENT]

Capture read-only Android diagnostics for com.orbital.synth, using the adb on PATH.
Set ANDROID_SERIAL to target one device when more than one is attached.
Default output parent: ~/ORBITAL-device-evidence (outside the repository).
Run once before a performance test and again while the same patch is playing.

This does NOT measure touch-to-audio latency, prove speaker sound, or certify
real-time performance. Review/redact logs and metadata before sharing.
HELP
}
if [[ ${1:-} == --help || ${1:-} == -h ]]; then usage; exit 0; fi
if (( $# > 1 )); then usage >&2; exit 2; fi
if ! command -v adb >/dev/null 2>&1; then
  echo 'Android SDK platform-tools (adb) is required on PATH.' >&2
  exit 2
fi

adb_read() {
  if [[ -n ${ANDROID_SERIAL:-} ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

if [[ $(adb_read get-state 2>/dev/null || true) != device ]]; then
  echo 'No authorized Android device selected. Check adb devices and ANDROID_SERIAL.' >&2
  exit 2
fi

parent=${1:-"$HOME/ORBITAL-device-evidence"}
mkdir -p -- "$parent"
base="$parent/capture-$(date -u +%Y%m%dT%H%M%SZ)"
output=$base
counter=1
while ! mkdir -- "$output" 2>/dev/null; do
  counter=$((counter + 1))
  if (( counter > 100 )); then echo 'Could not allocate an evidence directory.' >&2; exit 1; fi
  output="$base-$counter"
done

# Keep partially unavailable services explicit; older Android releases do not
# provide the same thermal and audio-service diagnostics.
record() {
  local file=$1
  shift
  if adb_read "$@" > "$output/$file" 2>&1; then
    printf 'OK   %s\n' "$file" >> "$output/CAPTURE_STATUS.txt"
  else
    printf 'FAIL %s (service/permission/device may be unavailable)\n' "$file" >> "$output/CAPTURE_STATUS.txt"
  fi
}

printf '%s\n' 'ORBITAL physical Android diagnostics — NOT a test pass certificate' \
  "Captured at $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  'Package: com.orbital.synth' \
  'No APK SHA or source commit is inferred; record those separately.' \
  'Never publish raw evidence without reviewing it for personal information.' \
  > "$output/README.txt"

record android-api.txt shell getprop ro.build.version.sdk
record android-version.txt shell getprop ro.build.version.release
record device-model.txt shell getprop ro.product.model
record cpu-abi.txt shell getprop ro.product.cpu.abi
record app-package.txt shell dumpsys package com.orbital.synth
record app-memory.txt shell dumpsys meminfo com.orbital.synth
record app-rendering.txt shell dumpsys gfxinfo com.orbital.synth
record audio-flinger.txt shell dumpsys media.audio_flinger
record audio-policy.txt shell dumpsys media.audio_policy
record thermal-state.txt shell dumpsys thermalservice
record app-logs.txt logcat -d -v threadtime -t 2000 -s OrbitalAudio:V OrbitalMidi:V AndroidRuntime:E '*:S'

# Hash exact evidence bytes, without embedding the device serial in filenames.
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$output" && sha256sum -- *.txt > SHA256SUMS.txt)
elif command -v shasum >/dev/null 2>&1; then
  (cd "$output" && shasum -a 256 -- *.txt > SHA256SUMS.txt)
else
  echo 'No SHA-256 utility available; capture has no checksums.' >> "$output/CAPTURE_STATUS.txt"
fi
printf 'Evidence written to: %s\n' "$output"
