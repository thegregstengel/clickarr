# 0002. UI toolkit: Compose with tv-material

**Status:** Accepted
**Date:** 2026-10-09

## Context

The two most demanding screens in Clickarr are the EPG grid (a time-positioned, D-pad-navigable custom layout) and the playback overlay that must appear instantly on channel change. Both are custom UI in any toolkit. The toolkit must run on Fire OS, which has no Google Play Services, and must be approachable for open-source contributors. Low-end Fire TV sticks with 1.5 GB RAM are in scope ([ADR-0001](0001-platform-floor-minsdk-25.md)).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Leanback (Views) | Mature, battle-tested on Fire TV, familiar to Jellyfin/Plex TV developers | In maintenance mode; the EPG grid would be a fully custom View anyway; styling fights the framework; XML plus Kotlin split raises contributor friction |
| Compose with Compose for TV (`tv-material`) | Stable since 1.0 (August 2024), now 1.1.0; custom layouts are far easier; one language for everything; focus APIs are first-class | Larger APK and higher startup cost than Views on 1.5 GB Fire sticks; some focus edge cases still need care |
| Plain Compose without `tv-material` | Fewer dependencies | Re-implementing D-pad focus indication and TV-sized components |

## Decision

**Compose with `tv-material`.** The guide and overlay are custom either way, and Compose makes custom UI cheap. Compose for TV is ordinary AndroidX with no Play Services dependency, so it runs on Fire OS. Kotlin with coroutines and Flow is the language and concurrency model, which was not seriously contested.

Note that the old `TvLazyColumn`/`TvLazyRow` are removed; standard `LazyColumn`/`LazyRow` from Compose Foundation 1.7+ handle TV focus positioning via `BringIntoViewSpec`.

## Consequences

- Compose startup and frame time on low-end Fire TV is the top-ranked technical risk. Phase 0 builds a skeleton player plus a 50-row synthetic guide and measures on a Fire TV Stick 4K (2018). Thresholds: cold start to first frame under 4 s, guide at 60 fps.
- Mitigations if the thresholds are missed: baseline profiles, R8 full mode, lazy feature initialization, lighter guide cells. Fallback: Leanback for the guide only.
- Guide focus must be handled explicitly (per-cell `FocusRequester` plus a `focusProperties` resolver) because default spatial focus produces surprising jumps on wide cells.
- Coil 3 is the image loader, chosen because it is Compose-native, small, and handles authenticated artwork URLs.
