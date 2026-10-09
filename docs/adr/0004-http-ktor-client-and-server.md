# 0004. HTTP: Ktor client and embedded server, Netty engine for TLS

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr needs an HTTP client for the Plex, Jellyfin, and Emby APIs, and the household coordinator is an HTTP server embedded inside the app ([ADR-0011](0011-household-sync-single-document.md)). The pairing design ([ADR-0013](0013-pairing-tls-tofu-pin-proof.md)) requires that server to speak TLS. The protocol module should be testable in-process.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Retrofit plus OkHttp | Most familiar Android stack; excellent interceptors | Client only; the coordinator still needs a separate server library |
| Ktor client plus Ktor embedded server | One library for both roles; `kotlinx.serialization` native; Kotlin-first; multiplatform if ever wanted | Less common in Android codebases; the CIO server engine has no TLS |
| OkHttp client plus NanoHTTPD server | Both tiny and proven | Two unrelated APIs; NanoHTTPD is barely maintained |

Server engine, given Ktor:

| Engine | Tradeoff |
|---|---|
| CIO | The natural Android choice, small, but does not support HTTPS |
| Netty | Supports TLS and runs on Android, at roughly 3 to 5 MB of APK |

## Decision

**Ktor for both client and server, `kotlinx.serialization` for JSON, Netty as the server engine because TLS is required.** Using one library for both halves keeps the protocol module symmetric, and Ktor's test host runs server and client in one JVM for fast tests.

## Consequences

- Phase 0 includes a spike (Spike C) to confirm Netty plus Keystore-backed certificates work on Fire OS 6 and Android TV 14 before committing. If it fails, the fallback is CIO without TLS and pairing option B from ADR-0013, whose message formats are identical.
- APK grows by the size of Netty. Accepted.
- Provider clients, the coordinator, and the member sync client share one HTTP stack, so fixtures via Ktor `MockEngine` and the Ktor test host cover all of them.
- Pairing and sync tests (success, wrong PIN, expired PIN, attempt limit, replayed proof, revoked token) run on the JVM against the in-process test host.
