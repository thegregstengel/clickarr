# 0019. MVP targets Plex only

**Status:** Accepted
**Date:** 2026-10-09

## Context

The approved proposal planned Plex and Jellyfin for Phase 1 and Emby for Phase 3. The maintainer's own setup is Plex, which is where every spike and every Phase 1 and 2 exit criterion will actually be exercised. Building two provider clients before the core experience is proven doubles the fixture work and the device-matrix work without proving anything new about Clickarr itself.

## Options considered

| Option | Tradeoff |
|---|---|
| Plex and Jellyfin in Phase 1 as proposed | Validates the provider abstraction early against two very different APIs; costs a second client, second fixture set, and a Jellyfin test server before the first channel plays |
| **Plex only through the MVP, Jellyfin and Emby afterwards** | Fastest path to "channel 10 at 7:17 PM is right on two TVs"; the abstraction is still exercised by the fake provider in `provider:testing`; risk that Plex-isms leak into the normalized model unnoticed |
| Jellyfin only | Better documented API, but not what the maintainer runs day to day |

## Decision

The MVP (Phases 0 to 2) implements the Plex provider only. Jellyfin and Emby are scheduled after the household milestone, in the phase that was already going to add Emby.

The `MediaProvider` interface (ADR 0007), the normalized model, and the rule that nothing outside `provider:*` sees a provider-specific type all stay in force. To keep Plex-isms from leaking, `provider:testing` ships a `FakeMediaProvider` from Phase 1 and the contract test suite runs against both it and the Plex client.

## Consequences

- Phase 1 scope shrinks on the provider side: one auth flow (plex.tv PIN plus manual URL and token), one metadata client, one playback path.
- The freed capacity goes to channel sources: Phase 1 now includes every `ProgrammingSource` (shows, library with filters, collection, playlist, explicit picks, union of sources) rather than only shows and libraries. Custom channels such as "all the Lord of the Rings films" or "1980s sitcoms" are an MVP feature, not a Phase 3 one.
- Spike B measures Plex direct play and Plex HLS only.
- The first-run screen still shows three server choices in the mockups; Jellyfin and Emby render as "coming soon" until their providers exist.
- Supersedes the Phase 1 and Phase 3 provider lines in proposal section 20; the proposal carries an amendment note rather than being rewritten.
