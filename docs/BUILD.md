# Building and verifying ORBITAL

## Pinned toolchain

- JDK 17
- Gradle 8.11.1 (run with the installed Gradle CLI; this repository intentionally has no unverified wrapper binary)
- Android Gradle Plugin 8.9.2
- Kotlin / Compose Compiler Gradle Plugin 2.1.20
- Android SDK platform 35, build-tools 35.0.0
- Compose BOM 2025.04.00
- Minimum Android API 26; compile / target API 35

Android documentation lists Gradle 8.11.1 and JDK 17 for AGP 8.9. Kotlin 2.x uses its dedicated Compose compiler Gradle plugin.

## Local build (optional)

Install Android SDK 35, JDK 17 and Gradle 8.11.1. Configure the local SDK using `ANDROID_HOME` or a Git-ignored `local.properties`.

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The resulting debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Android 26+ device required. The debug APK is installable; it is not Play Store signed.

## CI emulator (no local emulator required)

Open [Actions](../.github/workflows/android.yml) > **Android — build, JVM tests and emulator**. Every push to `main`, pull request and manual dispatch runs:

1. Pure Kotlin DSP/music unit tests, app unit tests, Android lint and debug APK assembly.
2. Android 35 x86_64 emulator startup, instrumentation smoke tests, real app launch via `adb shell am start -W`.
3. Real `adb shell screencap` captured after launch.

Artifacts (14-day retention):
- `orbital-debug-apk`: installable debug APK.
- `orbital-api35-emulator-screenshot`: actual screen from an emulated Android device, *not* the original concept artwork.
- `orbital-android-test-results`: instrumentation XML/HTML reports, if generated.
- `orbital-lint`: static analysis HTML, if generated.

Emulator startup/build can take many minutes. Passing CI verifies API 35 emulation, not physical-device latency, USB MIDI, performance, battery, or audio quality. Test those on real devices before shipping.

## Troubleshooting

- `SDK location not found`: install Android SDK 35 and set `ANDROID_HOME` or create local.properties (do not commit SDK paths).
- `Gradle requires Java 17`: install and select JDK 17.
- `No emulator tests found`: verify the `:app` module's `androidTest` sources and test runner; the CI job should fail rather than pretend a pass.
- Audio on emulator is not a proxy for low-latency hardware.
