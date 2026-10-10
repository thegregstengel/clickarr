# Developing Clickarr

## Requirements

- JDK 17 (Temurin recommended).
- Android SDK with platform 36 and build-tools 36 (or 35 for both). Set `ANDROID_HOME` or write `sdk.dir=...` into `local.properties`.
- A TV device with ADB debugging enabled, or an Android TV emulator image. Fire TV: Settings, My Fire TV, Developer options, ADB debugging.

No Android Studio is required; it is convenient but everything builds from the command line.

## Picking up on another machine

Everything lives in the repo; nothing on a developer machine is special. On a new box:

1. Install a JDK 17 and the Android SDK (platform 36, build-tools 36). Android Studio does this for you, or use
   `sdkmanager` from the command-line tools.
2. `git clone https://github.com/thegregstengel/clickarr && cd clickarr`
3. Write `local.properties` with `sdk.dir=/path/to/Android/Sdk` (gitignored, machine-specific).
4. `./gradlew assembleDebug` to confirm the toolchain, then open the folder in Android Studio if you like.
5. `gh auth login` if you want to watch CI and download artifacts from the terminal.

Nightly APKs and the emulator screenshots come from GitHub Actions regardless of where you develop, so a
machine without a working emulator can still see the app run (see the emulator section below).

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

Launch goes to Plex sign-in when no server is connected, otherwise straight to the player on the last channel. Back from the player opens the shell (Guide, Channels, Favorites, Settings). Channels has the create-channel wizard and the favorite toggle; Favorites is the guide filtered to favorites. Settings has General, Media Server (disconnect), Channels (refresh lineups), Appearance (overlay timeout), Playback (profile), Household (create, add a device with a PIN, join by discovery or address, member status, leave), Diagnostics (schedule math and recent redacted log, the thing to quote in bug reports), and About (version, Phase 0 spikes).

## Phase 0 spikes

[spikes.md](spikes.md) explains each spike, how to drive it, and where to record results. They are reachable from Settings or with the `--es spike <name>` launch extra.

## Logging

Use `net.clickarr.core.common.Log`. It redacts tokens and routes to logcat under the `Clickarr/<tag>` prefix:

```bash
adb logcat -s 'Clickarr/*'
```

## Emulator runs in GitHub Actions

`.github/workflows/emulator.yml` boots an Android TV emulator (API 36, the only level with a 64-bit TV image, `tv_1080p`, KVM-accelerated) on
every push to `main` that touches code, installs the debug APK, and runs `app/src/androidTest`. The
test `FirstRunFlowTest` walks the real first-run path against a fake Plex server that runs inside the
test process (`provider:plex-fixtures`): manual connect, create a channel from a show, tune, overlay,
guide. The run uploads an `emulator-run` artifact with numbered screenshots, a screen recording,
redacted logcat, and the HTML test report. Open the latest run under Actions, download the artifact,
and look at the PNGs to see what the app actually drew.

Limits: Fire OS has no emulator, so Fire TV behavior still needs a real stick; the fixture server has
no video file, so playback ends in the "unavailable" card by design (the overlay and schedule math are
what the test checks).

Run it locally with any Android TV emulator or device attached:

```bash
./gradlew :app:connectedDebugAndroidTest
adb pull /sdcard/Pictures/clickarr ./screenshots
```

## Households (Phase 2)

Modules: `household:protocol` (wire types, pairing proof, state reducer), `household:coordinator` (state machine
and Ktor routes), `household:client` (OkHttp member client), `household:discovery` (Android Keystore identity and
NSD), and the glue in `data` (`HouseholdService`, `RoomCoordinatorStore`). The coordinator runs Ktor's CIO engine
on port 47831 (or a free port, advertised over mDNS) and the member keeps a cached copy of the household document
in the same Room tables the player reads.

Transport in this build is plain LAN HTTP. ADR 0013's TLS design (Keystore certificate, trust-on-first-use pinning)
is implemented as far as the pairing proof, which already binds both certificate fingerprints; the TLS acceptor is
added once spike C reports which path works on Fire OS. Until then, do not pair across untrusted networks.

To try it with two devices on one LAN: on TV A open Settings, Household, Create a household, then Add a device and
read the code. On TV B open Settings, Household, Join a household, pick TV A from the list (or type its address),
enter the code, Join. Both TVs now show the same channels and the same program at the same offset.

The emulator test also exercises the member side without a second device: `app/src/androidTest/.../TestCoordinator.kt`
runs a coordinator with one movie channel inside the test process on a loopback port, and `FirstRunFlowTest` dissolves
the emulator's own household, joins that coordinator by address and PIN, and checks the synced channel shows up on the
Channels tab (screens 15 to 17). Joining by a typed address trusts the fingerprint the coordinator states on first
use; joining from the discovery list checks the stated fingerprint against the one that was advertised.

