# Contributing to Clickarr

Thanks for looking. Clickarr is small and early, which means a good time to get involved and a bad time to assume anything is settled.

## Before you write code

- Read [docs/architecture.md](docs/architecture.md). It is short and explains why the scheduler is a pure function and why nothing routes video through another TV.
- Skim the [ADR index](docs/adr/README.md). If your change contradicts an accepted decision, open an issue proposing a new ADR first.
- Check the roadmap in [docs/architecture-proposal.md](docs/architecture-proposal.md#20-phased-mvp-roadmap) so you know which phase we are in.

## How a change gets in

1. Fork the repository and make a branch in your fork for the one thing you are changing.
2. Open a pull request against `main`. The template asks what changed, how you tested it, and a short checklist.
3. The maintainer approves the CI run (detekt, unit tests, Android lint, a debug build; it runs on pull requests only
   after that approval, so run `./gradlew detekt test` yourself first), reviews the change, and squash-merges it.

`main` is protected by a repository ruleset: no direct pushes except by the maintainer, no force pushes or
deletions, pull requests only, with a passing "Lint, test, assemble" check and a code-owner review before merge.
The emulator screenshot suite, the nightly release, and the website deploy run only from `main`, so a pull
request never spends those minutes or touches the signing keys (fork pull requests do not receive repository
secrets at all).

## Building

See [docs/development.md](docs/development.md). Short version: JDK 17, Android SDK 36, `./gradlew assembleDebug`, sideload the APK.

## Rules of the road

- **No code from other media clients.** Do not copy from Plex, Kodi, QuasiTV, or any other media client, GPL or otherwise. Clickarr is MIT and must stay clean. Read the API docs, not the apps.
- **Plex only.** Clickarr is a Plex client by design (ADR 0019); pull requests that add another media server, or abstract toward one, will be declined.
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

Copy the lines from Settings, Diagnostics (device, scheduler version, each channel's lineup hash and current slot, recent redacted log), and say which device and which Plex server version you use. "Channel 10 is wrong on the bedroom TV" bugs need the lineup hash and clock offset from that screen.

## Security

See [SECURITY.md](SECURITY.md).
