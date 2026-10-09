# 0011. Household sync: single versioned document, coordinator sole writer, full snapshots

**Status:** Accepted
**Date:** 2026-10-09

## Context

Several Clickarr devices in one home can form a household that agrees on the lineup. Agreement requires identical scheduling inputs on every device ([ADR-0008](0008-deterministic-scheduling-inputs.md)). The data is small: channels, lineup snapshots, favorites, device list, server locations. The MVP has one primary developer and must be correct before it is clever.

## Options considered

| Option | Tradeoff |
|---|---|
| Single document, full snapshot on change, coordinator is sole writer | Trivially correct; state is a few hundred KB at most; no merge logic |
| Per-entity deltas with version vectors | Less bandwidth; conflict handling needed; more code; no real benefit at this size |
| CRDTs and peer-to-peer | Coordinator-less; far more complexity than the MVP can justify |

## Decision

**One document, `HouseholdState`, with a monotonically increasing `revision`. One device is the coordinator and is the only writer. Members read full snapshots and submit commands.** Media never flows between Clickarr devices; the media server is the only source of video.

The coordinator exposes: `GET /v1/info`, `POST /v1/pair/start` and `/v1/pair/complete`, `GET /v1/state` (ETag is the revision, 304 on match), `GET /v1/lineups/{id}`, `POST /v1/commands`, a `GET /v1/events` WebSocket that announces revisions and pings with `now` every 30 s, and `GET`/`DELETE /v1/devices`. Commands are a sealed type in `household:protocol` (`CreateChannel`, `UpdateChannel`, `DeleteChannel`, `ReorderChannels`, `RefreshLineup`, `SetFavorite`, `RenameHousehold`, `RegisterServer`). The coordinator validates, applies, bumps `revision`, persists, and broadcasts.

## Consequences

- Member loop: read cached state from Room so the UI is live, connect the WebSocket with backoff, fetch `/v1/state` with `If-None-Match` on connect or on a revision event, apply newer state in one transaction, and poll `/v1/info` every 5 minutes as a safety net.
- Snapshots are gzip-compressed on the wire. The pathological case (50 channels with 5,000 entries each, roughly 15 MB JSON) is two orders of magnitude above typical households; per-lineup fetching already exists in the API if it ever matters.
- Lineup resolution lives on the coordinator because it needs media server credentials. A `RefreshLineup` for a server the coordinator cannot reach fails with a clear error. Known MVP limitation.
- `protocolVersion` is an integer; a member refuses a coordinator with a higher major version and prompts for an update. Within a version, unknown JSON fields are ignored and new fields are optional. `HouseholdState` also carries `schedulerVersion`.
- Golden JSON files for every message type make any wire change visible in review.
- This depends on an embedded HTTP server in the app ([ADR-0004](0004-http-ktor-client-and-server.md)) and makes the coordinator a single point for editing, which [ADR-0015](0015-offline-watch-from-cache.md) accepts.
