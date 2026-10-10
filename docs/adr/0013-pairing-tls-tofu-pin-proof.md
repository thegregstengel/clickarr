# 0013. Pairing: TLS with Keystore certificates, TOFU pinning, PIN-bound HMAC proof

**Status:** Accepted
**Date:** 2026-10-09

## Context

Threat model: a home LAN with a guest's phone, a compromised IoT device, or a neighbor on a poorly secured network. Assets protected by the household protocol are channel configuration and lineups (low sensitivity), household membership (an intruder can rearrange the lineup; annoying, not dangerous), and the household credentials themselves. Media server tokens never travel over this protocol ([ADR-0014](0014-media-credentials-never-synced.md)), which caps the blast radius. Requirements: discovery alone must not authenticate, and an unrelated device must not join silently.

## Options considered

| Option | What it provides | Cost |
|---|---|---|
| A. Plain HTTP, PIN pairing, bearer token afterwards | Blocks silent joins | A LAN sniffer can read the token and the PIN exchange, then join or act as a member |
| B. Plain HTTP, PIN pairing, per-request HMAC signatures | Blocks silent joins; a sniffer cannot reuse credentials from later traffic | A sniffer during the pairing window can brute-force a 6-digit PIN offline from the captured proof and derive the device secret |
| C. TLS with per-device self-signed certificates, PIN-bound proof, trust-on-first-use pinning | Confidentiality and integrity for all traffic; token theft by sniffing impossible; PIN proof is encrypted | Needs a TLS-capable embedded server (Netty, not CIO); certificate generation and pinning code; an active attacker who spoofs the mDNS record and intercepts the pairing exchange can still brute-force the PIN offline |
| D. PAKE (SPAKE2/OPAQUE) | Closes the active-MITM gap fully | No well-maintained Android PAKE library; hand-rolled crypto in an open-source TV app is a liability |

## Decision

**Option C, with a hardened pairing window:** 3 PIN attempts, 2-minute expiry, the coordinator must be in an "accepting joins" mode the user turns on from Settings, and the coordinator shows a confirmation naming the joining device before issuing a token.

Identity: each install generates once an EC P-256 key pair in the Android Keystore with a self-signed X.509 certificate (no BouncyCastle). The SHA-256 of the SubjectPublicKeyInfo is the device fingerprint, advertised in the NSD TXT record and shown in settings.

Flow: the coordinator generates a 6-digit PIN. The joiner connects over TLS, pins the coordinator fingerprint from the TXT record (TOFU), and calls `POST /v1/pair/start` with its id, name, and fingerprint, receiving a session id and nonce. The user types the PIN; the joiner sends `proof = HMAC-SHA256(key = HKDF(PIN, nonce), msg = sessionId || joinerFp || coordinatorFp)` to `POST /v1/pair/complete`. The coordinator verifies, asks the user to confirm, issues a 256-bit random device token, stores the joiner fingerprint, and returns the household id, token, state snapshot, and its own fingerprint. Binding the proof to both fingerprints makes a captured proof useless in any other TLS session.

Afterwards every member request carries `Authorization: Bearer <deviceToken>` over TLS to the pinned coordinator certificate. Tokens are revocable from the coordinator's device list. There is no member-to-member communication in the MVP.

## Consequences

- **Residual risk, stated plainly:** an attacker already on the LAN, running an mDNS spoofer at the exact moment the user is pairing, could capture the PIN proof and attempt a join within the window. The user would see this as an unexpected second confirmation prompt on the coordinator. Accepted for the MVP.
- **Upgrade path:** option D (PAKE) if a credible, maintained library appears. The protocol reserves a `pairingMethod` field for it.
- **Fallback if the Phase 0 Netty TLS spike fails on Fire OS 6:** option B over plain HTTP, with an explicit note in the security docs. B's protocol messages are identical to C's, so no second pairing migration is needed. A and B were not chosen for the MVP because swapping transports later would force every household to re-pair.
- Requires the Netty server engine ([ADR-0004](0004-http-ktor-client-and-server.md)). Device token and coordinator fingerprint are stored encrypted on the device; the private key never leaves the Keystore; the PIN lives in memory for at most 120 s.
- Pairing tests on the Ktor test host cover success, wrong PIN, expired PIN, attempt limit, replayed proof against a different fingerprint, and revoked token.

## Status note (2026-10-10)

Implemented: Keystore EC identity and fingerprint (`household:discovery`), PIN-bound HKDF/HMAC proof (`household:protocol`),
coordinator pairing state machine with the three-attempt rule (`household:coordinator`), bearer tokens. Not yet implemented:
the TLS acceptor in front of the CIO engine and certificate pinning in the member's OkHttp client. Both wait on spike C;
the current build speaks plain HTTP on the LAN.

**Implementation note (2026-10-10).** Landed as `TlsFrontDoor`: the platform `SSLServerSocket` terminates TLS on the
LAN port with the Keystore certificate and copies bytes to a loopback-only CIO engine, which keeps HTTP and WebSocket
handling in Ktor and avoids Netty (spike C, finding C1). The member pins with `TrustOnFirstUse` in OkHttp; the proof
uses the fingerprint seen on the wire. Plain HTTP remains only as an explicit fallback when the device cannot start
TLS, advertised as `tls=0` and named in the Household pane.

