# 0006. Dependency injection: Hilt

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr is split into around twenty Gradle modules ([ADR-0016](0016-module-structure-and-pure-kotlin-core.md)), several of which are pure Kotlin with no Android dependency. The app needs a way to wire providers, services, and platform adapters together that outside contributors can follow without a learning curve.

## Options considered

| Option | Tradeoff |
|---|---|
| Hilt | The documented Android default; compile-time checked; KSP support; most contributors know it |
| Koin | Lighter, runtime-resolved, friendlier to pure-Kotlin modules |
| Manual | Zero magic, grows painful past about 10 modules |

## Decision

**Hilt.** Contributor familiarity outweighs its build-time cost.

## Consequences

- Pure-Kotlin modules (`core:*`, `provider:api`, `household:protocol`) expose plain constructors and never depend on Hilt. They are wired in `:app`.
- `:app` owns the Hilt graph and registers the concrete providers; nothing else depends on a specific provider module.
- A convention plugin in `build-logic/` applies Hilt and KSP consistently to the Android modules that need it.
