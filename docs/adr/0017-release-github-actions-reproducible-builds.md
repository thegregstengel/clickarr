# 0017. Release: GitHub Actions, signed APK on tag, reproducible unsigned builds

**Status:** Accepted
**Date:** 2026-10-09

## Context

Clickarr is an open-source sideloaded TV app. Users install it through Fire TV's Downloader app or `adb`, so GitHub Releases is the natural distribution channel. F-Droid inclusion is wanted later and requires reproducible builds. There is no Clickarr cloud; the project must not depend on store accounts to ship.

## Options considered

| Concern | Choice | Alternatives not taken |
|---|---|---|
| CI platform | GitHub Actions, co-located with the repository | External CI adds accounts and secrets for no gain |
| Release trigger | Tag `v*` builds, signs, checksums, and publishes a GitHub Release with a changelog from conventional commits | Manual local releases are unreproducible and tie releases to one machine |
| Signing key custody | One keystore generated offline, held in GitHub Actions secrets and the maintainer's password manager, never in the repo or logs | Key in repo (unacceptable); per-contributor keys (updates would break) |
| Reproducibility | Unsigned APK byte-identical from a tag checkout, verified by building twice on separate runners | Trusting a single build; blocks F-Droid |
| Store distribution | GitHub Releases canonical; F-Droid after reproducibility is proven | Google Play and Amazon Appstore are later considerations, not architectural inputs |

## Decision

Workflows: `ci.yml` on every PR and push to `main` (ktlint, detekt, Android Lint, JVM unit tests, debug APK artifact so any PR can be sideloaded); `instrumented.yml` nightly and on label (Android TV emulators at API 25 and API 33); `release.yml` on tag `v*` (build, sign, SHA-256 checksums, SBOM, GitHub Release); `dependency-review` on PR.

Reproducibility measures: pinned Gradle wrapper with checksum verification, version catalog plus lock files, `BuildConfig` free of timestamps, deterministic resource ordering, fixed R8 seeds with the map file published, documented JDK (Temurin, exact version in `.java-version`). The release workflow builds twice on separate runners and fails if the unsigned APKs differ. Verification uses `apksigner verify --print-certs` plus `diffoscope`.

## Consequences

- Each release ships `clickarr-<version>-release.apk`, a `.sha256`, and a `CHANGELOG`, plus a stable short URL redirecting to the latest APK for Downloader.
- An opt-in in-app update checker reads the GitHub Releases API and shows the changelog; installation still goes through the system installer. No silent updates.
- Semantic versions; `versionCode = major*10000 + minor*100 + patch` derived from the tag. Protocol and scheduler versions are independent integers that bump only on incompatible change.
- Debug builds use the standard debug key so contributors can install over each other's builds.
- Losing the release key means a new package name is required for updates, so `docs/release.md` records the rotation note and the key is backed up in two places.
- Supply chain: dependency locking, Renovate with grouped PRs, no binary blobs in the repo.
- Reproducible-build verification in CI and F-Droid metadata are scheduled for Phase 4.
