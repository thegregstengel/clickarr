# 0007. Hand-written provider clients behind a MediaProvider interface

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr reads metadata from, and plays video from, Plex. The scheduler must never see provider-specific query languages, and two household devices must resolve the same item ids against the same server. Each provider needs roughly a dozen endpoints. Jellyfin publishes an official Kotlin SDK; Plex and Emby do not.

## Options considered

| Option | Tradeoff |
|---|---|
| Hand-written thin clients for all three on a shared Ktor client | Each is a few hundred lines; we own every byte of the mapping; identical structure across providers makes a shared contract test suite meaningful |

## Decision

**A hand-written thin Plex client behind a `MediaProvider` interface in `provider:api`.** The interface is pure Kotlin, suspend-based, and returns only normalized `core:model` types. other media servers share a base class because Emby is Jellyfin's ancestor; Plex is its own client.

Key interface points: `resolve(source)` expands a `ProgrammingSource` into playables with durations so the scheduler only ever sees a `LineupSnapshot`; `playbackSource(item, profile, startAt)` lets a provider move the offset server-side for transcodes while the player seeks for direct play; `Result` is Clickarr's own sealed type with typed failures (`Unauthorized`, `Unreachable`, `NotFound`, `Unsupported`, `Unknown`).

## Consequences

- No provider type leaks past `provider:api`. Mappers inside each provider module translate wire DTOs to `core:model`.
- A shared contract test suite in `provider:testing` runs against every provider using sanitized fixtures recorded from real servers and served through Ktor `MockEngine`.
- Plex's undocumented API may drift. Fixture-based tests, a narrow surface, and a fast release cadence are the mitigation. Accepted risk.
- Plex transcode sessions must be stopped on channel change or the server keeps transcoding.
- Households sync server locations (kind, server identity, name, URLs) but never credentials ([ADR-0014](0014-media-credentials-never-synced.md)). Item ids line up because both devices talk to the same server identity. A member lacking access to a library sees a "Not available on this device" card and the schedule advances normally.
