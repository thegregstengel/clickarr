# Releasing Clickarr

Releases are built and signed on GitHub Actions from a tag. Nothing is built or signed on a laptop.

## One-time setup: the signing key

Clickarr sideloads, so the APK signature is the only thing that ties one version to the next; a device will
refuse to update over an APK signed with a different key. Make one key, keep it, back it up offline.

```sh
keytool -genkeypair -v -keystore clickarr-release.jks -alias clickarr -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 clickarr-release.jks > clickarr-release.jks.b64
```

Add four repository secrets (Settings, Secrets and variables, Actions):

| Secret | Value |
|---|---|
| `CLICKARR_KEYSTORE_BASE64` | contents of `clickarr-release.jks.b64` |
| `CLICKARR_KEYSTORE_PASSWORD` | the keystore password |
| `CLICKARR_KEY_ALIAS` | `clickarr` |
| `CLICKARR_KEY_PASSWORD` | the key password |

The keystore and its passwords never go in the repo, in an issue, or in a chat. `app/build.gradle.kts` reads them
from the environment only when `CLICKARR_KEYSTORE_PATH` is set, so local release builds stay unsigned.

## Cutting a release

1. Move the `[Unreleased]` notes in `CHANGELOG.md` under a new `## [0.1.0] - 2026-10-20` heading.
2. Set `versionName = "0.1.0"` in `app/build.gradle.kts` (`versionCode` is set by CI to the minutes since
   2026-01-01, so every build, nightly or release, is a newer version). Pre-releases use a suffix,
   `0.1.0-rc1`, and are marked as such on GitHub automatically.
3. Commit, then tag and push: `git tag v0.1.0 && git push origin main v0.1.0`.
4. The Release workflow checks the tag against `versionName`, builds `assembleRelease`, and publishes
   `clickarr-0.1.0.apk`, a stable `clickarr.apk`, and `SHA256SUMS.txt` with the changelog section as notes.
5. Back on main, set `versionName` to the next `-dev` version.

`clickarr.net/apk` redirects to the latest release's `clickarr.apk`, which is the URL to give Downloader.
`clickarr.net/nightly` keeps pointing at the debug nightly.

## Google Drive sync

Create a Google Cloud project, enable the Google Drive API, publish an OAuth consent screen named Clickarr with the
`drive.appdata` and `email` scopes, and create an OAuth client of type **TVs and Limited Input devices**. Add the id
and secret as repository secrets `CLICKARR_GOOGLE_CLIENT_ID` and `CLICKARR_GOOGLE_CLIENT_SECRET`; CI passes them to
every build. For the TV flow Google does not treat the client secret as confidential (SECURITY.md).

## In the app

Settings, About, "Check for updates" asks the GitHub releases API for the latest tag and compares it with the
running version. It runs only when pressed; Clickarr does not check on its own.
