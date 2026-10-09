# Phase 0 spikes

Each spike answers one question from [the risk list](architecture-proposal.md#19-major-technical-risks-and-unknowns). The app's Phase 0 launcher opens them. Record results in the tables below; when all four have a verdict, the `:spike` module is deleted and ADRs are amended where needed.

Install the debug APK, then either pick a spike from the menu or launch one directly:

```bash
adb shell am start -n net.clickarr.debug/net.clickarr.MainActivity --es spike guide
```

## A. Guide skeleton (Compose for TV performance)

**Question.** Can a Compose for TV guide with 50 channel rows and a 3-hour window hold 60 fps on a 2018 Fire TV Stick 4K?

**How.** Open the spike, hold D-pad down to scroll through all rows, then D-pad right across cells. Read the frame counter at the top. Also record cold start: `adb shell am start -W -n net.clickarr.debug/net.clickarr.MainActivity` prints `TotalTime`.

**Pass.** Janky frames under 5 percent, worst frame under 100 ms, cold start under 4000 ms.

| Device | OS | Frames | Janky % | Worst ms | Cold start ms | Verdict |
|---|---|---|---|---|---|---|
| Fire TV Stick 4K (2018) | Fire OS 6 | | | | | |
| Fire TV Stick 4K Max | Fire OS 7/8 | | | | | |
| Nvidia Shield | Android TV 11 | | | | | |
| Chromecast with Google TV | Android TV 12+ | | | | | |

## B. Playback at an offset (ExoPlayer tune-in)

**Question.** How long from `prepare()` to first frame when starting at an arbitrary offset, for direct play and for an HLS transcode, from Plex?

**How.** Get a direct-play URL and a transcode URL from Plex (proposal 6.2 lists the endpoints). Launch:

```bash
adb shell am start -n net.clickarr.debug/net.clickarr.MainActivity \
  --es spike player --es url "<url>" --ei offsetSec 1020
```

The overlay reports time to first frame, the position actually reached, decoder, and format. Tokens are redacted on screen.

**Pass.** Direct play first frame under 1500 ms on LAN; position within 1 s of requested. Transcode first frame under 6000 ms; position within 2 s.

| Device | Server | Mode | First frame ms | Reached s (asked) | Decoder | Verdict |
|---|---|---|---|---|---|---|
| | Plex | direct | | | | |
| | Plex | HLS transcode | | | | |

## C. TLS household server (Netty + Android Keystore)

**Question.** Does Ktor's Netty engine serve TLS on this device from a certificate whose key lives in the Android Keystore, and does a pinned client connect? If not, does the software-certificate fallback work?

**How.** Open the spike. Press the three buttons in turn. Each starts a server and runs a loopback self-test with a trust manager that accepts only that server's key. Also connect from a laptop to confirm it is reachable on the LAN:

```bash
openssl s_client -connect <tv-ip>:47831 </dev/null 2>/dev/null | openssl x509 -noout -subject -fingerprint -sha256
curl -k https://<tv-ip>:47831/v1/info
```

**Pass.** "Keystore TLS self-test OK" on every device in the matrix. If only "Software TLS" passes, ADR 0013 keeps TLS with the software path. If neither passes, ADR 0013's plaintext fallback (option B) activates and ADR 0004 switches the engine to CIO.

| Device | OS | Keystore TLS | Software TLS | Plain HTTP | Self-test ms | APK size delta | Verdict |
|---|---|---|---|---|---|---|---|
| Fire TV Stick 4K (2018) | Fire OS 6 | | | | | | |
| Fire TV Stick 4K Max | Fire OS 7/8 | | | | | | |
| Android TV 12+ device | | | | | | | |

### Finding C1 (2026-10-09, from CI, before any device test)

D8 refuses to dex Netty 4.2 below API 26 ("Increase the minSdkVersion to 26 or above": Netty uses `java.lang.invoke`). The Ktor 3.2.0 client failed the same way, which is at least partly a separate Ktor 3.2.0/3.2.1 D8 bug fixed in 3.2.2 (per the [Ktor 3.2.2 release notes](https://blog.jetbrains.com/kotlin/2025/07/ktor-3-2-0-is-now-available-2/)). Ktor server core and the CIO engine dex fine on 25.

Consequences applied:
- The spike terminates TLS with the platform `SSLServerSocket` (`SslHttpServer.kt`) instead of Netty, which is a purer test of the Keystore question anyway.
- Clients use OkHttp directly (already present through Media3). The Ktor client is out of the catalog.
- Ktor bumped to 3.2.3.

Decision for the maintainer, with data from the device matrix: keep minSdk 25 and build the coordinator as Ktor CIO behind an in-process TLS acceptor (or NanoHTTPD HTTPS), or raise minSdk to 26, drop Fire OS 6 devices (2018 Fire TV Stick 4K, 2018 to 2020 Fire TV Edition sets), and use Netty. ADR 0001 and ADR 0004 carry the note.

## D. LAN discovery (NsdManager)

**Question.** Do two devices discover each other's `_clickarr._tcp` service within 10 s, including a Fire TV, and does resolution return the TXT record?

**How.** On device 1 press Advertise. On device 2 press Browse. Swap. Watch the log lines. Try with the TV on Wi-Fi and the other device on Ethernet if available.

**Pass.** Found and resolved with the TXT attributes within 10 s in both directions.

| Advertiser | Browser | Found s | Resolved | TXT ok | Notes |
|---|---|---|---|---|---|
| | | | | | |

## Decisions pending on these results

- A fails: baseline profiles and lighter guide cells first; Leanback for the guide only as a last resort (ADR 0002).
- B transcode fails the latency bar: nothing changes architecturally; the device-profile work in Phase 3 gets priority so direct play dominates.
- C fails both TLS paths: ADR 0013 option B, ADR 0004 engine CIO.
- D fails: manual address entry becomes the primary flow and a UDP beacon is evaluated (ADR 0012).
