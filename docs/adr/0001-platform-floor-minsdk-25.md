# 0001. Platform floor: minSdk 25

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr targets Android TV, Google TV, and Fire TV with a single APK. Fire TV compatibility is a hard filter. The platform floor decides which devices can install the app and how much of the engineering budget goes to low-end hardware. Fire OS 5 is Android 5.1 (API 22), Fire OS 6 is Android 7.1 (API 25), Fire OS 7 is Android 9 (API 28), and Fire OS 8 is Android 10/11 (API 29/30).

## Options considered

| Option | Covers | Cost |
|---|---|---|
| minSdk 22 (Fire OS 5) | 2016-era Fire TV Stick 2nd gen, 1 GB RAM | Compose on 1 GB devices is slow; network-security-config absent before 24; large test matrix |
| minSdk 25 (Fire OS 6) | Every Fire TV from 2018 on, every Android TV/Google TV device in support | Excludes Fire OS 5 sticks |
| minSdk 28 (Fire OS 7) | Simplifies NSD and codec handling | Excludes the still-common 2018 Fire TV Stick 4K and AmazonBasics TVs on Fire OS 6 |

## Decision

**minSdk 25, targetSdk current.** API 25 is the lowest floor that keeps every device still receiving Fire OS updates while avoiding the 1 GB Fire OS 5 hardware that would otherwise dominate performance work.

## Consequences

- Fire OS 5 devices (2016 Fire TV Stick 2nd gen) are not supported at launch. This is recorded as an assumption of the proposal.
- The 2018 Fire TV Stick 4K on Fire OS 6 is in scope and becomes the reference low-end device for performance measurement (see [ADR-0002](0002-ui-toolkit-compose-tv-material.md)).
- NSD on API 25 to 27 needs the sequential-resolve workaround described in [ADR-0012](0012-discovery-nsd-with-manual-fallback.md).
- Nothing in the design requires API 25 specifically except network security config, so lowering to 22 later is a one-line change plus testing if demand appears.

## Note (2026-10-09, Phase 0 finding C1)

Netty 4.2 cannot be dexed below API 26. Keeping minSdk 25 means the household server cannot use Ktor's Netty engine; see docs/spikes.md finding C1 and ADR 0004. The floor stays at 25 until the device matrix says otherwise.
