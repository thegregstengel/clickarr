# 0016. Module structure and pure-Kotlin core boundaries

**Status:** Accepted
**Date:** 2026-10-09

## Context

The parts of Clickarr that must be correct (scheduler, household protocol, provider mappers) are logic, not UI. They should be unit-testable on the JVM in milliseconds and reasoned about without Android. Providers should be pluggable. The project is sized for one primary developer plus a handful of contributors.

## Options considered

| Option | Tradeoff |
|---|---|
| A single `:app` module | Fastest to start; nothing prevents Android imports leaking into the scheduler; tests need an emulator or Robolectric |
| A few coarse modules (app, core, data) | Some separation; provider and household seams still blur |
| Around twenty modules, each at a real seam | More Gradle files, mitigated by convention plugins; every boundary corresponds to testability, provider plug-in, or pure-vs-Android |

## Decision

**Around twenty modules with Gradle Kotlin DSL, a version catalog (`gradle/libs.versions.toml`), convention plugins in `build-logic/`, and dependency locking.** None of the boundaries are speculative.

```text
app/                    Hilt graph, navigation, manifests, banners
build-logic/            Convention plugins
core/model              Pure Kotlin domain types
core/scheduling         Pure Kotlin deterministic schedule engine
core/common             Pure Kotlin Result, Clock abstraction, logging facade
core/database           Room entities, DAOs, migrations, mappers
provider/api            MediaProvider interface and DTO contracts (pure Kotlin)
provider/plex|jellyfin|emby   Clients and mappers
provider/testing        FakeMediaProvider, fixtures, contract test suite
household/protocol      Pure Kotlin message and state types, golden tests
household/discovery     NSD wrapper, manual fallback
household/coordinator   Embedded Ktor server
household/client        Member sync client
playback/               Media3 wrapper, DeviceProfile, TuneController, drift correction
feature/player|guide|channels|setup|settings
ui/design               Theme, typography, TV components, focus helpers
```

Rules:

- `core:*`, `provider:api`, and `household:protocol` are `kotlin("jvm")` modules with no Android dependency. **The convention plugin enforces this, not discipline.**
- Features depend on application services and `core:model`, never on each other.
- Providers depend only on `provider:api`, `core:model`, and `core:common`. Nothing depends on a specific provider except `:app`, which registers them.
- Dependencies point downward and inward: TV UI, application services, then scheduling / providers / household, then platform adapters.

## Consequences

- The scheduler, protocol, and mapper tests run on every PR in seconds on the JVM. The two-device agreement test is a plain unit test.
- Pure modules expose plain constructors and never see Hilt ([ADR-0006](0006-dependency-injection-hilt.md)).
- Adding a provider means adding one module and one registration line in `:app`.
- Each module's build file is a few lines because the convention plugin carries the configuration.
