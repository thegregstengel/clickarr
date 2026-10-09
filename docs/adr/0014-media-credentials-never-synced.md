# 0014. Media server credentials are never synchronized and are Keystore-encrypted at rest

**Status:** Accepted
**Date:** 2026-10-09

## Context

A Plex account token grants access to every server and setting on the account; a other media servers token is a full user session. Households synchronize configuration between TVs ([ADR-0011](0011-household-sync-single-document.md)), and it would be convenient to sync sign-ins too. Jetpack Security's `EncryptedSharedPreferences`, the usual answer for at-rest secrets on Android, is deprecated. TVs have no lock screen.

## Options considered

| Concern | Rejected | Chosen |
|---|---|---|
| Sync scope of media server tokens | Share sign-ins across the household for convenience; a lost or sold TV then exposes every TV's access | Never synchronized; each device signs in to each server itself |
| At-rest storage | `EncryptedSharedPreferences` (deprecated); Room or DataStore in plaintext | A `SecretStore` using an AES-256-GCM key in the Android Keystore to encrypt a Proto DataStore file |

## Decision

**Media server tokens and API keys are never synchronized.** The household syncs server locations (kind, server identity, friendly name, URLs) so a joining device can be prompted to sign in, but never tokens. The UX cost is one sign-in per TV per server, which every other media client imposes too.

**At rest, secrets live in `secrets.pb`, encrypted by a `SecretStore`** with an AES-256-GCM key generated in the Android Keystore (`setUserAuthenticationRequired(false)`) and a random IV per write. Keystore keys cannot leave the device, so a copied data directory is useless elsewhere. The same store holds the household device token and the coordinator certificate fingerprint; the device private key stays in the Keystore itself; the pairing PIN is memory-only.

## Consequences

- Classification is fixed: tokens, device token, and fingerprint are device-specific and encrypted; server locations, channels, lineups, favorites, and names are household-wide in Room plaintext; last channel, device profile, and UI preferences are device-specific in DataStore.
- On devices with a broken Keystore (rare Fire OS builds), `SecretStore` falls back to a software key in app-private storage and logs a warning in the Diagnostics screen.
- Plex requires `X-Plex-Token` as a query parameter on media URLs, so the logging facade redacts `X-Plex-Token`, `api_key`, and `Authorization` everywhere, including ExoPlayer logs via a custom `EventLogger`, crash reports, and diagnostics exports.
- Lineup resolution must run on the coordinator, which holds its own credentials ([ADR-0011](0011-household-sync-single-document.md)).
- Permissions stay minimal: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`. No storage, location, or account access.
- A future opt-in "share this server's sign-in with the household" would require end-to-end encryption to the member's public key and a visible warning. Not in the MVP.
- `SecretStore` round-trip on the Keystore is covered by an instrumented test.
