# Security policy

Clickarr runs on televisions on a home LAN and holds credentials for your media server. Please report vulnerabilities privately.

## Reporting

Open a GitHub Security Advisory on this repository (Security tab, "Report a vulnerability"). Include the device, the app version, and reproduction steps. Expect an acknowledgement within a week.

## Scope that matters most

- Media server tokens leaking (logs, exports, sync traffic).
- Household pairing being bypassed by a device on the same network.
- Any path that lets a household member read another device's credentials.

## What is documented already

The threat model and the known residual risks are written down in [ADR 0013](docs/adr/0013-pairing-tls-tofu-pin-proof.md) and [ADR 0014](docs/adr/0014-media-credentials-never-synced.md). A report that restates a documented residual risk is still welcome if it comes with a practical fix.

The planned Google Drive sync ([ADR 0020](docs/adr/0020-sync-mode-lan-household-or-google-drive.md)) will ship a Google OAuth client id and client secret inside the APK. For Google's TV and limited-input device flow the client secret is not a confidential credential, which is why open-source TV apps embed it; it grants nothing without a user completing sign-in on their own account. The Google token itself is stored in the Keystore-backed secret store, used only against the Drive API, and the synced document never contains Plex tokens.

## Signing certificates

Verify a downloaded APK against these before sideloading (`apksigner verify --print-certs clickarr.apk`):

| Build | Certificate SHA-256 |
|---|---|
| Release (`clickarr.net/apk`) | `1E:5C:8E:4B:8A:5C:C7:A0:79:8C:3D:D5:C6:2A:B8:F5:C9:7A:A9:A3:21:38:97:D3:71:53:F2:9D:B6:C0:E6:35` |
| Nightly (`clickarr.net/nightly`) | `14:BC:A2:82:39:01:0A:9F:9A:7E:EF:80:F7:8A:37:BD:A6:ED:D9:42:95:57:5B:CC:9A:5B:4D:E4:31:5E:6D:CE` |

`version.json` next to each build carries the same digest, and the in-app updater compares it with the running
build before offering a download. The operating system refuses an update signed by a different key regardless.

## Review of 2026-10-10

Two independent reviews covered the household protocol, TLS front door, Drive sync, Plex client, updater, secret
store, logging, settings lock, and CI. What changed as a result:

- The household is TLS only. A coordinator that cannot start TLS stays off instead of listening in the clear;
  members refuse `http://` addresses and ignore advertisements without TLS; the pairing proof is bound to the
  certificate actually seen on the wire, never to a claimed fingerprint.
- Wrong PIN guesses count against the PIN, not the session (three and it is cancelled); pairing sessions are capped.
- Every channel a member or a Drive document supplies is bounds-checked (number 1 to 9999, name length, slot
  rounding, lineup size, lineup ownership) before any TV persists it.
- Request bodies to the coordinator are capped; the TLS bridge pool is bounded with a read timeout; the event
  stream no longer accepts a token in the URL.
- A TV that leaves Drive sync forgets its sync base so re-enabling cannot push a stale state over the shared file;
  a file written by a newer scheduler is not merged.
- The Plex client never follows redirects (the token rides as a query parameter) and prefers https among a
  server's local addresses. The Google revoke call sends the token in the body.
- Network time replies must echo this TV's transmit stamp and come from the server asked; a correction over an
  hour needs two servers that agree.
- Exception text in logs and Diagnostics is redacted like everything else.
- Published nightlies are built non-debuggable, so `run-as` and a debugger cannot read the token store.
- Settings lock wrong-guess counts survive leaving the screen. Spike extras on the launcher intent are honoured
  only by debuggable builds. The updater refuses an APK name that leaves the release and caps `version.json`.
- CI: every action pinned to a commit, release permissions job-scoped, keystores deleted when the job ends,
  `v*` tags protected by a ruleset.

Known and accepted: a typed coordinator address with no advertised key can be answered by a rogue TV that then
holds a PIN-derived proof it can guess offline within the two-minute window; the fix is a PAKE, which is on the
list. The Google client secret is in the APK by the nature of the TV device flow.

