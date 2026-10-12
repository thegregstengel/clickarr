# 0022. Jellyfin alongside Plex

**Status:** Accepted
**Date:** 2026-10-12
**Supersedes:** [ADR-0019](0019-mvp-targets-plex-only.md)

## Context

ADR-0019 made Clickarr a Plex application because the maintainer ran Plex and could not verify anything else. The
maintainer now runs a Jellyfin server for testing and wants Jellyfin users to get the same linear TV. The
`MediaProvider` contract (ADR-0007) already keeps Plex wire types out of the scheduler, the channel editor, the guide,
and the player, and the shared contract test suite in `provider:testing` says what any provider must do. The parts
that still assume Plex are the provider registry, first-run sign-in, how a credential is stored, household and Drive
server matching, and some labels.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Stay Plex only | No new surface | Leaves out Jellyfin households; the maintainer can now verify Jellyfin |
| **Jellyfin alongside Plex, one server per TV** | Reuses the provider contract; every feature works against either; testable on the maintainer's server | Second sign-in flow, client, fixtures, and playback path to maintain |
| Jellyfin and Emby together | Shared API heritage | Emby has diverged; twice the testing with no Emby server to test against |
| Several servers on one TV at once | Mix libraries | Channels, sync, and the guide all assume one server; large change for little demand |

## Decision

Clickarr supports Plex Media Server and Jellyfin. A TV connects to one server at a time, chosen at first run. Emby is
not supported; a later ADR can add it.

- `ProviderKind` gains `JELLYFIN`. The provider registry holds one `MediaProviderFactory` per kind and creates each
  stored connection with the factory for its kind.
- A stored credential is a `ProviderCredentials` (kind plus named values) serialized into the secret store. A value
  that is not JSON is read as a Plex token, so TVs set up before this change keep their sign-in.
- Jellyfin sign-in is Quick Connect (the TV shows a code; the viewer approves it in a Jellyfin app or the web UI while
  signed in) or a server address with username and password. The password is sent once to get an access token and
  is never stored. Credentials stay on the TV and are never synced (ADR-0014).
- The Jellyfin provider is written from Jellyfin's published REST API. No code is copied from Jellyfin's own clients
  (GPL) or any other media client (ADR-0018).
- Playback asks the server for a decision with Clickarr's device profile: direct play when the file suits the TV,
  otherwise an HLS transcode. Text subtitles are side-loaded when the server offers them as external files and burned
  into a transcode otherwise, matching the Plex behaviour.
- Watched reporting, the "Coming up next" promo's theme music, artwork, collections, playlists, and genre filters map
  to their Jellyfin equivalents. Where Jellyfin has no equivalent the feature hides rather than half-works.
- A household or Drive sync group shares one server. Server matching uses kind plus server identity, so a Plex TV
  and a Jellyfin TV never think they share a library.
- Labels say the server's name or "media server" rather than "Plex" wherever both apply.

## Consequences

- New modules `provider:jellyfin` and `provider:jellyfin-fixtures`, mirroring the Plex pair. The Jellyfin provider
  passes the same `MediaProviderContractTest` as Plex.
- `feature:setup` offers two rows, Plex and Jellyfin, each with its own sign-in steps behind a provider-neutral
  `ServerConnector`.
- The emulator walkthrough gains a Jellyfin path against the fixture server.
- Room needs no schema change: `provider_connection.kind` is already a string.
- The README, website, and roadmap name both servers. ADR-0019 stays in the log, marked superseded.
