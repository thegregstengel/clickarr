# 0010. Lineup changes cut over at a program boundary

**Status:** Accepted
**Date:** 2026-10-09

## Context

A channel's schedule is computed from a frozen `LineupSnapshot` and an `anchor` ([ADR-0008](0008-deterministic-scheduling-inputs.md), [ADR-0009](0009-schedule-as-pure-function-of-time.md)). When a lineup is refreshed (new episodes arrive, the user edits the channel), the entry count and `cycleLength` change. A naive swap of the snapshot would make every program on the channel jump at once, on every device, in the middle of whatever is playing.

## Options considered

| Option | Tradeoff |
|---|---|
| Swap the snapshot immediately | Simple; the current program jumps and the illusion of a live channel breaks |
| Cut over at the end of the current program | Needs a pending-lineup field and a computed new anchor; every device switches at the same boundary with no visible discontinuity |

## Decision

**Lineup changes are applied with a cut-over.** The coordinator computes the current `Airing` under the old lineup, sets the new lineup's `anchor` to that airing's `end`, and keeps the old snapshot referenced until then via `Channel.pendingLineup` and `pendingAt`. Every device, computing from the same data, switches at the same boundary. The old snapshot is garbage-collected once `pendingAt` is in the past on the coordinator.

For the MVP, a user editing a channel can choose "Apply now" (immediate jump) or "Apply after current program" (cut-over). The default is cut-over.

## Consequences

- The channel model carries `pendingLineup` and `pendingAt` in addition to `lineup` and `anchor`, and the scheduler must consult them.
- Because the new anchor is the old airing's `end`, the new lineup starts its cycle 0 exactly at the boundary; continuity at the boundary is a property-tested invariant.
- Lineup resolution, and therefore cut-over computation, happens on the coordinator for household channels ([ADR-0011](0011-household-sync-single-document.md)), since it needs media server credentials the coordinator holds.
