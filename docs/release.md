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

## One-time setup: the nightly key

Nightlies are debug builds, and a debug build is signed with whatever key the build machine has. A fresh
GitHub runner has a fresh key every time, so without a shared one no nightly can update over the last; the
system refuses the install as signed by a stranger. Make a second key, kept apart from the release key, and
give it to CI the same way (the alias defaults to `clickarr-nightly`):

```sh
keytool -genkeypair -v -keystore clickarr-nightly.jks -storetype PKCS12 -alias clickarr-nightly \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 clickarr-nightly.jks > clickarr-nightly.jks.b64
gh secret set CLICKARR_NIGHTLY_KEYSTORE_BASE64 < clickarr-nightly.jks.b64
gh secret set CLICKARR_NIGHTLY_KEYSTORE_PASSWORD   # the keystore password
gh secret set CLICKARR_NIGHTLY_KEY_ALIAS --body clickarr-nightly
```

Without the secret the CI job warns and signs with a throwaway key. Changing the nightly key, or going from
a throwaway-signed nightly to the shared key, means uninstalling Clickarr once on each TV; the app says so
when it notices, because CI publishes the signer's certificate digest in `version.json`.

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

Settings, About, "Check for updates" reads `version.json` from the nightly or the latest release and
compares its version code with the running build. It runs only when pressed; Clickarr does not check on its own.
Download verifies the APK's SHA-256 against `version.json`, and Install streams it into a package-installer
session, so the system's verdict (confirmed, cancelled, blocked source, key mismatch) comes back as a sentence
on the pane rather than a dialog that just closes. On Android 8 and later the TV has to allow installs from
Clickarr once; the pane opens that settings page when it is needed.
