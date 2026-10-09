# Developing Clickarr

## Requirements

- JDK 17 (Temurin recommended).
- Android SDK with platform 36 and build-tools 36 (or 35 for both). Set `ANDROID_HOME` or write `sdk.dir=...` into `local.properties`.
- A TV device with ADB debugging enabled, or an Android TV emulator image. Fire TV: Settings, My Fire TV, Developer options, ADB debugging.

No Android Studio is required; it is convenient but everything builds from the command line.

## Build and install

```bash
./gradlew assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build has application id `net.clickarr.debug`, so it installs beside a release build.

## Run checks

```bash
./gradlew detekt test        # static analysis + JVM unit tests (fast)
./gradlew :app:lintDebug     # Android lint
```

## Project layout

See [architecture.md](architecture.md#module-map). Pure-Kotlin modules (`core:*`) have no Android dependency and their tests run on the JVM in seconds.

## App flow (Phase 1)

Launch goes to Plex sign-in when no server is connected, otherwise straight to the player on the last channel. Back from the player opens the shell (Guide, Channels, Settings). Channels has the create-channel wizard. Settings holds the Phase 0 spikes.

## Phase 0 spikes

[spikes.md](spikes.md) explains each spike, how to drive it, and where to record results. They are reachable from Settings or with the `--es spike <name>` launch extra.

## Logging

Use `net.clickarr.core.common.Log`. It redacts tokens and routes to logcat under the `Clickarr/<tag>` prefix:

```bash
adb logcat -s 'Clickarr/*'
```
