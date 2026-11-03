#!/usr/bin/env bash
# Install exactly the SDK packages required by reactivecircus/android-emulator-runner@v2
# before its one-shot SDK installer. Retry only transient SDK download/provisioning
# failures; this script never reruns or suppresses an instrumentation assertion.
set -Eeuo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must be set by android-actions/setup-android}"
sdk_root="${ANDROID_HOME%/}"
if [[ ! -d "$sdk_root" ]]; then
  echo "::error::Android SDK root does not exist: $sdk_root" >&2
  exit 1
fi
command -v sdkmanager >/dev/null || {
  echo "::error::sdkmanager is not on PATH" >&2
  exit 1
}

# Match the emulator-runner v2 SDK installer (src/sdk-installer.ts):
# build tools 37.0.0, API 35 Google APIs x86_64 image, emulator stable channel.
packages=(
  'build-tools;37.0.0'
  'platform-tools'
  'platforms;android-35'
  'emulator'
  'system-images;android-35;google_apis;x86_64'
)

has_installed_package() {
  local listing="$1" package="$2"
  awk -F '|' -v wanted="$package" '
    NF >= 2 {
      path = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", path)
      if (path == wanted) found = 1
    }
    END { exit !found }
  ' <<< "$listing"
}

verify_sdk() {
  local listing package emulator_bin emulator_version image_dir
  if ! listing="$(sdkmanager --list_installed --channel=0)"; then
    echo "::warning::Unable to read installed SDK package metadata" >&2
    return 1
  fi

  for package in "${packages[@]}"; do
    if ! has_installed_package "$listing" "$package"; then
      echo "::warning::SDK package not installed: $package" >&2
      return 1
    fi
  done

  emulator_bin="$sdk_root/emulator/emulator"
  if [[ ! -x "$emulator_bin" || ! -s "$emulator_bin" ]]; then
    echo "::warning::Emulator binary missing, empty or non-executable" >&2
    return 1
  fi
  # Before the action creates an AVD or prepares KVM, a headless `-version`
  # may fail even for an intact SDK executable. Validate its ELF header and
  # package metadata here; actual runtime viability is proven by the mandatory
  # reactivecircus emulator boot and the unchanged instrumentation gates.
  if [[ ! -s "$sdk_root/emulator/source.properties" ]]; then
    echo "::warning::Emulator package metadata is missing" >&2
    return 1
  fi
  local elf_magic
  elf_magic="$(od -An -tx1 -N4 "$emulator_bin" | tr -d '[:space:]')"
  if [[ "$elf_magic" != "7f454c46" ]]; then
    echo "::warning::Emulator binary does not have an ELF executable header" >&2
    return 1
  fi
  local probe_exit=0
  emulator_version="$("$emulator_bin" -version 2>&1)" || probe_exit=$?
  if (( probe_exit != 0 )); then
    echo "::notice::Headless emulator -version returned $probe_exit before AVD/KVM setup: ${emulator_version:0:400}"
  else
    echo "::notice::Emulator version: ${emulator_version:0:200}"
  fi

  image_dir="$sdk_root/system-images/android-35/google_apis/x86_64"
  if [[ ! -s "$image_dir/source.properties" || ! -s "$image_dir/system.img" ]]; then
    echo "::warning::API 35 Google APIs x86_64 system image is incomplete" >&2
    return 1
  fi
  if [[ ! -s "$sdk_root/platforms/android-35/source.properties" ]]; then
    echo "::warning::API 35 platform metadata is missing" >&2
    return 1
  fi
  echo "Verified installed Android SDK packages, emulator ELF/package integrity, and API 35 system image."
}

for attempt in 1 2 3; do
  echo "::group::Android SDK emulator provisioning (attempt $attempt/3)"
  if sdkmanager --install "${packages[@]}" --channel=0 && verify_sdk; then
    echo "::endgroup::"
    exit 0
  fi
  echo "::warning::SDK provisioning attempt $attempt/3 failed"
  echo "::endgroup::"
  if (( attempt < 3 )); then
    sleep "$(( attempt * 5 ))"
  fi
done

echo "::error::Android SDK emulator provisioning failed after three attempts; instrumentation was not run." >&2
exit 1
