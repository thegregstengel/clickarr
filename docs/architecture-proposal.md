# Clickarr Architecture Proposal

**Status:** Draft for approval. No application code has been written against this document yet.
**Date:** 2026-10-09
**Amendment (2026-10-09, after approval):** Clickarr supports **Plex only**. Other media servers are not planned (ADR 0019). The `MediaProvider` abstraction stays as a clean boundary and a test seam (the fake provider), not as a promise of other backends. This document has been edited to remove the other servers from every plan; the original multi-server reasoning is in the git history.

**Scope:** The MVP (single-device linear TV from Plex, then two-device household agreement) and the structural decisions that let later features land without redesign.

Clickarr turns a personal media library into channel-surfing television. The APK is the whole product: it talks to a media server, computes what every channel is airing right now, and plays it from the right offset. Several Clickarr devices in one home can optionally form a household that agrees on the lineup.

This document is organised as the twenty deliverables requested, in order. Each significant decision lists the options, the tradeoffs, the recommendation, and the reason. Section 21 collects the assumptions.

---

## Table of contents

1. [High-level architecture](#1-high-level-architecture)
2. [Android technology stack](#2-android-technology-stack)
3. [Gradle, project, and module structure](#3-gradle-project-and-module-structure)
4. [Core domain model](#4-core-domain-model)
5. [MediaProvider interface](#5-mediaprovider-interface)
6. [Plex integration strategy](#6-plex-integration-strategy)
7. [Local database model](#7-local-database-model)
8. [Scheduling engine design](#8-scheduling-engine-design)
9. [Deterministic scheduling strategy](#9-deterministic-scheduling-strategy)
10. [Playback architecture](#10-playback-architecture)
11. [EPG architecture](#11-epg-architecture)
12. [Household discovery protocol](#12-household-discovery-protocol)
13. [Pairing and authentication](#13-pairing-and-authentication)
14. [Household synchronization protocol](#14-household-synchronization-protocol)
15. [Offline behavior](#15-offline-behavior)
16. [Credential and token security model](#16-credential-and-token-security-model)
17. [Testing strategy](#17-testing-strategy)
18. [CI and release strategy](#18-ci-and-release-strategy)
19. [Major technical risks and unknowns](#19-major-technical-risks-and-unknowns)
20. [Phased MVP roadmap](#20-phased-mvp-roadmap)
21. [Assumptions](#21-assumptions)
22. [Decision summary](#22-decision-summary)

---

## 1. High-level architecture

### 1.1 The one-sentence model

A channel is a **pure function from time to program**. Everything else in Clickarr exists to supply that function's inputs (media metadata, channel configuration), evaluate it (scheduler), present its output (overlay, EPG), or act on it (player seeks to the computed offset).

That framing drives most of the design:

- The schedule is never a stored timetable that must be kept in sync. It is recomputed on demand from small, versioned inputs.
- Two devices agree on what is airing if and only if they hold the same inputs and have roughly the same clock. Synchronization therefore moves configuration, not schedules.
- The media server is the only source of video. Clickarr never proxies bytes.

### 1.2 Runtime layers inside the APK

```text
┌─────────────────────────────────────────────────────────────┐
│  TV UI (Compose for TV)                                     │
│  Player screen · Overlay · Guide · Channel editor · Setup   │
├─────────────────────────────────────────────────────────────┤
│  Application services                                       │
│  TuneController · GuideWindowBuilder · ChannelEditor        │
│  HouseholdService · ProviderRegistry · MetadataCache        │
├───────────────┬───────────────────┬─────────────────────────┤
│ Scheduling    │ Providers         │ Household               │
│ (pure Kotlin) │ Plex client       │ Discovery · Pairing     │
│               │ behind one API    │ Coordinator · Client    │
├───────────────┴───────────────────┴─────────────────────────┤
│  Platform adapters                                          │
│  Room DB · DataStore · Keystore · Media3 player · Ktor HTTP │
└─────────────────────────────────────────────────────────────┘
```

Dependencies point downward and inward. The scheduling engine and the household protocol types are plain Kotlin with no Android imports so they can be unit tested on the JVM in milliseconds and reasoned about in isolation.

### 1.3 Data flow for "open the app"

```text
Launch
  │
  ├─ load device state (last channel, provider connection)
  ├─ load household state (channels + lineup snapshots) from local DB
  │
  ├─ Scheduler.programAt(channel, now)  → (item, scheduledStart, scheduledEnd)
  ├─ Provider.playbackSource(item, deviceProfile) → URL + start offset
  └─ Player.play(url, offset = now - scheduledStart)
         │
         └─ overlay shows channel/program for ~4 s, then hides
```

Household synchronization and metadata refresh run in the background and never sit on this path.

### 1.4 Household topology

One device is the **coordinator**. It owns the canonical household state (a single versioned document) and serves it over the LAN. Other devices are **members**: they cache the full state locally, apply changes by sending commands to the coordinator, and keep watching from cache if the coordinator is off. Media never flows between Clickarr devices.

---

## 2. Android technology stack

Each choice below was evaluated rather than assumed. Fire TV compatibility was a hard filter.

### 2.1 Platform floor

| Option | Covers | Cost |
|---|---|---|
| minSdk 22 (Fire OS 5) | 2016-era Fire TV Stick 2nd gen, 1 GB RAM | Compose on 1 GB devices is slow; network-security-config absent before 24; large test matrix |
| **minSdk 25 (Fire OS 6)** | Every Fire TV from 2018 on, every Android TV/Google TV device in support | Excludes Fire OS 5 sticks |
| minSdk 28 (Fire OS 7) | Simplifies NSD and codec handling | Excludes the still-common 2018 Fire TV Stick 4K and AmazonBasics TVs on Fire OS 6 |

**Recommendation: minSdk 25, targetSdk current.** Fire OS 6 is Android 7.1 (API 25), Fire OS 7 is Android 9 (API 28), Fire OS 8 is Android 10/11 (API 29/30). API 25 is the lowest floor that keeps every device still receiving Fire OS updates while avoiding the 1 GB Fire OS 5 hardware that would dominate performance work. If demand appears, lowering to 22 is a one-line change plus testing, since nothing proposed here requires 25 specifically except network security config.

### 2.2 Language and concurrency

**Kotlin, coroutines, Flow.** Not seriously contested. Kotlin is the only first-class language for Compose; structured concurrency fits the player/scheduler/sync lifecycle; Flow is the natural shape for "current program on channel X" and for Room observation.

### 2.3 UI toolkit

| Option | Pros | Cons |
|---|---|---|
| Leanback (Views) | Mature, battle-tested on Fire TV, familiar to TV app developers | In maintenance mode; the EPG grid would be a fully custom View anyway; styling fights the framework; XML + Kotlin split raises contributor friction |
| **Compose + Compose for TV (`tv-material`)** | Stable since 1.0 (Aug 2024), now 1.1.0; custom layouts (EPG grid) are far easier; one language for everything; focus APIs are first-class | Larger APK and higher startup cost than Views on 1.5 GB Fire sticks; some focus edge cases still need care |
| Plain Compose without `tv-material` | Fewer dependencies | Re-implementing D-pad focus indication and TV-sized components |

**Recommendation: Compose with `tv-material`.** The EPG grid and the fast overlay are custom UI either way; Compose makes custom UI cheap. Compose for TV runs on Fire OS because it is ordinary AndroidX with no Play Services dependency. Note that the old `TvLazyColumn`/`TvLazyRow` are removed; the standard `LazyColumn`/`LazyRow` from Compose Foundation 1.7+ handle TV focus positioning via `BringIntoViewSpec`. Startup time on low-end Fire sticks is a tracked risk (Section 19) with a Phase 0 measurement.

### 2.4 Player

**AndroidX Media3 ExoPlayer.** The only realistic option. Amazon's own Fire TV guidance recommends ExoPlayer. It handles progressive MP4/MKV direct play, HLS (Plex transcodes), and DASH, and gives precise `seekTo`. MediaPlayer is too limited and opaque.

### 2.5 HTTP

| Option | Pros | Cons |
|---|---|---|
| Retrofit + OkHttp | Most familiar Android stack; excellent interceptors | Client only. The coordinator also needs an embedded HTTP **server** |
| **Ktor client + Ktor embedded server** | One library for both roles; `kotlinx.serialization` native; Kotlin-first; multiplatform if ever wanted | Less common in Android codebases; the CIO server engine has no TLS (see 2.9) |
| OkHttp client + NanoHTTPD server | Both tiny and proven | Two unrelated APIs; NanoHTTPD is barely maintained |

**Recommendation: Ktor for client and server, `kotlinx.serialization` for JSON.** The household coordinator is an HTTP server inside the app; using one library for both halves keeps the protocol module symmetric and testable in-process (Ktor's test host runs server and client in one JVM).

### 2.6 Persistence

| Option | Pros | Cons |
|---|---|---|
| **Room** | Android standard; Flow queries; migrations; KSP | Android-only |
| SQLDelight | Multiplatform; SQL-first | Less familiar to most Android contributors; no gain for an Android-only app |
| Realm / ObjectBox | Fast | Proprietary-ish, less approachable |

**Recommendation: Room for relational data, Proto DataStore for small preference blobs.** Credentials go in neither (Section 16).

### 2.7 Dependency injection

| Option | Tradeoff |
|---|---|
| **Hilt** | The documented Android default; compile-time checked; KSP support; most contributors know it |
| Koin | Lighter, runtime-resolved, friendlier to pure-Kotlin modules |
| Manual | Zero magic, grows painful past ~10 modules |

**Recommendation: Hilt.** Contributor familiarity outweighs its build-time cost. Pure-Kotlin modules (scheduling, protocol) expose plain constructors and are wired in `:app`, so they never depend on Hilt.

### 2.8 Images

**Coil 3.** Compose-native, small, handles the authenticated artwork URLs the providers emit.

### 2.9 Embedded server engine and TLS

Ktor's CIO server engine is the natural Android choice but does not support HTTPS. The Netty engine does, and runs on Android, at roughly 3 to 5 MB of APK. Whether the household protocol needs TLS at all is decided in Section 13; the engine choice follows from that decision. Phase 0 includes a spike to confirm Netty + Keystore-backed certificates work on Fire OS 6 before committing.

### 2.10 Things deliberately not used

- Google Play Services, Firebase, Cast SDK: breaks Fire TV and adds nothing core.
- Jetpack Security `EncryptedSharedPreferences`: deprecated; see Section 16 for the replacement.
- Any Clickarr-side server, container, or cloud component.

---

## 3. Gradle, project, and module structure

Gradle Kotlin DSL, a version catalog (`gradle/libs.versions.toml`), convention plugins in `build-logic/` so each module's build file is a few lines, and Gradle dependency locking for reproducibility.

```text
clickarr/
├── app/                       Android application: Hilt graph, navigation, manifests, banners
├── build-logic/               Convention plugins (android-library, kotlin-jvm, compose, hilt)
├── core/
│   ├── model/                 Pure Kotlin. Domain types shared by everything.
│   ├── scheduling/            Pure Kotlin. Deterministic schedule engine. No Android.
│   ├── common/                Pure Kotlin. Result types, Clock abstraction, logging facade.
│   └── database/              Room entities, DAOs, migrations, mappers to/from core:model
├── provider/
│   ├── api/                   MediaProvider interface + normalized DTO contracts (pure Kotlin)
│   ├── plex/                  Plex client + mapper
│   └── testing/               FakeMediaProvider, recorded fixtures, shared contract test suite
├── household/
│   ├── protocol/              Pure Kotlin. Message/state types, versioning, serialization golden tests
│   ├── discovery/             NSD advertise/browse wrapper, manual-address fallback
│   ├── coordinator/           Embedded Ktor server: pairing, state, commands, event stream
│   └── client/                Member-side sync client, cache reconciliation
├── playback/                  Media3 wrapper, DeviceProfile, TuneController, drift correction
├── feature/
│   ├── player/                Full-screen player + overlay
│   ├── guide/                 EPG grid
│   ├── channels/              Channel list and editor
│   ├── setup/                 First-run, provider sign-in
│   └── settings/              Settings, household join/manage
├── ui/
│   └── design/                Theme, typography, shared TV components, focus helpers
├── docs/                      This proposal, ADRs, protocol spec, contributor docs
└── .github/workflows/         CI
```

Rules:

- `core:*`, `provider:api`, `household:protocol` are `kotlin("jvm")` modules with no Android dependency. This is enforced by the convention plugin, not by discipline.
- Features depend on application services and `core:model`, never on each other.
- Providers depend only on `provider:api`, `core:model`, `core:common`. Nothing depends on a specific provider except `:app`, which registers them.
- Around 20 modules is more than a weekend project needs, but each boundary above corresponds to a real seam (testability, provider plug-in, pure vs Android). None are speculative.

---

## 4. Core domain model

All types are Kotlin `data class` / `value class` in `core:model`. Identifiers are typed, never raw strings.

### 4.1 Identity

```kotlin
@JvmInline value class ProviderId(val value: String)      // UUID per configured server connection
@JvmInline value class NativeItemId(val value: String)    // Plex ratingKey
data class MediaRef(val provider: ProviderId, val id: NativeItemId)  // globally unique inside Clickarr

@JvmInline value class ChannelId(val value: String)       // UUID
@JvmInline value class DeviceId(val value: String)        // UUID, generated once per install
@JvmInline value class HouseholdId(val value: String)     // UUID
```

Every reference to media carries the provider it came from. Two household devices resolve the same `MediaRef` because they connect to the same server (Section 6.5).

### 4.2 Media (normalized)

```kotlin
enum class ProviderKind { PLEX }

data class ServerInfo(
    val providerId: ProviderId, val kind: ProviderKind,
    val serverIdentity: String,   // Plex machineIdentifier, stable across URLs
    val name: String, val baseUrl: String, val version: String?,
)

enum class LibraryKind { MOVIES, SHOWS, OTHER }
data class Library(val ref: MediaRef, val name: String, val kind: LibraryKind)

sealed interface MediaItem {
    val ref: MediaRef
    val title: String
    val artwork: Artwork
    val genres: List<String>
    val year: Int?
}
data class Show(..., val studio: String?, val network: String?, val seasonCount: Int) : MediaItem
data class Season(..., val show: MediaRef, val index: Int) : MediaItem
data class Episode(..., val show: MediaRef, val season: MediaRef, val seasonIndex: Int, val episodeIndex: Int,
                   val runtime: Duration, val media: List<MediaVersion>) : MediaItem, Playable
data class Movie(..., val runtime: Duration, val studio: String?, val media: List<MediaVersion>) : MediaItem, Playable

interface Playable { val ref: MediaRef; val runtime: Duration; val media: List<MediaVersion> }

data class MediaVersion(val id: String, val container: String, val videoCodec: String?, val audioCodec: String?,
                        val width: Int?, val height: Int?, val bitrateKbps: Int?, val duration: Duration)

data class Artwork(val poster: ArtworkRef?, val thumb: ArtworkRef?, val backdrop: ArtworkRef?)
data class ArtworkRef(val provider: ProviderId, val path: String)   // resolved to a URL by the provider
data class Collection(val ref: MediaRef, val name: String) ; data class Playlist(val ref: MediaRef, val name: String)
```

`runtime` is the **file duration** reported by the server for the chosen media version, not the metadata runtime. The schedule depends on it being accurate.

### 4.3 Channels

```kotlin
data class Channel(
    val id: ChannelId,
    val number: Int,
    val name: String,
    val icon: ChannelIcon?,                     // artwork ref or built-in glyph
    val source: ProgrammingSource,             // what media is eligible
    val order: OrderingMode,                   // SEQUENTIAL | SHUFFLE
    val slotRounding: Duration?,               // null = none; e.g. 30 min pads each program to the grid
    val seed: Long,                            // fixed at creation; drives SHUFFLE permutations
    val lineup: LineupSnapshotId,              // the materialized item list currently in effect
    val anchor: Instant,                       // schedule epoch: cycle 0 begins here
    val enabled: Boolean,
)

sealed interface ProgrammingSource {
    data class Shows(val shows: List<MediaRef>, val episodeOrder: EpisodeOrder) : ProgrammingSource  // AIRED | INTERLEAVED
    data class Library(val library: MediaRef, val filter: MediaFilter) : ProgrammingSource
    data class Collection(val ref: MediaRef) : ProgrammingSource
    data class Playlist(val ref: MediaRef) : ProgrammingSource
    data class Explicit(val items: List<MediaRef>) : ProgrammingSource
}
data class MediaFilter(val genres: Set<String> = emptySet(), val decade: IntRange? = null,
                       val networks: Set<String> = emptySet(), val studios: Set<String> = emptySet())
```

MVP implements `Shows`, `Library` (no filter), and `Explicit`. The sealed type leaves room for the rest.

### 4.4 Lineup snapshot

```kotlin
data class LineupSnapshot(
    val id: LineupSnapshotId,
    val channelId: ChannelId,
    val createdAt: Instant,
    val entries: List<LineupEntry>,            // ordered; this order is "sequential"
    val contentHash: String,                   // SHA-256 over entries, used in sync and tests
)
data class LineupEntry(val ref: MediaRef, val duration: Duration, val title: String, val subtitle: String?)
```

The snapshot is the single most important concept for determinism (Section 9). It freezes *which* items and *how long* each is, so that two devices querying a live server at different moments cannot drift.

### 4.5 Schedule output

```kotlin
data class Airing(
    val channelId: ChannelId,
    val entry: LineupEntry,
    val start: Instant,
    val end: Instant,                          // start + duration (+ padding if slotRounding)
    val contentEnd: Instant,                   // start + duration; filler plays between contentEnd and end
    val cycle: Long, val indexInCycle: Int,    // debugging / tests
)
```

`Airing` is computed, never persisted.

### 4.6 Household

```kotlin
data class Household(val id: HouseholdId, val name: String, val coordinator: DeviceId, val createdAt: Instant)
data class HouseholdDevice(val id: DeviceId, val name: String, val joinedAt: Instant, val lastSeen: Instant?)
data class HouseholdState(                     // the synchronized document
    val revision: Long,
    val household: Household,
    val devices: List<HouseholdDevice>,
    val servers: List<ServerLocation>,         // kind + identity + URL(s); never tokens
    val channels: List<Channel>,
    val lineups: List<LineupSnapshot>,
    val favorites: Set<ChannelId>,
)
```

---

## 5. MediaProvider interface

Lives in `provider:api`. Pure Kotlin, suspend-based, returns normalized types only.

```kotlin
interface MediaProvider {
    val id: ProviderId
    val kind: ProviderKind
    val server: ServerInfo

    /** Cheap liveness + version check. Used at startup and by the household UI. */
    suspend fun ping(): Result<ServerInfo>

    suspend fun libraries(): Result<List<Library>>
    suspend fun shows(library: MediaRef, page: Page): Result<PageOf<Show>>
    suspend fun movies(library: MediaRef, page: Page): Result<PageOf<Movie>>
    suspend fun episodes(show: MediaRef): Result<List<Episode>>          // all seasons, aired order
    suspend fun collections(library: MediaRef): Result<List<Collection>>
    suspend fun playlists(): Result<List<Playlist>>
    suspend fun items(refs: List<MediaRef>): Result<List<MediaItem>>     // batch lookup for cache refresh

    /** Expand a ProgrammingSource into concrete playables with durations. Used to build LineupSnapshots. */
    suspend fun resolve(source: ProgrammingSource): Result<List<Playable>>

    /** Decide how this device should play this item right now. Direct play preferred. */
    suspend fun playbackSource(item: MediaRef, profile: DeviceProfile, startAt: Duration): Result<PlaybackSource>

    fun artworkUrl(ref: ArtworkRef, size: ArtworkSize): String

    /** Optional. Providers that cannot do this return Result.success(Unit). */
    suspend fun reportProgress(item: MediaRef, position: Duration, state: PlaybackState): Result<Unit>
}

data class DeviceProfile(val maxWidth: Int, val maxHeight: Int, val videoCodecs: Set<String>,
                         val audioCodecs: Set<String>, val containers: Set<String>, val maxBitrateKbps: Int?,
                         val supportsHevc: Boolean, val supportsHdr10: Boolean, val supportsDolbyVision: Boolean)

sealed interface PlaybackSource {
    val url: String
    val headers: Map<String, String>
    val startAt: Duration                       // the offset the player must still seek to (0 if server-side)
    data class DirectPlay(override val url: String, override val headers: Map<String,String>,
                          override val startAt: Duration, val mimeType: String?) : PlaybackSource
    data class Hls(override val url: String, override val headers: Map<String,String>,
                   override val startAt: Duration, val sessionId: String) : PlaybackSource
}

interface MediaProviderFactory {
    val kind: ProviderKind
    suspend fun authenticate(flow: AuthFlow): Result<ProviderCredentials>   // per-kind flows, Section 6
    fun create(server: ServerInfo, credentials: ProviderCredentials): MediaProvider
}
```

Design notes:

- `resolve()` is the hook that keeps provider-specific query languages (Plex filters) out of the scheduler. The scheduler only ever sees `LineupSnapshot`.
- `startAt` on `PlaybackSource` lets a provider move the offset server-side for transcodes (Plex `offset`) while the player still seeks for direct play.
- `Result` is Clickarr's own sealed type in `core:common` with typed failures (`Unauthorized`, `Unreachable`, `NotFound`, `Unsupported`, `Unknown`), so the UI can distinguish "sign in again" from "server off".
- No provider type leaks past `provider:api`. Mappers inside each provider module translate wire DTOs to `core:model`.

---

## 6. Plex integration strategy

### 6.1 Build a thin client or use an SDK?

There is no official Plex SDK for Kotlin, and the community ones track an undocumented API loosely. Clickarr needs roughly a dozen endpoints. **Recommendation: a hand-written thin client** on OkHttp and kotlinx.serialization, with recorded fixtures pinning every response shape we rely on. A few hundred lines, fully under our control.

### 6.2 Plex

- **Auth:** plex.tv PIN flow (`POST /api/v2/pins`, show the 4-character code, poll until claimed). Returns an account token. Then `GET /api/v2/resources` lists the user's servers and their local/remote connection URIs; Clickarr prefers a `local` connection on the LAN. Fallback: manual URL + token entry for users who avoid plex.tv login.
- **Transport:** JSON via `Accept: application/json`. Required headers: `X-Plex-Token`, `X-Plex-Client-Identifier` (the Clickarr `DeviceId`), `X-Plex-Product`, `X-Plex-Device`, `X-Plex-Platform`.
- **Metadata:** `/library/sections`, `/library/sections/{id}/all?type=2|1`, `/library/metadata/{ratingKey}/allLeaves` for all episodes of a show. Durations come from `Media[].duration`.
- **Direct play:** `GET {base}/library/parts/{partId}/file.{ext}?X-Plex-Token=...`. The token must be a query parameter here, which is why playback URLs are never logged (Section 16).
- **Transcode:** `/video/:/transcode/universal/start.m3u8` with a decision request first (`/video/:/transcode/universal/decision`) and `offset=<seconds>`. The HLS session must be stopped on channel change (`/video/:/transcode/universal/stop?session=`) or the server keeps transcoding.
- **Risk:** the API is undocumented and occasionally shifts. Fixtures recorded from real servers (sanitized) pin the behavior we rely on.

### 6.5 Matching servers across household devices

A household syncs **server locations** (kind, server identity, friendly name, last known URLs) but not credentials. When a member device joins and sees a server it has no credentials for, setup prompts: "This household uses Plex server *WOPR*. Sign in on this device." Item IDs line up because both devices talk to the same server identity: Plex `ratingKey` values are per-server.

Edge case handled in the model: a member signed in as a different Plex user (a managed or shared account) may lack access to a library. `playbackSource` returns `NotFound`/`Unauthorized`; the player shows a "Not available on this device" card for that program and advances at the scheduled time.

### 6.6 Metadata caching

Clickarr does not mirror the library. It caches:

- Libraries, shows, and movies that the user has browsed or that a channel references (needed for titles, artwork, and the channel editor).
- Full episode lists for shows used by channels (needed to build lineups).
- Nothing else until browsed.

Cache rows carry `fetchedAt`; the channel editor refreshes on open; lineup refresh (Section 9.6) re-resolves sources. A full library scan is never required.

---

## 7. Local database model

Room, single database `clickarr.db`, versioned migrations from day one. Credentials are not in it.

```text
provider_connection      id PK, kind, server_identity, name, base_url, alt_urls(json), user_id, last_ok_at
                         -- credential lives in encrypted store keyed by id

media_item               provider_id, native_id, (PK both), type(SHOW|SEASON|EPISODE|MOVIE|COLLECTION|PLAYLIST),
                         title, sort_title, year, parent_native_id, grandparent_native_id, season_index, episode_index,
                         runtime_ms, genres(json), studio, network, artwork(json), versions(json), fetched_at
                         -- indexes: (provider_id, parent_native_id), (provider_id, type)

library                  provider_id, native_id (PK both), name, kind

channel                  id PK, number UNIQUE, name, icon(json), source(json), order_mode, slot_rounding_ms,
                         seed, lineup_id FK, anchor_epoch_ms, enabled, updated_at

lineup_snapshot          id PK, channel_id FK, created_at, content_hash, entry_count
lineup_entry             lineup_id FK, position, provider_id, native_id, duration_ms, title, subtitle
                         -- PK (lineup_id, position)

household                id PK, name, coordinator_device_id, created_at, revision          -- 0 or 1 rows
household_device         id PK, name, joined_at, last_seen_at
household_server         server_identity PK, kind, name, urls(json)                         -- what the household uses

favorite                 channel_id PK

sync_state               key PK, value   -- last_applied_revision, coordinator_address, last_sync_at, cursor data
```

Device-local small state in Proto DataStore (`device_prefs.pb`): device id and name, last channel, overlay timeout, preferred audio language, EPG density, "resume on launch" toggle.

Why a flat `media_item` table with a type column instead of four tables: the provider mappers already produce a sealed type, the queries are simple, and one table makes "refresh everything for provider X" one statement. Seasons are cached only because the channel editor wants them for display.

Why `lineup_entry` denormalizes `title`/`subtitle`: the EPG and overlay must render from the lineup alone, with no join to `media_item`, so a device that received a lineup from the coordinator can draw the guide before it has fetched any metadata of its own.

---

## 8. Scheduling engine design

`core:scheduling`. No Android, no coroutines, no I/O. Inputs are values; outputs are values.

### 8.1 Public surface

```kotlin
interface ScheduleStrategy {
    fun airingAt(channel: Channel, lineup: LineupSnapshot, at: Instant): Airing?
    fun airingsBetween(channel: Channel, lineup: LineupSnapshot, from: Instant, to: Instant): List<Airing>
    fun next(airing: Airing, lineup: LineupSnapshot): Airing?
}

class Scheduler(private val strategies: Map<StrategyKind, ScheduleStrategy>) {
    fun airingAt(channel: Channel, lineup: LineupSnapshot, at: Instant): Airing?
    fun guideWindow(channels: List<ChannelWithLineup>, from: Instant, to: Instant): GuideWindow
}
```

MVP ships one strategy, `CyclicLineupStrategy`. The interface exists so that later strategies (time blocks, fixed-time overrides, interstitials) compose without touching callers.

### 8.2 CyclicLineupStrategy

Given a lineup of N entries with durations `d[0..N)`, optional slot rounding `r`, anchor `A`, seed `s`, and ordering mode:

1. `slot[i] = r == null ? d[i] : ceil(d[i] / r) * r` (padded duration).
2. `cycleLength = Σ slot[i]`.
3. `elapsed = at - A`. If negative, nothing is airing (channel not started yet; practically never, anchor is creation time).
4. `cycle = floor(elapsed / cycleLength)`, `offsetInCycle = elapsed mod cycleLength`.
5. `perm = order == SEQUENTIAL ? identity : permutation(seed, cycle)` (Section 9.3).
6. Prefix sums over `slot[perm[i]]`; binary search for the index `k` where `offsetInCycle` falls.
7. `start = A + cycle*cycleLength + prefix[k]`, `contentEnd = start + d[perm[k]]`, `end = start + slot[perm[k]]`.

Complexity: O(log N) per lookup after an O(N) prefix sum per (lineup, cycle), cached. A 5,000-episode channel is trivial.

`airingsBetween` walks forward from `airingAt(from)` until `to`. A guide window of 3 hours over 30 channels is a few hundred lookups.

### 8.3 Padding and filler

With `slotRounding = 30 min`, a 22-minute episode occupies a 30-minute slot. Between `contentEnd` and `end` the channel shows **filler**: in the MVP a static channel card with "Up next: …" and a countdown. Later, interstitials and bumpers plug in here as their own lineup-like list with their own deterministic selection. The padding rule is part of the channel config, so devices agree.

### 8.4 Future strategies, sketched to validate the model

- **TimeBlockStrategy:** a channel holds a list of `(dayOfWeek set, localStart, localEnd, sub-lineup, sub-seed)` blocks; each block is a `CyclicLineupStrategy` with its own anchor aligned to the block start on the first day; a default block fills gaps. Fixed-time and day-of-week schedules fall out of this.
- **OverrideLayer:** `(start, end, LineupEntry)` list applied on top of any strategy for marathons, specials, and holidays; overrides win, the underlying cycle is paused (elapsed time excludes override duration) so the base schedule resumes where it left off.
- **Interstitials:** a filler policy that picks from an interstitial lineup deterministically from `(seed, cycle, index)`.

None of these change `Airing`, `Scheduler`, the EPG, or the player. That is the test of the model.

---

## 9. Deterministic scheduling strategy

### 9.1 The agreement contract

Two devices agree on "what is airing on channel X at time T" if they share:

1. The channel configuration (`anchor`, `seed`, `order`, `slotRounding`).
2. The identical lineup snapshot (verified by `contentHash`).
3. The same scheduler algorithm version.
4. Clocks within a few seconds.

Clickarr guarantees 1 and 2 by syncing them as data. 3 is guaranteed by a `schedulerVersion` field in `HouseholdState`; a member running older code refuses to apply a state with a newer scheduler version and prompts for an update, rather than silently computing something different. 4 is addressed in 9.5.

### 9.2 Why snapshots instead of live queries

If each device ran `resolve(source)` against the server itself, a new episode landing between two queries would shift every subsequent program on that channel on one device and not the other. So the coordinator (or the lone device) resolves once, freezes the result as a `LineupSnapshot`, and that snapshot is what gets synchronized. Lineup changes are explicit, versioned events.

### 9.3 Deterministic shuffle

Do not use `java.util.Random` or `kotlin.random.Random` semantics as the contract; their algorithms are not guaranteed stable across platform versions. Clickarr implements a small, documented PRNG (SplitMix64 for seeding, xoshiro256** for the stream) in pure Kotlin with test vectors in the repository. The permutation for cycle `c` of a channel is the Fisher-Yates shuffle of `[0, N)` driven by `prng(seed xor splitmix(c))`. Every cycle therefore gets a fresh order, every device computes the same order, and the sequence is reproducible years later.

Optional, later: "no immediate repeats across cycle boundaries" by rejecting a permutation whose first element equals the previous cycle's last and retrying with `c + 2^32`; still deterministic.

### 9.4 Time arithmetic

All scheduling uses `Instant` (UTC epoch milliseconds). Local time only appears at the presentation layer and, later, in time-block definitions, where the block's local-time rule is evaluated with the household's configured time zone (synced), not the device's. DST transitions therefore cannot cause two devices to disagree.

### 9.5 Clock agreement

Android TV devices sync time via NTP when networked; a few seconds of skew is normal and invisible. Mitigations in order of cost:

1. The coordinator includes its `now` in every response; members compute and keep a smoothed `clockOffset` and use `now + offset` for scheduling while in a household. Cheap, in the MVP.
2. Members with an offset above 30 seconds show a one-time warning in settings.
3. Nothing further. NTP-grade sync is out of scope.

### 9.6 Changing a lineup without breaking the illusion

When a lineup is refreshed (new episodes, user edits), a naive swap changes `cycleLength` and every program jumps. Rule: a lineup change is applied with a **cut-over**. The coordinator computes the current `Airing` under the old lineup, sets the new lineup's `anchor` to that airing's `end`, and keeps the old snapshot referenced until then (`Channel.pendingLineup`, `pendingAt`). Every device, computing from the same data, switches at the same boundary. The old snapshot is garbage-collected once `pendingAt` is in the past on the coordinator.

For the MVP, a user editing a channel may accept an immediate jump ("Apply now") or the cut-over ("Apply after current program"). Default is cut-over.

### 9.7 Long-term drift of the model

`elapsed` grows forever. With millisecond `Long` arithmetic that is not a numeric problem (292 million years). A channel's cycle count grows, and the shuffle for cycle 1,000,000 is as deterministic as cycle 0. There is no state to accumulate or compact.

---

## 10. Playback architecture

### 10.1 Components

```text
TuneController (playback module)
   ├─ Scheduler.airingAt(channel, now)
   ├─ Provider.playbackSource(entry.ref, deviceProfile, startAt = now - airing.start)
   ├─ PlayerEngine.load(source, seekTo = source.startAt)
   ├─ schedules a boundary timer for airing.end
   └─ DriftMonitor (every 10 s: expected = now - airing.start; if |player.position - expected| > 5 s, re-seek)
PlayerEngine (Media3 ExoPlayer behind a small interface so tests can fake it)
OverlayState (StateFlow: channel, airing, next, visible-until)
```

The player lives in the player screen's ViewModel scope. TV apps have no background audio use case, so no foreground service. ExoPlayer is created once and reused across channel changes to avoid codec re-initialization cost.

### 10.2 Tune-in sequence and latency budget

1. User presses channel up. Overlay switches to the new channel's info **immediately** from local data (0 ms perceived).
2. `airingAt` (sub-millisecond).
3. `playbackSource`: direct play needs one metadata GET to learn the file part key (tens of ms on LAN). Transcode requires a decision/start round trip (hundreds of ms) and server spin-up (seconds).
4. `setMediaItem` + `prepare` + `seekTo(startAt)` + `play`. Direct play from a LAN server typically shows first frame within about a second; HLS transcodes within three to six.

Optimizations in scope for the MVP: reuse the player, prefer direct play aggressively, cancel the previous load on rapid channel changes (debounce 300 ms while the user is still surfing so intermediate channels never start loading), and stop Plex transcode sessions on departure.

### 10.3 Program boundaries

At `airing.end` the controller tunes to `next()` on the same channel. To make the transition seamless, when `airing.end - now < 20 s` the next program's `MediaItem` is appended to the ExoPlayer playlist with a `clippingConfiguration` so the player auto-advances; the current item is clipped at `contentEnd`. If the file is shorter than the lineup claims, the player ends early and the filler card shows until `end`. If longer, the clip cuts it at `contentEnd`.

### 10.4 Returning to a channel

There is no "resume". Returning always calls `airingAt(now)`. The previous player position is discarded. This is the behavior that makes channels feel live.

### 10.5 Device profile

Built once at startup from `MediaCodecList` and display capabilities, with a Fire TV table of known quirks (for example Dolby Vision profile support on specific sticks). Users can cap resolution/bitrate in settings. Profiles are device-local, never synced.

### 10.6 Remote control mapping

| Key | Action |
|---|---|
| Channel Up/Down (where present) | Next/previous channel by number |
| D-pad Up/Down during playback | Same as channel up/down (Fire TV remotes lack channel keys) |
| D-pad Left/Right | Previous channel (last-watched toggle) / show mini-guide |
| OK/Select | Toggle overlay |
| Back | Close overlay or guide; at player root, prompt to exit |
| Play/Pause | Pause (pausing a live channel is allowed; resuming re-tunes to the live position, matching the illusion) |
| Menu | Guide |
| 0–9 (where present) | Direct channel entry with 2-second commit |

---

## 11. EPG architecture

> Design reference: the TV UI mockups and the token set extracted from them live in [`docs/design/`](design/README.md). They establish a tabbed shell (Guide · Channels · Favorites · Settings) that Back-from-playback lands in, a Channels screen with a preview pane, and the overlay layout already described in Section 10. Where this document and the mockups differ, `docs/design/README.md` §3 records the resolution.


### 11.1 Data

`GuideWindowBuilder` asks `Scheduler.guideWindow(channels, from = now - 30 min, to = now + 3 h)` and produces a flat, pre-laid-out structure: per channel, a list of `(airing, leftPx, widthPx)` given a pixels-per-minute scale. Scrolling right past the window extends it by another window; results are memoized per `(channel, lineupId, from)` so moving focus never recomputes.

### 11.2 Rendering

```text
┌──────────┬────────────────────────────────────────────────────────┐
│  (clock) │ 7:00 PM       7:30 PM       8:00 PM       8:30 PM      │  sticky time header
├──────────┼────────────────────────────────────────────────────────┤
│  5 COMEDY│ Seinfeld   │ Seinfeld   │ Community ─────────────────  │
│ 10 SITCOM│ Office     │ Parks      │ Seinfeld   │ Office          │  LazyColumn of channel rows
│ 20 SCIFI │ Star Trek ─────────────│ Stargate ──────────────────  │
│ 30 MOVIES│ Back to the Future ────────────────────────────────── │
└──────────┴────────────────────────────────────────────────────────┘
                 ▲ now line (vertical, drawn over all rows)
```

- Vertical virtualization with `LazyColumn` (standard Compose Foundation; channel rows are the items).
- Each row is a custom `Layout` that positions program cells by time; cells intersecting the visible horizontal window are the only ones composed. Horizontal scroll is a shared `ScrollState` so all rows and the header move together.
- Program cells are focusable. Focus handling: Left/Right move to adjacent cells in the row; Up/Down move to the cell in the adjacent row that overlaps the *focused time* (the center of the current cell, clamped to now), the same rule traditional guides use. Implemented with explicit `FocusRequester`s per visible cell plus a `focusProperties { up = ...; down = ... }` resolver, because default spatial focus produces surprising jumps on wide cells.
- A sticky left column shows channel number, name, and icon; it never scrolls horizontally.
- Past programs are dimmed; the current program carries a progress bar; the now-line is redrawn each minute.
- OK on a cell tunes if it is airing now, otherwise shows a detail card (synopsis, artwork, "Tune to channel" button).
- Long-press OK or a dedicated key opens channel favorites filter.

Performance target: 60 fps with 50 channels on a Fire TV Stick 4K (2018). Cells are plain `Box` + `Text`, artwork is not shown in cells, and the row layout caches measurements.

### 11.3 Mini-guide

A one-row variant (current channel: now and next three) that slides in from the bottom on Left/Right during playback. Same data source, same cell composable.

---

## 12. Household discovery protocol

### 12.1 Options

| Option | Pros | Cons |
|---|---|---|
| **Android NSD (`NsdManager`, mDNS/DNS-SD)** | Built in, no dependency, Fire OS supports it (AOSP) | Known flakiness before API 28 (one resolve at a time, occasional stale entries); needs a multicast lock on some devices |
| JmDNS library | Works around NSD bugs | Unmaintained; raw sockets fight the system's mDNS responder |
| Custom UDP broadcast beacon | Simplest to reason about; Plex's GDM discovery works this way | Broadcast is blocked by some router isolation features; reinvents DNS-SD |
| Manual IP entry only | Zero discovery risk | Poor UX |

**Recommendation: NSD for discovery, manual address entry always available as a fallback.** The pairing and sync protocols do not depend on how the address was learned, so discovery can be replaced later without protocol changes.

### 12.2 Service definition

- Service type: `_clickarr._tcp`
- Instance name: the device's friendly name ("Living Room")
- Port: the coordinator's HTTP port (default 47831, fallback to a random free port advertised in the record)
- TXT record: `v=1` (protocol version), `hid=<householdId>` (if the device is in a household), `did=<deviceId>`, `role=coordinator|member`, `name=<household name>`, `pk=<base64 public key fingerprint, see 13>`

Only coordinators advertise by default. Members advertise too but with `role=member`, so a future "promote member to coordinator" or "which devices are in my household" screen works without a registry.

### 12.3 Client behavior

- Browse for 10 seconds, list coordinators found, resolve on selection (sequential resolve on API < 28).
- Acquire `WifiManager.MulticastLock` during browse (required on several Fire TV and Android TV builds that filter multicast to save power), release afterwards.
- Cache the coordinator's last known address in `sync_state` so reconnection after reboot does not need discovery at all; fall back to a fresh browse if the cached address fails.
- Manual entry: `host:port`, same flow afterward.

---

## 13. Pairing and authentication

### 13.1 Threat model

Home LAN. Adversaries considered: a guest's phone on the Wi-Fi, a compromised IoT device, a neighbor on a poorly secured network. Assets protected by the household protocol: channel configuration and lineups (low sensitivity), household membership (an attacker who joins can rearrange the lineup; annoying, not dangerous), and the household credentials themselves. **Media server tokens are never carried by this protocol** (Section 16), which caps the blast radius of any household compromise.

Requirements from the brief: discovery alone must not authenticate; an unrelated device must not join silently.

### 13.2 Options

| Option | What it provides | Cost |
|---|---|---|
| A. Plain HTTP, PIN pairing, bearer token afterwards | Blocks silent joins | A LAN sniffer can read the token and the PIN exchange, then join or act as a member |
| B. Plain HTTP, PIN pairing, per-request HMAC signatures | Blocks silent joins; a sniffer cannot reuse credentials from later traffic | A sniffer during the pairing window can brute-force a 6-digit PIN offline from the captured proof and derive the device secret |
| **C. TLS with per-device self-signed certificates, PIN-bound proof, trust-on-first-use pinning** | Confidentiality and integrity for all traffic; token theft by sniffing impossible; PIN proof is encrypted | Needs an embedded server engine with TLS (Netty, not CIO); certificate generation and pinning code; an *active* attacker who spoofs the mDNS record and intercepts the pairing exchange can still brute-force the PIN offline |
| D. PAKE (SPAKE2/OPAQUE) over plain or TLS | Closes the active-MITM gap fully | No well-maintained Android PAKE library; hand-rolled crypto in an open-source TV app is a liability |

**Recommendation: C, with the pairing window hardened (3 PIN attempts, 2-minute expiry, the coordinator shows a confirmation naming the joining device, and the coordinator must be in "accepting joins" mode which the user turns on from Settings).** Residual risk, documented honestly: an attacker who is already on the LAN, running an mDNS spoofer, at the exact moment the user is pairing, could capture the PIN proof and attempt a join within the window, which the user would then see as an unexpected second confirmation prompt on the coordinator. D is the upgrade path if a credible library appears; the protocol reserves a `pairingMethod` field.

Why not A or B for the MVP: they are cheaper, but swapping transports later would mean a second pairing migration for every household. TLS is standard, well-reviewed, and shifts the risk from "our custom crypto" to "Netty on Android works", which Phase 0 verifies. If the Phase 0 spike fails on Fire OS 6, the fallback is B with an explicit note in the security docs, since B's protocol messages are identical to C's.

### 13.3 Identity

Each install generates, once, an EC P-256 key pair in the Android Keystore with a self-signed X.509 certificate (the Keystore does this natively; no BouncyCastle). The SHA-256 of the SubjectPublicKeyInfo is the device's **fingerprint**, advertised in the TXT record and shown in settings.

### 13.4 Pairing flow

```text
Coordinator (Living Room)                        Joiner (Bedroom)
─────────────────────────                        ────────────────
Settings → Household → Add device
  generates PIN 6 digits, expires 120 s
  shows: "Enter 482 913 on the new device"
                                                 Settings → Household → Join
                                                 discovers "Living Room", user selects
                                                 TLS connect; pins server fingerprint from TXT (TOFU)
                                                 POST /v1/pair/start  {deviceId, name, certFingerprint}
  returns {sessionId, nonce}
                                                 user types PIN
                                                 proof = HMAC-SHA256(key = HKDF(PIN, nonce),
                                                          msg = sessionId‖joinerFp‖coordinatorFp)
                                                 POST /v1/pair/complete {sessionId, proof}
  verifies proof (≤3 attempts)
  shows: "Bedroom wants to join. Allow?"  → user confirms
  issues deviceToken (256-bit random), stores joiner cert fingerprint
  returns {householdId, deviceToken, state snapshot, coordinatorFp}
                                                 stores token + coordinator fingerprint, done
```

Binding the proof to both certificate fingerprints means a proof captured in one TLS session is useless in another.

### 13.5 Ongoing authentication

Every member request carries `Authorization: Bearer <deviceToken>` over TLS to the pinned coordinator certificate. The coordinator maps token to `DeviceId`. Tokens are revocable from the coordinator's device list ("Remove device"). Member-to-member communication does not exist in the MVP.

---

## 14. Household synchronization protocol

### 14.1 Model

The whole household configuration is one document, `HouseholdState`, with a monotonically increasing `revision`. The coordinator is the only writer. Members read snapshots and submit commands.

| Option | Tradeoff |
|---|---|
| **Single document, full snapshot on change, coordinator is sole writer** | Trivially correct; state is a few hundred KB at most; no merge logic |
| Per-entity deltas with version vectors | Less bandwidth; conflict handling needed; more code; no real benefit at this size |
| CRDTs and peer-to-peer | Coordinator-less; far more complexity than the MVP can justify |

**Recommendation: single document with full snapshots.** Measured: 50 channels with 5,000 lineup entries each is roughly 15 MB of JSON, which is the pathological case; typical households are two orders of magnitude smaller. Snapshots are gzip-compressed on the wire. If a real household hits the pathological size, per-lineup fetching (`GET /v1/lineups/{id}`, already in the API) splits the transfer without a protocol change.

### 14.2 Endpoints (coordinator)

```text
GET  /v1/info                         → {protocolVersion, schedulerVersion, householdId, name, revision, now}
POST /v1/pair/start, /v1/pair/complete   (Section 13)
GET  /v1/state                        → full HouseholdState (ETag = revision; 304 if If-None-Match matches)
GET  /v1/lineups/{id}                 → one LineupSnapshot
POST /v1/commands                     → {command} ; reply {revision} or {error}
GET  /v1/events  (WebSocket)          → {type: "revision", revision} on every change; {type: "ping", now} every 30 s
GET  /v1/devices ; DELETE /v1/devices/{id}
```

Commands are a sealed type in `household:protocol`: `CreateChannel`, `UpdateChannel`, `DeleteChannel`, `ReorderChannels`, `RefreshLineup(channelId)`, `SetFavorite`, `RenameHousehold`, `RegisterServer(location)`. The coordinator validates (unique channel numbers, referenced lineup exists), applies, bumps `revision`, persists, broadcasts.

### 14.3 Member loop

```text
start → read cached state from DB → UI is live
      → connect WebSocket (retry with backoff)
      → on connect or on "revision" event: GET /v1/state with If-None-Match → apply if newer → persist → UI updates
      → every 5 min as a safety net: GET /v1/info and compare revision
```

Applying a state is a single transaction that replaces channels, lineups, devices, servers, and favorites. The UI observes the DB, so it updates as a unit.

### 14.4 Lineup resolution lives on the coordinator

`RefreshLineup` requires talking to the media server, which requires credentials. The coordinator uses its own credentials. If a member sends `RefreshLineup` for a server the coordinator cannot reach, the command fails with a clear error. This is a known limitation of the MVP (Section 15.3).

### 14.5 Versioning

`protocolVersion` is an integer; the member refuses to talk to a coordinator with a higher major version and shows "Update Clickarr on this device." Within a version, unknown JSON fields are ignored and new fields are optional, so minor additions never break old members. `HouseholdState` is also stamped with `schedulerVersion` (Section 9.1).

---

## 15. Offline behavior

### 15.1 Watching

Members keep the full `HouseholdState` in Room. Tune-in, overlay, EPG, and channel surfing read only local data plus the media server. The coordinator being off is invisible to viewers, except that the clock offset stops being refreshed (harmless for days).

### 15.2 Editing while the coordinator is unreachable

MVP rule: editing is disabled on members with the message "Living Room (household coordinator) is offline. You can still watch; changes need it online." No queued offline edits, no conflict resolution. This is the single biggest simplification in the design and it is acceptable because channel editing is rare and watching is constant.

### 15.3 Coordinator migration

Not automatic in the MVP, but the data model makes it a manual, safe operation later: any member holds the full state at some revision; "Make this device the coordinator" would mint a new coordinator identity, bump the revision with a `CoordinatorChanged` marker, and the old coordinator, when it returns and sees the marker with a higher revision, demotes itself. Pairing tokens are coordinator-specific, so members would re-pair with the new coordinator (one PIN each). Election by heartbeat is explicitly out of scope.

### 15.4 Media server unreachable

Independent of household state. The player shows a channel card "Can't reach Plex server *WOPR*" with the schedule still ticking; it retries at each program boundary and every 30 seconds. The guide remains fully functional because it needs no server access.

---

## 16. Credential and token security model

### 16.1 Classification

| Data | Sync scope | Storage |
|---|---|---|
| Plex account and server tokens | **Never synchronized** | Encrypted on device (16.2) |
| Household device token, coordinator certificate fingerprint | Device-specific, never synchronized | Encrypted on device |
| Device private key | Device-specific | Android Keystore, non-exportable |
| Pairing PIN | Ephemeral, never stored | Memory only, 120 s |
| Server locations (kind, identity, name, URLs) | Household-wide | Room, plaintext |
| Channels, lineups, favorites, household name, device names | Household-wide | Room, plaintext |
| Last channel, device profile, UI preferences | Device-specific | DataStore |
| Viewing history | Device-specific in the MVP (not collected by default) | Room if enabled |

Why media server tokens are never shared: a Plex account token grants access to every server and setting on the account. Copying it to another TV silently expands the blast radius of a lost or sold device from "that TV" to "every TV". Each device authenticates itself; the UX cost is one sign-in per TV per server, which is the same cost every other media client imposes. A future opt-in "share this server's sign-in with the household" would require end-to-end encryption to the member's public key and a visible warning, and is not in the MVP.

### 16.2 At-rest encryption

Jetpack Security's `EncryptedSharedPreferences` is deprecated, so Clickarr uses the primitive it wrapped: an AES-256-GCM key generated in the Android Keystore (`setUserAuthenticationRequired(false)`, since TVs have no lock screen), used by a small `SecretStore` that encrypts a Proto DataStore file (`secrets.pb`) with a random IV per write. Keystore keys cannot leave the device, so a copied data directory is useless elsewhere. On devices where the Keystore is broken (rare, some Fire OS builds have had issues), `SecretStore` falls back to a software key stored in app-private storage and logs a warning in the diagnostics screen; app-private storage on a non-rooted TV is already inaccessible to other apps.

### 16.3 Tokens in URLs

Plex requires `X-Plex-Token` as a query parameter on media URLs. Clickarr's logging facade redacts `X-Plex-Token`, `api_key`, and `Authorization` values everywhere, including ExoPlayer's own logs (a custom `EventLogger` with redaction) and crash reports. Diagnostics exports scrub the same.

### 16.4 Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (discovery), `WAKE_LOCK` (playback). Nothing else. No storage, no location, no account access.

### 16.5 Supply chain

Dependency locking, Renovate with grouped PRs, and a `dependency-review` CI check. No binary blobs in the repo. The signing keystore exists only in GitHub Actions secrets and the maintainer's password manager (Section 18).

---

## 17. Testing strategy

Pyramid, weighted toward JVM tests because the parts that must be correct (scheduler, protocol, mappers) are pure Kotlin.

### 17.1 Unit and property tests (JVM, run on every PR, seconds)

- **Scheduler:** property-based tests with Kotest. Invariants: airings tile time with no gaps or overlaps; `airingAt(t)` is inside `airingsBetween(a,b)` for `a ≤ t < b`; identical inputs give identical outputs across 10,000 random `(lineup, seed, t)` samples; shuffle permutations are bijections; PRNG matches committed test vectors; lineup cut-over produces continuity at the boundary.
- **Two-device agreement test:** two `Scheduler` instances constructed from a `HouseholdState` serialized and deserialized through the protocol module must agree on every channel at 1,000 random instants. This is the MVP's second milestone expressed as a unit test.
- **Protocol:** golden JSON files for every message type; a test fails if serialization changes without the golden file being updated, which forces reviewers to see wire changes.
- **Provider mappers:** recorded, sanitized fixtures from a real Plex server served through OkHttp's MockWebServer. A shared **contract test suite** in `provider:testing` runs against the Plex client and the fake provider and asserts normalized output shape (every episode has a positive duration, parent refs resolve, artwork refs resolve to URLs).
- **TuneController:** a `FakePlayer` and a `FakeClock` verify the offset math, boundary advancement, drift re-seek, and debounce during fast surfing.
- **Pairing:** Ktor test host runs coordinator and client in one JVM; tests cover success, wrong PIN, expired PIN, attempt limit, replayed proof against a different fingerprint, revoked token.

### 17.2 Android instrumented tests (emulator, on PR for a smoke subset, nightly in full)

- Room migrations (schema export + `MigrationTestHelper`).
- `SecretStore` round trip on the Android Keystore.
- NSD advertise/browse loopback on the emulator.
- Compose UI tests for the guide: focus moves as specified across a synthetic 20-channel window; overlay auto-hides; numeric entry commits.

### 17.3 Device matrix (manual before each release, documented checklist)

Nvidia Shield (Android TV 11), Chromecast with Google TV, Fire TV Stick 4K 2018 (Fire OS 6), Fire TV Stick 4K Max (Fire OS 7/8), one Fire TV Cube. Checklist items: cold start to playing under 5 s, channel change under 2 s on direct play, no audio/video desync after 30 min, guide at 60 fps, pairing round-trip, coordinator reboot while member is watching.

### 17.4 Debug tooling in the app

A hidden Diagnostics screen (Settings → About → press OK five times): current airing math (`elapsed`, `cycle`, `offsetInCycle`), lineup hash, clock offset, last sync revision, player stats (codec, dropped frames, buffer), redacted log tail, export logs to a file for bug reports. This pays for itself the first time a user reports "channel 10 is wrong on the bedroom TV".

---

## 18. CI and release strategy

### 18.1 GitHub Actions

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | every PR and push to `main` | `ktlint`, `detekt`, Android Lint, JVM unit tests, assemble debug APK, upload as artifact (so any PR can be sideloaded for testing) |
| `instrumented.yml` | nightly and on label | Android TV emulator (API 25 and API 33), instrumented + Compose tests |
| `release.yml` | tag `v*` | Build release APK, sign, generate SHA-256 checksums and SBOM, create GitHub Release with changelog from conventional commits |
| `dependency-review` | PR | Flag new dependencies and known CVEs |

### 18.2 Signing

One release keystore, generated offline, held in GitHub Actions secrets (base64) and the maintainer's password manager. Never in the repo, never in CI logs. Debug builds use the standard debug key so contributors can install over each other's builds. A rotation note in `docs/release.md` explains that losing the key means a new package name is required for updates, so the key is backed up in two places.

### 18.3 Reproducible builds

Target: anyone can check out a tag, run `./gradlew assembleRelease`, and produce an **unsigned APK byte-identical** to the one in the release minus the signature block. Measures: pinned Gradle wrapper with checksum verification, version catalog plus dependency lock files, `BuildConfig` free of timestamps, deterministic resource ordering (AGP default), no R8 map randomness (fixed seeds, map file published), documented JDK (Temurin, exact version in `.java-version`). The release workflow runs the build twice on separate runners and fails if the two unsigned APKs differ. Verification instructions use `apksigner verify --print-certs` plus `diffoscope`. This is the prerequisite for F-Droid inclusion later.

### 18.4 Distribution

- GitHub Releases are canonical. Each release has `clickarr-<version>-release.apk`, `.sha256`, `CHANGELOG`.
- A stable short URL redirecting to the latest APK, for Fire TV's Downloader app.
- Self-update checker inside the app (opt-in, checks the GitHub Releases API, shows "Update available" with the changelog; installing still goes through the system installer). No silent updates.
- F-Droid after reproducible builds are proven. Google Play and Amazon Appstore are later considerations, not architectural inputs.

### 18.5 Versioning

Semantic versions. `versionCode` is derived from the tag (`major*10000 + minor*100 + patch`). Protocol and scheduler versions are independent integers that bump only on incompatible change.

---

## 19. Major technical risks and unknowns

Ordered by expected impact on the MVP. Each has a Phase 0 action.

1. **Compose startup and frame time on low-end Fire TV (1.5 GB sticks).** Compose for TV is stable but heavier than Leanback. Action: Phase 0 builds a skeleton player + 50-row guide and measures cold start and frame time on a Fire TV Stick 4K (2018). Threshold: cold start to first frame under 4 s, guide at 60 fps. Mitigations if missed: baseline profiles, R8 full mode, lazy feature init, lighter guide cells. Fallback: Leanback for the guide only.
2. **Embedded TLS server on Android (Netty + Keystore-backed cert).** Action: Phase 0 spike on Fire OS 6 and Android TV 14. Fallback: option B from Section 13 with identical message formats.
3. **Seek accuracy and latency into transcoded streams.** Plex `offset` works, but server spin-up is seconds and some containers seek imprecisely. Action: measure; make direct play the overwhelming default by building accurate device profiles; show overlay instantly so perceived latency is lower than actual.
4. **Plex API drift.** Undocumented, occasionally changes. Mitigation: fixture-based tests, a narrow surface, and a fast release cadence. Accept.
5. **NSD reliability on Fire OS 6/7.** Multicast filtering and pre-28 resolver bugs. Mitigation: multicast lock, sequential resolves, cached coordinator address, manual entry. Accept.
6. **Runtime mismatch between metadata and file.** Some files report wrong durations (VFR, broken headers). Mitigation: lineups record the server-reported file duration; player clips at `contentEnd`; filler covers shortfalls. Residual: a few seconds of filler or truncation on bad files. Accept.
7. **Clock skew.** Covered by coordinator-relative offset. Residual: a device with a wildly wrong clock and no household; it will still be internally consistent, just not matching wall time. Accept with a settings warning.
8. **Codec and HDR quirks on Fire TV** (Dolby Vision profiles, HEVC Main10 on older sticks, audio passthrough). Mitigation: conservative profile table, user override, ExoPlayer's decoder fallbacks. Will generate bug reports regardless. Accept.
9. **Household state growth** (pathological lineups). Mitigation already in the API (per-lineup fetch); monitor.
10. **Licensing hygiene.** MIT project; all dependencies are Apache-2.0/MIT/BSD. No code may be taken from GPL media clients or from any closed product. Documented in `CONTRIBUTING.md`.

Unknowns to confirm during implementation rather than now: how Fire TV handles `LEANBACK_LAUNCHER` banners versus Amazon's own asset requirements for sideloaded apps.

---

## 20. Phased MVP roadmap

Each phase ends with something runnable. Estimates assume one primary developer with AI assistance and are deliberately rough.

### Phase 0: Spikes and skeleton (1 to 2 weeks)

- Repository scaffold: modules, convention plugins, version catalog, CI with lint and unit tests, `CONTRIBUTING.md`, ADR template, this proposal split into ADRs.
- Spike A: Compose for TV skeleton with a 50-row synthetic guide; measure on Fire TV Stick 4K 2018 and Shield.
- Spike B: ExoPlayer direct play from Plex at an arbitrary offset; measure tune-in latency.
- Spike C: Ktor + Netty TLS server on Fire OS 6 with a Keystore certificate; Ktor client pinning it.
- Spike D: NSD advertise/browse between a Fire TV and an Android TV emulator with multicast lock.
- Deliverable: go/no-go notes on each spike; stack decisions confirmed or amended.

### Phase 1: One device, one channel (3 to 4 weeks) — Milestone 1

- `core:model`, `core:scheduling` with `CyclicLineupStrategy`, PRNG, property tests.
- `provider:api`, the Plex client with the plex.tv PIN flow, library browse, episode listing, direct play + HLS fallback, fixtures and contract tests; `provider:testing` with a fake provider and the shared contract suite.
- Room schema v1, `SecretStore`.
- Setup flow: sign in to one Plex server.
- Channel creation from shows, libraries with filters, Plex collections, playlists, hand-picked lists, and unions of those; sequential or shuffle; optional 30-minute rounding; lineup snapshot built on device.
- Player with tune-in offset, overlay, boundary advancement, drift monitor, filler card.
- Basic guide (grid, focus, tune), mini-guide, resume last channel on launch.
- Diagnostics screen.
- Exit criterion: on a real TV, tune to a channel at 7:17 PM and land about 17 minutes into the scheduled episode; leave and return and land at the new correct position.

### Phase 2: Two devices agree (3 weeks) — Milestone 2

- Device identity, certificates, `SecretStore` entries.
- `household:protocol` with golden tests; coordinator server (pairing, state, commands, events); member client with cached state.
- Settings: create household, add device (PIN), join household, device list, remove device.
- Lineup resolution moved to the coordinator for household channels; cut-over semantics for lineup changes.
- Clock offset from coordinator.
- Offline: member keeps watching with the coordinator off; editing disabled with a clear message.
- Exit criterion: two physical devices paired over Wi-Fi show the same program at the same offset on channel 10, within a few seconds, including after rebooting the coordinator. The same scenario exists as a JVM test.

### Phase 3: A real lineup (3 to 4 weeks)

- Channel editor: numbers, names, icons, reorder; favorites; numeric channel entry.
- Guide polish: detail card, time jump, favorites filter, past-program dimming, performance pass.
- Plex transcode session hygiene, device profile table for Fire TV models, resolution caps.
- First public release (`v0.1.0`), GitHub Releases, Downloader short URL, update checker.

### Phase 4: Hardening and Drive sync (4 to 5 weeks)

- Google Drive sync as the alternative to a LAN household (ADR 0020, added 2026-10-10): Settings, Sync with Off, Local household, or Google Drive; device-code sign-in; the household document in Drive's per-app folder; replay-on-conflict writes; Plex server identity check before applying remote state; backup and restore fall out of it.
- Reproducible-build verification in CI; F-Droid metadata.
- Manual coordinator migration.
- Multiple Plex servers per household; per-device "not available here" handling.
- Watched-state reporting to servers (opt-in).
- Accessibility pass (TalkBack on Android TV, focus order, text scaling).

### Later (not scheduled)

Time blocks and day-parts, fixed-time programs, overrides for marathons and holidays, interstitials and bumpers, viewing history and "what did I miss", opt-in encrypted credential sharing, PAKE pairing, additional platforms.

---

## 21. Assumptions

1. Users own a working Plex Media Server reachable on the same LAN as the TVs, with libraries already scanned and durations available.
2. TVs have a reasonably correct clock (automatic time is on, which is the default on all target platforms).
3. A household is one LAN; devices on different networks are out of scope.
4. The first user of a household is comfortable designating one always-on-ish TV as coordinator and understands that channel editing requires it to be on.
5. Each TV signs in to each media server separately. This is a deliberate security decision, not an oversight.
6. Fire OS 5 devices (2016 Fire TV Stick 2nd gen) are not supported at launch.
7. There is no Clickarr cloud account, telemetry, or analytics. Crash reporting, if added, is opt-in and self-hosted-friendly.
8. English UI first; strings are externalized from day one so translations are a contribution, not a refactor.
9. Lineups are built from file durations as reported by the server; Clickarr does not probe media files itself.
10. A single developer with AI assistance builds the MVP; the module structure is sized for that plus a handful of outside contributors, not a large team.
11. The project license is MIT (already in the repository). All dependencies are permissively licensed; no GPL code is incorporated.

---

## 22. Decision summary

| Area | Decision | Primary reason |
|---|---|---|
| Platform floor | minSdk 25 | Covers every Fire TV still receiving updates |
| UI | Compose + tv-material | Custom guide and overlay are cheap in Compose; stable; Fire OS compatible |
| Player | Media3 ExoPlayer | Only serious option; Amazon-recommended |
| HTTP | Ktor client + Ktor server | Coordinator needs a server; one library, in-process tests |
| Server engine | Netty (TLS); CIO fallback without TLS | CIO has no TLS |
| Persistence | Room + Proto DataStore | Android standard; migrations; Flow |
| DI | Hilt | Contributor familiarity |
| Providers | Hand-written thin clients behind `MediaProvider` | Symmetry, control, small surface |
| Determinism | Frozen lineup snapshots + anchor + seed + own PRNG | Agreement requires identical inputs, not identical queries |
| Scheduling | Pure function of time; `CyclicLineupStrategy` first | Nothing to sync, nothing to drift |
| Sync | Single versioned document, coordinator sole writer, full snapshots | Correct by construction at this data size |
| Discovery | NSD + manual entry | Built in; fallback covers flakiness |
| Pairing | TLS (Keystore certs, TOFU) + PIN-bound HMAC proof + explicit confirm | Standard crypto; token sniffing impossible; residual active-MITM documented |
| Credentials | Never synced; Keystore-encrypted at rest | Caps blast radius |
| Offline | Watch from cache; edits require coordinator | Simplest correct behavior |
| Release | GitHub Actions, signed APK on tag, reproducible unsigned build | Sideloading first, F-Droid later |

---

*Awaiting approval. On approval, Phase 0 begins with the repository scaffold and the four spikes; this document is split into individual ADRs under `docs/adr/` and the approved sections become `docs/architecture.md`.*
