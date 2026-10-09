# 0015. Offline: watch from cache, editing requires the coordinator

**Status:** Accepted
**Date:** 2026-10-09

## Context

The coordinator is a TV that may be off ([ADR-0011](0011-household-sync-single-document.md)). Members must keep working as television when it is. Channel editing is rare; watching is constant. The MVP has one primary developer, and offline edit queues with conflict resolution are a large source of complexity and bugs.

## Options considered

| Option | Tradeoff |
|---|---|
| Members watch from a local copy of the state; editing is disabled while the coordinator is unreachable | Simplest correct behavior; no merge logic; an explicit message tells the user why they cannot edit |
| Queue edits offline and reconcile when the coordinator returns | Needs conflict resolution and a second write path; little benefit for a rare operation |
| Automatic coordinator election by heartbeat | Coordinator-less availability; far more complexity than the MVP can justify and easy to get wrong on flaky home Wi-Fi |

## Decision

**Members keep the full `HouseholdState` in Room and read only local data plus the media server for tune-in, overlay, EPG, and surfing. While the coordinator is unreachable, editing is disabled on members** with the message "Living Room (household coordinator) is offline. You can still watch; changes need it online." No queued offline edits, no conflict resolution. Election by heartbeat is explicitly out of scope.

## Consequences

- The coordinator being off is invisible to viewers except that the clock offset stops refreshing, which is harmless for days.
- Media server unreachability is independent of household state: the player shows a "Can't reach server" card with the schedule still ticking, retrying at each program boundary and every 30 s; the guide stays fully functional.
- Coordinator migration is not automatic in the MVP but the model makes it a safe manual operation later (Phase 4): any member holds the full state at some revision; "Make this device the coordinator" mints a new coordinator identity and bumps the revision with a `CoordinatorChanged` marker; the old coordinator demotes itself when it sees the marker. Pairing tokens are coordinator-specific, so members re-pair with one PIN each.
- Assumption recorded in the proposal: the first user is comfortable designating one always-on-ish TV as coordinator and understands that editing requires it to be on.
