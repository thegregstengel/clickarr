# Contributing to Clickarr

Thanks for looking. Clickarr is small and early, which means a good time to get involved and a bad time to assume anything is settled.

## Before you write code

- Read [docs/architecture.md](docs/architecture.md). It is short and explains why the scheduler is a pure function and why nothing routes video through another TV.
- Skim the [ADR index](docs/adr/README.md). If your change contradicts an accepted decision, open an issue proposing a new ADR first.
- Check the roadmap in [docs/architecture-proposal.md](docs/architecture-proposal.md#20-phased-mvp-roadmap) so you know which phase we are in.

## Building

See [docs/development.md](docs/development.md). Short version: JDK 17, Android SDK 36, `./gradlew assembleDebug`, sideload the APK.

## Rules of the road

- **No code from other media clients.** Do not copy from Plex, Jellyfin, Emby, Kodi, QuasiTV, or anything GPL. Clickarr is MIT and must stay clean. Read the API docs, not the apps.
- **No secrets in the repo.** Tokens, keystores, and local server addresses never get committed. `.gitignore` covers the obvious ones; think before adding files.
- **Pure Kotlin stays pure.** `core:*` and `household:protocol` must not import Android. The build enforces it; please do not work around it.
- **Tests go with the code.** Scheduler and protocol changes need unit tests. Provider changes need fixture-based tests. UI changes should at least keep the existing Compose tests green.
- **Small PRs.** One concern per PR. If a refactor is needed first, send it separately.
- **Logs are redacted.** Use `net.clickarr.core.common.Log`, never `android.util.Log` directly outside the app module. Playback URLs contain tokens.

## Style

`./gradlew detekt` must pass. Kotlin official style, trailing commas on, 140-column lines. No em-dashes in docs or strings, plain hyphens are fine.

## Commits and PRs

Conventional commit prefixes (`feat:`, `fix:`, `docs:`, `build:`, `test:`, `refactor:`) help the changelog. Describe what changed and why; link the ADR or issue.

## Reporting bugs

Use the Diagnostics screen (Settings, About, press OK five times once it exists) to export redacted logs, and say which device and which media server you use. "Channel 10 is wrong on the bedroom TV" bugs need the lineup hash and clock offset from that screen.

## Security

See [SECURITY.md](SECURITY.md).
