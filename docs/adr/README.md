# Architecture Decision Records

This directory holds the Architecture Decision Records (ADRs) for Clickarr. Each ADR captures one significant decision: the context that forced it, the options that were weighed, what was chosen, and what that choice commits us to.

The first batch (0001 to 0018) was split out of the approved [architecture proposal](../architecture-proposal.md) on 2026-10-09. The proposal is kept unchanged for history; the ADRs and [architecture.md](../architecture.md) are the living record.

## Process

- One file per decision, named `NNNN-short-kebab-case-title.md`. Numbers are sequential and never reused.
- Copy [0000-template.md](0000-template.md) to start a new ADR. The template is MADR-style and deliberately short: Title, Status, Date, Context, Options considered, Decision, Consequences.
- Keep an ADR focused. If it grows past a page, it is probably two decisions.
- Statuses:
  - **Proposed**: written, under discussion, not yet binding.
  - **Accepted**: binding. Code should follow it.
  - **Superseded**: replaced by a later ADR. Add a line under Status pointing to the replacement, and add a line in the replacement pointing back. Never edit the body of a superseded ADR; history is the point.
- An accepted ADR is changed only by writing a new ADR that supersedes it. Small factual corrections (typos, broken links) are fine without a new record.
- Propose an ADR in a pull request. The PR description should say what prompted the decision. Once merged with Status Accepted, it is in force.
- Reference ADRs from code comments and PRs by number (for example `ADR-0008`) so the reasoning is one search away.

## Index

| ADR | Title | Status |
|---|---|---|
| [0000](0000-template.md) | Template | n/a |
| [0001](0001-platform-floor-minsdk-25.md) | Platform floor: minSdk 25 | Accepted |
| [0002](0002-ui-toolkit-compose-tv-material.md) | UI toolkit: Compose with tv-material | Accepted |
| [0003](0003-player-media3-exoplayer.md) | Player: Media3 ExoPlayer | Accepted |
| [0004](0004-http-ktor-client-and-server.md) | HTTP: Ktor client and embedded server, Netty engine for TLS | Accepted |
| [0005](0005-persistence-room-and-proto-datastore.md) | Persistence: Room and Proto DataStore | Accepted |
| [0006](0006-dependency-injection-hilt.md) | Dependency injection: Hilt | Accepted |
| [0007](0007-hand-written-provider-clients.md) | Hand-written provider clients behind a MediaProvider interface | Accepted |
| [0008](0008-deterministic-scheduling-inputs.md) | Deterministic scheduling via frozen lineup snapshots, anchor, seed, and own PRNG | Accepted |
| [0009](0009-schedule-as-pure-function-of-time.md) | Schedule as a pure function of time with a strategy interface | Accepted |
| [0010](0010-lineup-changes-cut-over-at-boundary.md) | Lineup changes cut over at a program boundary | Accepted |
| [0011](0011-household-sync-single-document.md) | Household sync: single versioned document, coordinator sole writer, full snapshots | Accepted |
| [0012](0012-discovery-nsd-with-manual-fallback.md) | Discovery: Android NSD with manual address fallback | Accepted |
| [0013](0013-pairing-tls-tofu-pin-proof.md) | Pairing: TLS with Keystore certificates, TOFU pinning, PIN-bound HMAC proof | Accepted |
| [0014](0014-media-credentials-never-synced.md) | Media server credentials are never synchronized and are Keystore-encrypted at rest | Accepted |
| [0015](0015-offline-watch-from-cache.md) | Offline: watch from cache, editing requires the coordinator | Accepted |
| [0016](0016-module-structure-and-pure-kotlin-core.md) | Module structure and pure-Kotlin core boundaries | Accepted |
| [0017](0017-release-github-actions-reproducible-builds.md) | Release: GitHub Actions, signed APK on tag, reproducible unsigned builds | Accepted |
| [0018](0018-license-hygiene-mit-permissive-deps.md) | License hygiene: MIT, permissive dependencies only, no GPL code | Accepted |
