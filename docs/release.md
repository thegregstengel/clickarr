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

Versions are dates. A release is `2026.10.11`; a second release on the same day is `2026.10.11.2`; a nightly is
`2026.10.11-nightly.407561` (the date plus CI's build number). Nothing in source names a version: the tag is the
version for a release, the calendar is the version for a nightly, and a local build calls itself `dev`.

1. Move the `[Unreleased]` notes in `CHANGELOG.md` under a new `## 2026.10.11` heading and commit.
2. Tag and push: `git tag v2026.10.11 && git push origin v2026.10.11`.
3. The Release workflow takes the version from the tag, builds and signs `assembleRelease`, and publishes
   `clickarr-2026.10.11.apk`, a stable `clickarr.apk`, `SHA256SUMS.txt`, and `version.json`, with the matching
   changelog section as the notes. A tag with a suffix (`v2026.10.11-rc1`) is marked a pre-release.

`versionCode` is the minutes since 2026-01-01 on every build, nightly or release, so the in-app updater always
sees a later build as newer whatever its name.

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
