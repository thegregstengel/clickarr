# 0021. Holiday channels: yearly visibility windows and shared numbers

**Status:** Accepted
**Date:** 2026-10-12

## Context

People want a Christmas channel in December and a Halloween channel in October, without seeing them the rest of
the year or using up a channel number for each. Schedules are pure functions of time shared by every TV
([ADR-0009](0009-schedule-as-pure-function-of-time.md)), so a channel that starts and stops would have to agree on
where it is when it comes back. Viewers also asked to choose what plays (genres, collections, or picked movies)
rather than rely on title matching.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Fixed month-and-day windows chosen per channel | Simple | Easter and Thanksgiving move every year; windows overlapped |
| A holiday on the channel, with the window computed per year | Correct dates; no overlaps by construction | Five holidays, US Thanksgiving only |
| Holidays as a scheduling layer (`OverrideLayer`) | One channel number, content swaps by date | Much larger change; harder to reason about |

## Decision

**A channel may name a `Holiday`; the holiday decides only whether the channel is shown.** Windows end on the
holiday: the week before Valentine's Day and Easter (Gregorian computus), and from the 1st of the month for
Halloween, Thanksgiving (US, fourth Thursday), and Christmas (to the 25th). The schedule runs all year. Guide,
surfing, and number entry skip channels out of their window. Channels for different holidays may share a number
(`Channel.clashesWith`); a regular channel never shares. Settings, Channels, Holiday channels creates them from
genres, collections, or picked movies, on number 100 by default.

## Consequences

- No scheduler change, and every TV agrees on what is airing when a holiday channel reappears.
- `channel.number` is no longer unique in the database (schema 6, ADR-0005 amendment); the reducer, repository,
  and editor all apply the same sharing rule.
- Other countries' Thanksgiving, or more holidays, are a new enum value and window function each.
