# 0009. Schedule as a pure function of time with a strategy interface

**Status:** Accepted
**Date:** 2026-10-09

## Context

A channel in Clickarr behaves like linear TV: something is always on, and tuning in lands mid-program at the right offset. The design question is whether the schedule is a stored timetable that must be generated, persisted, and kept in sync, or a computation. Later features (time blocks, fixed-time overrides, interstitials) must land without redesigning the engine, the EPG, or the player.

## Options considered

| Option | Tradeoff |
|---|---|
| Stored timetable generated ahead of time | Must be regenerated, persisted, extended as time passes, and synchronized between devices; drift and sync bugs are inherent |
| Pure function from time to program, recomputed on demand from small versioned inputs | Nothing to sync except configuration, nothing to drift; needs careful determinism ([ADR-0008](0008-deterministic-scheduling-inputs.md)) |

## Decision

**A channel is a pure function from time to program.** The engine lives in `core:scheduling` with no Android, no coroutines, and no I/O; inputs are values, outputs are values. `Airing` is computed, never persisted.

The MVP ships one strategy, `CyclicLineupStrategy`, behind a `ScheduleStrategy` interface (`airingAt`, `airingsBetween`, `next`). Given N entries with durations, optional slot rounding `r`, anchor `A`, seed `s`, and ordering mode: pad each duration to a slot, sum to `cycleLength`, compute `elapsed = at - A`, derive `cycle` and `offsetInCycle`, choose the permutation for the cycle (identity for SEQUENTIAL, deterministic shuffle for SHUFFLE), and binary-search prefix sums to find the entry. O(log N) per lookup after an O(N) prefix sum per (lineup, cycle), cached.

With `slotRounding`, the gap between `contentEnd` and `end` shows filler: in the MVP a static channel card with "Up next" and a countdown.

## Consequences

- Two devices with the same inputs and roughly the same clock agree with no coordination at query time. Synchronization moves configuration, not schedules.
- The EPG is just `airingsBetween` over a window; a 3-hour window over 30 channels is a few hundred cheap lookups.
- Returning to a channel never resumes; it always calls `airingAt(now)`, which is what makes channels feel live.
- Future strategies were sketched to validate the model and none change `Airing`, `Scheduler`, the EPG, or the player: `TimeBlockStrategy` (day-of-week and local-time blocks, each a cyclic strategy), `OverrideLayer` (fixed `(start, end, entry)` overrides that pause the underlying cycle), and deterministic interstitial selection for filler.
- The scheduler is tested on the JVM with Kotest property tests: airings tile time with no gaps or overlaps, `airingAt(t)` is inside `airingsBetween(a, b)` for `a <= t < b`, and cut-over produces continuity at the boundary.
