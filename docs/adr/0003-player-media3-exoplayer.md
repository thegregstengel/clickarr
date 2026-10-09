# 0003. Player: Media3 ExoPlayer

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr's core trick is tuning into a program at a computed offset. The player must support progressive MP4/MKV direct play, HLS (Plex transcodes), DASH, and precise seeking. It must run on Fire TV. Channel changes should be fast, so codec re-initialization cost matters.

## Options considered

| Option | Tradeoff |
|---|---|
| AndroidX Media3 ExoPlayer | Handles direct play, HLS, and DASH; precise `seekTo`; Amazon's own Fire TV guidance recommends it; clipping and playlist APIs support seamless program boundaries |
| Android `MediaPlayer` | Too limited and opaque; no reliable control over seeking, formats, or diagnostics |

## Decision

**AndroidX Media3 ExoPlayer**, wrapped behind a small `PlayerEngine` interface so tests can substitute a `FakePlayer`. This is the only realistic option.

## Consequences

- ExoPlayer is created once and reused across channel changes to avoid codec re-initialization.
- The player lives in the player screen's ViewModel scope. TV apps have no background audio use case, so there is no foreground service.
- Program boundaries use ExoPlayer's playlist plus `clippingConfiguration`: when the current airing ends in under 20 s, the next item is appended and the current item is clipped at `contentEnd`, so the player auto-advances.
- A `DriftMonitor` compares player position to expected position every 10 s and re-seeks if they differ by more than 5 s.
- ExoPlayer's own logging is routed through a custom `EventLogger` so that tokens in URLs are redacted ([ADR-0014](0014-media-credentials-never-synced.md)).
- Codec and HDR quirks on Fire TV (Dolby Vision profiles, HEVC Main10, audio passthrough) are an accepted risk, mitigated by a conservative device profile table, user overrides, and ExoPlayer's decoder fallbacks.
