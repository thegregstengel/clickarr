# 0019. Clickarr targets Plex only

**Status:** Accepted
**Date:** 2026-10-09 (revised the same day: originally "Plex only for the MVP", now "Plex only")

## Context

The approved proposal was written for three media servers. The maintainer runs Plex, every spike and
exit criterion is exercised against Plex, and supporting other servers would mean maintaining clients,
fixtures, sign-in flows, playback decisions, and device-matrix testing for products the project does
not use. The maintainer decided that Clickarr is a Plex application.

## Options considered

| Option | Tradeoff |
|---|---|
| Three servers as proposed | Broadest audience; roughly triples provider work and support surface; most of it unverifiable by the maintainer |
| Plex first, others later | Keeps the door open; still implies a promise the project may never keep, and shapes the model around hypothetical backends |
| **Plex only** | Smallest surface; every feature can be verified end to end; the provider boundary is kept for cleanliness and testing, not for extensibility |

## Decision

Clickarr supports Plex Media Server and nothing else. No other media server appears in the roadmap,
the README, the website, the model, or the code.

The `MediaProvider` interface (ADR 0007) stays because it is a good boundary: the scheduler, channel
editor, and player never see Plex wire types, and the fake provider in `provider:testing` lets every
layer above be tested without a server. `ProviderKind` has a single value, `PLEX`.

## Consequences

- Phase 1 scope is one sign-in flow (plex.tv PIN plus manual URL and token), one metadata client,
  one playback path, and the full set of channel sources: shows, libraries with filters, collections,
  playlists, hand-picked lists, and unions.
- Spike B measures Plex direct play and Plex HLS only.
- The first-run mockup shows three server rows; the app shows one.
- If this decision is ever reversed, the provider contract and the shared contract test suite are
  the starting point, and this ADR is superseded rather than edited.
