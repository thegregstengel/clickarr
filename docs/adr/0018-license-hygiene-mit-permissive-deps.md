# 0018. License hygiene: MIT, permissive dependencies only, no GPL code

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr is MIT-licensed (the LICENSE file is already in the repository). The closest prior art for TV media players is GPL-licensed, which makes clean-room discipline essential.

## Options considered

| Option | Tradeoff |
|---|---|
| MIT project, only Apache-2.0/MIT/BSD dependencies, no code from GPL or closed clients | Clean redistribution and F-Droid eligibility; every provider client is written from the wire protocol, not borrowed |
| Relicense to GPL to allow reuse of GPL client code | Would permit copying from GPL TV clients; conflicts with the MIT license already published and narrows downstream use |
| Ignore dependency licenses until release | Risk of discovering a copyleft or proprietary dependency late, forcing a rewrite |

## Decision

**The project stays MIT. All dependencies must be permissively licensed (Apache-2.0, MIT, BSD). No code may be taken from GPL media clients or from any closed product.** This is documented in `CONTRIBUTING.md` and enforced in review.

## Consequences

- Provider clients are hand-written from the APIs ([ADR-0007](0007-hand-written-provider-clients.md)); fixtures are recorded from real servers and sanitized, never copied from other clients.
- The stack chosen elsewhere (Compose, Media3, Ktor, Room, Hilt, Coil, Kotest) is Apache-2.0. The suggested UI font, Inter, is SIL OFL.
- The `dependency-review` CI check and Renovate flag new dependencies so license review happens at PR time.
- Reviewers should treat "I adapted this from another TV client" as a blocker, not a nit.
