# 0008. Deterministic scheduling via frozen lineup snapshots, anchor, seed, and own PRNG

**Status:** Accepted
**Date:** 2026-10-09

## Context

Several Clickarr devices in one home must agree on what is airing on every channel at any instant, and the schedule must be reproducible years later. The schedule is computed, not stored ([ADR-0009](0009-schedule-as-pure-function-of-time.md)), so agreement depends entirely on the inputs. Media servers change over time (new episodes land, items are removed), and platform random number generators are not guaranteed stable across versions.

## Options considered

| Concern | Rejected approach | Chosen approach |
|---|---|---|
| Which items are on the channel | Each device runs `resolve(source)` against the live server; a new episode landing between two queries shifts every later program on one device only | The coordinator (or lone device) resolves once and freezes the result as a `LineupSnapshot` with a `contentHash`; the snapshot is what gets synced |
| Where cycle 0 begins | Implicit or device-local start time | An explicit `anchor` `Instant` stored on the channel |
| Shuffle order | `java.util.Random` or `kotlin.random.Random` semantics as the contract; algorithms can change across platform versions | A small documented PRNG in pure Kotlin (SplitMix64 for seeding, xoshiro256** for the stream) with committed test vectors; Fisher-Yates permutation per cycle driven by `prng(seed xor splitmix(cycle))` |
| Time representation | Local time | `Instant` (UTC epoch milliseconds) everywhere in scheduling; local time only at the presentation layer |

## Decision

Two devices agree on "what is airing on channel X at time T" if and only if they share: (1) the channel configuration (`anchor`, `seed`, `order`, `slotRounding`); (2) the identical `LineupSnapshot`, verified by `contentHash`; (3) the same scheduler algorithm version; and (4) clocks within a few seconds. Clickarr guarantees 1 and 2 by syncing them as data, 3 via a `schedulerVersion` stamped on `HouseholdState`, and addresses 4 with a coordinator-relative clock offset.

## Consequences

- `LineupSnapshot` freezes both which items and how long each is (`duration` is the server-reported file duration, not the metadata runtime). Lineup changes are explicit, versioned events ([ADR-0010](0010-lineup-changes-cut-over-at-boundary.md)).
- A member running older code refuses to apply a state with a newer `schedulerVersion` and prompts for an update rather than silently computing something different.
- Members keep a smoothed `clockOffset` from the coordinator's `now` in every response and use `now + offset` for scheduling. An offset above 30 s shows a one-time settings warning. NTP-grade sync is out of scope.
- Every cycle gets a fresh permutation, every device computes the same order, and there is no state to accumulate or compact; `elapsed` in millisecond `Long` arithmetic is good for 292 million years.
- Later time-block rules will evaluate against the household's configured time zone (synced), not the device's, so DST cannot cause disagreement.
- Property tests cover: identical inputs give identical outputs over 10,000 random samples; permutations are bijections; PRNG matches test vectors. A two-device agreement test serializes a `HouseholdState` through the protocol module and checks both schedulers agree at 1,000 random instants.
- Files with wrong reported durations produce a few seconds of filler or truncation. Accepted.
