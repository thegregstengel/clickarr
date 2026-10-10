# 0020. Sync mode: a LAN household or Google Drive, one at a time

**Status:** Accepted
**Date:** 2026-10-10

## Context

The household ([ADR 0011](0011-household-sync-single-document.md)) keeps several TVs in one home on the same
lineup over the LAN, with one coordinator and no cloud account. It cannot reach a TV in another house, and it is
not a backup: lose the coordinator and its state goes with it. The maintainer wants both of those covered without
giving up the no-server design, and asked for Google Drive as the vehicle, with the user choosing between local
and Drive sync.

Constraints already decided: no Clickarr server ([proposal section 1](../architecture-proposal.md)), no Google Play
Services dependency because Fire TV has none ([ADR 0001](0001-platform-floor-minsdk-25.md)), media server tokens
never leave the device ([ADR 0014](0014-media-credentials-never-synced.md)), and schedules are a pure function of
the shared state and the clock ([ADR 0009](0009-schedule-as-pure-function-of-time.md)), so any two devices holding
the same state show the same program at the same minute wherever they are.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| LAN household only (status quo) | No accounts, no third party | No backup; nothing across networks |
| Google Drive sync in addition to the household, both active at once | Most flexible | Two stores of truth with different latencies; every conflict case has to be designed and explained |
| **Either a LAN household or Google Drive, chosen in Settings, one at a time** | One store of truth per device; reuses the household document, commands, and reducer unchanged; backup and cross-network sync for free | Two homes cannot also be one LAN household at the same time; switching modes is an explicit step |
| A Clickarr-run sync service | Simplest client | A server to run, pay for, and secure; the project's defining constraint says no |
| Export and import a file | Trivial | Manual, no sync, easy to forget |

Google sign-in on TV was a question in itself. Google's OAuth flow for TVs and limited-input devices (a short code
entered on a phone at google.com/device) needs no browser on the TV and no Play Services, and it permits the Drive
scopes `drive.appdata` and `drive.file`. Drive's hidden per-app folder (`appDataFolder`) holds files only the app
can see and is removed when the user disconnects the app from their Google account.

## Decision

Settings gains a **Sync** choice with three states: **Off**, **Local household** (today's LAN coordinator and
members), and **Google Drive**. Exactly one is active on a device. Switching from one to the other first leaves the
current one, and the screen says so before it happens.

In Drive mode the Drive file is the household store:

- **Document.** The same `HouseholdState` JSON the LAN household uses, with the same `revision`, `schedulerVersion`,
  and `protocolVersion` rules, stored as one file in `appDataFolder`. Nothing new to design in the model.
- **Writes.** Every change is still a `Command` through the `HouseholdReducer`. A device applies the command locally,
  then uploads the new state with Drive's version precondition. If another device wrote first, it downloads the newer
  state, replays its pending commands on top, and uploads again. Last writer wins only after a replay, never by
  overwriting.
- **Reads.** On launch, after each local write, when the app returns to the foreground, and every 15 minutes while
  running. There is no push to a TV; the schedule model makes this harmless, because a device that is a few minutes
  behind on a lineup edit still computes the right program at the next cut-over.
- **Same Plex server, verified.** The state records the Plex server's machine identifier. Before applying a remote
  state, the device compares it with the server it is signed into. A mismatch stops the sync and names both servers,
  because every channel references items by that server's ids. Restoring onto a different server is refused rather
  than producing empty channels.
- **Credentials.** Plex tokens are never in the document (ADR 0014). The Google token lives in the Keystore-backed
  `SecretStore`, is used only against the Drive API, and is wiped on sign-out. The app's OAuth client id and secret
  are shipped in the APK; for the TV device flow Google does not treat the client secret as confidential, and this is
  recorded in SECURITY.md.
- **Transport.** Plain HTTPS to Google with OkHttp. No Google SDK, so it runs the same on Fire TV and Android TV.
- **Backend boundary.** The Drive client sits behind a small `SyncBackend` interface so a WebDAV or Nextcloud backend
  can be added later for people who would rather not use Google. Only Drive is planned.

Local household mode is unchanged by this decision.

## Consequences

- Backup falls out of Drive mode: a fresh install that signs into the same Plex server and the same Google account
  comes back with its channels and favorites.
- Two TVs in different homes on Drive mode show the same program at the same minute, with no LAN between them.
- The maintainer owns a Google Cloud project with an OAuth client of type "TVs and Limited Input devices" and a
  published consent screen with Clickarr branding. The Drive scopes used are in Google's non-sensitive tier, so no
  verification review is expected.
- A hybrid where the LAN coordinator also publishes to Drive is deliberately not designed now. If it is wanted later,
  it supersedes this ADR rather than extending it quietly.
- Work lands in Phase 4 after v0.1.0: device-flow sign-in, a Drive `appDataFolder` client, the replay-on-conflict
  sync loop, the server identity check, the Settings pane, and tests against a fake Drive. About two weeks.
- Residual risk: Google could change what the TV flow permits. The backend interface is the escape path.
