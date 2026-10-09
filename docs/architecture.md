# Clickarr architecture

**This is the living architecture overview.** It is adapted from the approved [architecture proposal](architecture-proposal.md) (2026-10-09), which is kept unchanged for history. When this document and the proposal differ, this document wins. Individual decisions and their reasoning live in the [Architecture Decision Records](adr/README.md); the UI design reference lives in [docs/design/README.md](design/README.md).

Clickarr turns a personal media library into channel-surfing television. The APK is the whole product: it talks to a media server, computes what every channel is airing right now, and plays it from the right offset. Several Clickarr devices in one home can optionally form a household that agrees on the lineup. There is no Clickarr server, container, or cloud component.

## 1. The one-sentence model

A channel is a **pure function from time to program** ([ADR-0009](adr/0009-schedule-as-pure-function-of-time.md)). Everything else exists to supply that function's inputs (media metadata, channel configuration), evaluate it (scheduler), present its output (overlay, EPG), or act on it (player seeks to the computed offset).

Three things follow:

- The schedule is never a stored timetable. It is recomputed on demand from small, versioned inputs.
- Two devices agree on what is airing if and only if they hold the same inputs and roughly the same clock. Synchronization moves configuration, not schedules ([ADR-0008](adr/0008-deterministic-scheduling-inputs.md)).
- The media server is the only source of video. Clickarr never proxies bytes between devices.

## 2. Runtime layers

```text
+-------------------------------------------------------------+
|  TV UI (Compose for TV)                                     |
|  Player screen . Overlay . Guide . Channel editor . Setup   |
+-------------------------------------------------------------+
|  Application services                                       |
|  TuneController . GuideWindowBuilder . ChannelEditor        |
|  HouseholdService . ProviderRegistry . MetadataCache        |
+---------------+-------------------+-------------------------+
| Scheduling    | Providers         | Household               |
| (pure Kotlin) | Plex/Jellyfin/Emby| Discovery . Pairing     |
|               | behind one API    | Coordinator . Client    |
+---------------+-------------------+-------------------------+
|  Platform adapters                                          |
|  Room DB . DataStore . Keystore . Media3 player . Ktor HTTP |
+-------------------------------------------------------------+
```

Dependencies point downward and inward. The scheduling engine and the household protocol types are plain Kotlin with no Android imports, so they are unit tested on the JVM in milliseconds ([ADR-0016](adr/0016-module-structure-and-pure-kotlin-core.md)).

Stack summary: minSdk 25 ([ADR-0001](adr/0001-platform-floor-minsdk-25.md)), Kotlin with coroutines and Flow, Compose with `tv-material` ([ADR-0002](adr/0002-ui-toolkit-compose-tv-material.md)), Media3 ExoPlayer ([ADR-0003](adr/0003-player-media3-exoplayer.md)), Ktor client and Netty-backed Ktor server ([ADR-0004](adr/0004-http-ktor-client-and-server.md)), Room and Proto DataStore ([ADR-0005](adr/0005-persistence-room-and-proto-datastore.md)), Hilt ([ADR-0006](adr/0006-dependency-injection-hilt.md)), Coil 3 for images. No Google Play Services, Firebase, or Cast SDK.

## 3. Data flow: opening the app

```text
Launch
  |
  +- load device state (last channel, provider connection)
  +- load household state (channels + lineup snapshots) from local DB
  |
  +- Scheduler.programAt(channel, now)  -> (item, scheduledStart, scheduledEnd)
  +- Provider.playbackSource(item, deviceProfile) -> URL + start offset
  +- Player.play(url, offset = now - scheduledStart)
         |
         +- overlay shows channel/program for about 4 s, then hides
```

Household synchronization and metadata refresh run in the background and never sit on this path.

## 4. Module map

Gradle Kotlin DSL, a version catalog, convention plugins in `build-logic/`, and dependency locking. Each boundary below is a real seam: testability, provider plug-in, or pure-vs-Android.

| Module | Role | Android? |
|---|---|---|
| `app` | Hilt graph, navigation, manifests, provider registration | yes |
| `core:model` | Domain types shared by everything | no |
| `core:scheduling` | Deterministic schedule engine | no |
| `core:common` | `Result`, `Clock` abstraction, logging facade | no |
| `core:database` | Room entities, DAOs, migrations, mappers | yes |
| `provider:api` | `MediaProvider` interface and normalized contracts | no |
| `provider:plex`, `provider:jellyfin`, `provider:emby` | Thin clients and mappers | yes |
| `provider:testing` | `FakeMediaProvider`, fixtures, contract test suite | no |
| `household:protocol` | Message and state types, versioning, golden tests | no |
| `household:discovery` | NSD wrapper, manual fallback | yes |
| `household:coordinator` | Embedded Ktor server | yes |
| `household:client` | Member sync client, cache reconciliation | yes |
| `playback` | Media3 wrapper, `DeviceProfile`, `TuneController`, drift correction | yes |
| `feature:player`, `feature:guide`, `feature:channels`, `feature:setup`, `feature:settings` | Screens | yes |
| `ui:design` | Theme, typography, shared TV components, focus helpers | yes |

Rules: `core:*`, `provider:api`, and `household:protocol` are `kotlin("jvm")` modules, enforced by the convention plugin. Features never depend on each other. Providers depend only on `provider:api`, `core:model`, and `core:common`; only `:app` knows about concrete providers.

## 5. Core domain model

All types are Kotlin `data class` or `value class` in `core:model`. Identifiers are typed, never raw strings.

**Identity.** `ProviderId` (one per configured server connection), `NativeItemId` (Plex ratingKey, Jellyfin/Emby GUID), and `MediaRef = (ProviderId, NativeItemId)`, which is globally unique inside Clickarr. Also `ChannelId`, `DeviceId` (generated once per install), and `HouseholdId`. Every reference to media carries the provider it came from.

**Media (normalized).** `ServerInfo` carries `kind` (PLEX, JELLYFIN, EMBY), a `serverIdentity` that is stable across URLs, and `baseUrl`. `MediaItem` is a sealed interface with `Show`, `Season`, `Episode`, and `Movie`; `Episode` and `Movie` are `Playable` and carry `runtime` and a list of `MediaVersion`. `runtime` is the **file duration** reported by the server for the chosen version, not the metadata runtime; the schedule depends on it being accurate. `Artwork` holds provider-relative `ArtworkRef`s resolved to URLs by the provider.

**Channel.** `Channel(id, number, name, icon, source, order, slotRounding, seed, lineup, anchor, enabled)`. `source` is a sealed `ProgrammingSource`: `Shows` (with aired or interleaved episode order), `Library` (with an optional `MediaFilter`), `Collection`, `Playlist`, or `Explicit`. The MVP implements `Shows`, `Library` without filter, and `Explicit`. `order` is SEQUENTIAL or SHUFFLE. `seed` is fixed at creation. `anchor` is the schedule epoch where cycle 0 begins. `slotRounding` (for example 30 minutes) pads each program to the grid.

**Lineup snapshot.** `LineupSnapshot(id, channelId, createdAt, entries, contentHash)` where each `LineupEntry` is `(ref, duration, title, subtitle)`. This is the single most important concept for determinism: it freezes which items and how long each is, so two devices querying a live server at different moments cannot drift.

**Schedule output.** `Airing(channelId, entry, start, end, contentEnd, cycle, indexInCycle)`. Filler plays between `contentEnd` and `end`. `Airing` is computed, never persisted.

**Household.** `HouseholdState(revision, household, devices, servers, channels, lineups, favorites)` is the synchronized document. `servers` holds kind, identity, and URLs but never tokens.

## 6. Scheduling

`core:scheduling` has no Android, no coroutines, and no I/O. The public surface is a `ScheduleStrategy` interface (`airingAt`, `airingsBetween`, `next`) and a `Scheduler` that dispatches to strategies and builds guide windows.

The MVP ships `CyclicLineupStrategy`. Given N entries with durations, optional slot rounding, anchor, seed, and ordering: pad each duration to a slot; sum to `cycleLength`; compute `elapsed = at - anchor`, then `cycle` and `offsetInCycle`; choose the permutation for the cycle (identity for SEQUENTIAL, a deterministic Fisher-Yates shuffle driven by Clickarr's own PRNG for SHUFFLE); binary-search prefix sums to find the entry. O(log N) per lookup after one cached O(N) prefix sum per (lineup, cycle).

Determinism rests on four shared inputs ([ADR-0008](adr/0008-deterministic-scheduling-inputs.md)): channel configuration, the identical lineup snapshot (verified by `contentHash`), the same `schedulerVersion`, and clocks within a few seconds. The PRNG is SplitMix64 for seeding and xoshiro256** for the stream, implemented in pure Kotlin with committed test vectors, because platform `Random` algorithms are not guaranteed stable. All scheduling arithmetic uses UTC `Instant`; local time appears only at the presentation layer.

Lineup changes do not jump the channel. The coordinator computes the current airing under the old lineup, sets the new lineup's anchor to that airing's `end`, and keeps the old snapshot in `pendingLineup`/`pendingAt` until then. Every device switches at the same boundary ([ADR-0010](adr/0010-lineup-changes-cut-over-at-boundary.md)). The user can choose "Apply now" instead; cut-over is the default.

Future strategies validated against the model but not scheduled: `TimeBlockStrategy` (day-of-week and local-time blocks), `OverrideLayer` (marathons, specials, holidays), and deterministic interstitials in filler slots. None change `Airing`, `Scheduler`, the EPG, or the player.

## 7. Playback and tune-in

`TuneController` in the `playback` module runs the sequence: `Scheduler.airingAt(channel, now)`, then `Provider.playbackSource(entry.ref, deviceProfile, startAt = now - airing.start)`, then `PlayerEngine.load(source, seekTo = source.startAt)`, then a boundary timer for `airing.end`. A `DriftMonitor` checks every 10 s and re-seeks if the player position differs from the expected position by more than 5 s. `PlayerEngine` wraps Media3 ExoPlayer behind a small interface so tests use a `FakePlayer`.

The player is created once and reused across channel changes. It lives in the player screen's ViewModel scope; there is no foreground service.

Latency budget on channel change: the overlay switches to the new channel's info immediately from local data; `airingAt` is sub-millisecond; `playbackSource` is zero round trips for Plex direct play (the URL is constructable) and one `PlaybackInfo` POST for Jellyfin/Emby; transcodes cost a decision round trip plus server spin-up. Direct play from a LAN server typically shows first frame in about a second, HLS transcodes in three to six. The MVP prefers direct play aggressively, debounces 300 ms during rapid surfing so intermediate channels never start loading, and stops Plex transcode sessions on departure.

Program boundaries are seamless: when `airing.end - now < 20 s`, the next program is appended to the ExoPlayer playlist with a `clippingConfiguration`, and the current item is clipped at `contentEnd`. If the file is shorter than the lineup claims, filler shows until `end`; if longer, it is cut at `contentEnd`.

Returning to a channel never resumes. It always calls `airingAt(now)`. Pausing a live channel is allowed; resuming re-tunes to the live position.

The device profile is built once at startup from `MediaCodecList` and display capabilities, with a Fire TV quirks table and user-settable resolution and bitrate caps. Profiles are device-local, never synced.

Remote mapping: Channel Up/Down and D-pad Up/Down change channel; D-pad Left/Right toggle last-watched or open the mini-guide; OK toggles the overlay; Back closes overlay or guide and prompts to exit at the root; Menu opens the guide; digits enter a channel number with a 2-second commit.

## 8. Guide (EPG)

`GuideWindowBuilder` asks the scheduler for `now - 30 min` to `now + 3 h` across all channels and produces a pre-laid-out structure per channel at a pixels-per-minute scale, memoized per `(channel, lineupId, from)`. Rendering is a `LazyColumn` of channel rows, each a custom `Layout` that composes only the cells intersecting the visible window, with a shared horizontal `ScrollState`, a sticky time header, a sticky channel column, and a now-line redrawn each minute. Focus moves Left/Right between adjacent cells and Up/Down to the cell in the adjacent row overlapping the focused time, with explicit `FocusRequester`s because default spatial focus jumps on wide cells. Target: 60 fps with 50 channels on a 2018 Fire TV Stick 4K. The mini-guide is a one-row variant (now and next three) that slides in during playback. The tabbed shell (Guide, Channels, Favorites, Settings) and visual tokens are specified in [docs/design/README.md](design/README.md).

## 9. Household

One device is the **coordinator**. It owns the canonical `HouseholdState`, a single document with a monotonically increasing `revision`, and is its only writer ([ADR-0011](adr/0011-household-sync-single-document.md)). **Members** cache the full state in Room, apply changes by sending commands to the coordinator, and keep watching from cache if the coordinator is off ([ADR-0015](adr/0015-offline-watch-from-cache.md)). Editing on a member is disabled while the coordinator is unreachable, with a clear message. Media never flows between Clickarr devices.

Coordinator API: `GET /v1/info`, `POST /v1/pair/start` and `/v1/pair/complete`, `GET /v1/state` (ETag is the revision), `GET /v1/lineups/{id}`, `POST /v1/commands`, a `GET /v1/events` WebSocket for revision notifications and 30-second pings carrying `now`, and `GET`/`DELETE /v1/devices`. Commands are a sealed type: create, update, delete, and reorder channels; refresh a lineup; set favorite; rename household; register a server.

Member loop: read cached state so the UI is live, connect the WebSocket with backoff, fetch `/v1/state` with `If-None-Match` on connect or on a revision event, apply newer state in one Room transaction, and poll `/v1/info` every 5 minutes. Members derive a smoothed clock offset from the coordinator's `now`. Lineup resolution runs on the coordinator because it needs media server credentials.

Discovery uses Android NSD (`_clickarr._tcp`, default port 47831, TXT record with protocol version, household id, device id, role, name, and public key fingerprint) with a multicast lock during browse, a cached coordinator address for reconnection, and manual `host:port` entry as a permanent fallback ([ADR-0012](adr/0012-discovery-nsd-with-manual-fallback.md)).

Pairing runs over TLS with per-device self-signed certificates from the Android Keystore, trust-on-first-use pinning of the coordinator fingerprint, and a 6-digit PIN whose HMAC proof is bound to both certificate fingerprints. The coordinator must be in "accepting joins" mode, allows 3 attempts within 2 minutes, and asks the user to confirm the named joiner before issuing a bearer device token ([ADR-0013](adr/0013-pairing-tls-tofu-pin-proof.md)). The residual risk (an active mDNS spoofer on the LAN during the pairing window) and the PAKE upgrade path are documented there.

Media server tokens are never synchronized. The household shares server locations so a joining device is prompted to sign in; each TV authenticates itself. Secrets are encrypted at rest by a Keystore-backed `SecretStore`, and the logging facade redacts tokens everywhere, including ExoPlayer logs and diagnostics exports ([ADR-0014](adr/0014-media-credentials-never-synced.md)).

Versioning: `protocolVersion` and `schedulerVersion` are independent integers. A member refuses a coordinator or state with a higher major version and prompts for an update. Within a version, unknown JSON fields are ignored and new fields are optional.

## 10. Quality, release, and licensing

Tests are weighted toward the JVM: Kotest property tests for the scheduler, golden JSON files for every protocol message, recorded provider fixtures with a shared contract suite, a `FakePlayer` and `FakeClock` for `TuneController`, and pairing tests on the Ktor test host. A two-device agreement test serializes a `HouseholdState` through the protocol and checks both schedulers agree at 1,000 random instants. Instrumented tests cover Room migrations, `SecretStore`, NSD loopback, and guide focus. A hidden Diagnostics screen shows airing math, lineup hash, clock offset, sync revision, player stats, and a redacted log tail.

Releases are built by GitHub Actions on tag, signed with a keystore held only in CI secrets and the maintainer's password manager, and published to GitHub Releases with checksums and an SBOM. Unsigned builds are reproducible and verified by building twice on separate runners; F-Droid follows once that is proven ([ADR-0017](adr/0017-release-github-actions-reproducible-builds.md)). The project is MIT with permissive dependencies only and no code from GPL or closed media clients ([ADR-0018](adr/0018-license-hygiene-mit-permissive-deps.md)).

## 11. Roadmap and open risks

Phase 0 is spikes and scaffold: Compose for TV performance on a 2018 Fire TV Stick 4K, ExoPlayer offset playback from Plex, Netty TLS with a Keystore certificate on Fire OS 6, and NSD between Fire TV and an emulator. Phase 1 delivers one device and one channel (Plex, scheduler, player, guide). Phase 2 delivers two devices that agree (identity, pairing, coordinator, member, offline). Phase 3 adds remaining channel sources, the channel editor, guide polish, and the first public release. Phase 4 hardens: reproducible builds in CI, manual coordinator migration, multiple servers per household, opt-in watched-state reporting, accessibility. Phase 5, after the first public release, adds Jellyfin and then Emby behind the same provider contract. Full detail is in proposal sections 19 to 21.

The top tracked risks are Compose startup and frame time on low-end Fire sticks, the embedded TLS server on Android, and seek accuracy into transcoded streams. Each has a Phase 0 measurement and a named fallback in the corresponding ADR.
