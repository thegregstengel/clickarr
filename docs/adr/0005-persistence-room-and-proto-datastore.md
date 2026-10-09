# 0005. Persistence: Room and Proto DataStore

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr stores cached media metadata, channel configuration, lineup snapshots, household state, and small device preferences on each device. Members must be able to watch from local data while the coordinator is offline ([ADR-0015](0015-offline-watch-from-cache.md)), so the local store is the UI's source of truth. The app is Android-only. Credentials have separate requirements ([ADR-0014](0014-media-credentials-never-synced.md)).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Room | Android standard; Flow queries; migrations; KSP | Android-only |
| SQLDelight | Multiplatform; SQL-first | Less familiar to most Android contributors; no gain for an Android-only app |
| Realm / ObjectBox | Fast | Proprietary-ish, less approachable |

For small preference blobs, Proto DataStore is the standard typed, Flow-observable store and was not seriously contested.

## Decision

**Room for relational data (single database `clickarr.db`, versioned migrations from day one) and Proto DataStore for small device-local preference blobs.** Credentials go in neither.

## Consequences

- Schema: `provider_connection`, `media_item` (one flat table with a type column), `library`, `channel`, `lineup_snapshot`, `lineup_entry`, `household`, `household_device`, `household_server`, `favorite`, `sync_state`.
- `media_item` is a single table rather than four because the provider mappers already produce a sealed type, queries are simple, and "refresh everything for provider X" becomes one statement.
- `lineup_entry` denormalizes `title` and `subtitle` so the EPG and overlay render from the lineup alone, letting a member draw the guide before it has fetched any metadata of its own.
- Applying a synced `HouseholdState` is a single Room transaction, so the UI (which observes the DB) updates as a unit.
- Device-local state in `device_prefs.pb`: device id and name, last channel, overlay timeout, preferred audio language, EPG density, resume-on-launch toggle. Device profiles are device-local and never synced.
- Room migrations are covered by instrumented tests (schema export plus `MigrationTestHelper`).
- Clickarr does not mirror the library. It caches only what the user has browsed or a channel references, plus full episode lists for shows used by channels.
