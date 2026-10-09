# 0012. Discovery: Android NSD with manual address fallback

**Status:** Accepted
**Date:** 2026-10-09

## Context

A member device needs to find the household coordinator on the LAN. The platform floor is API 25 ([ADR-0001](0001-platform-floor-minsdk-25.md)), which includes Fire OS 6 and 7 builds with known mDNS quirks. Discovery must not itself grant any trust; pairing ([ADR-0013](0013-pairing-tls-tofu-pin-proof.md)) handles that.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Android NSD (`NsdManager`, mDNS/DNS-SD) | Built in, no dependency, Fire OS supports it (AOSP) | Known flakiness before API 28 (one resolve at a time, occasional stale entries); needs a multicast lock on some devices |
| JmDNS library | Works around NSD bugs | Unmaintained; raw sockets fight the system's mDNS responder |
| Custom UDP broadcast beacon | Simplest to reason about; Jellyfin does this | Broadcast is blocked by some router isolation features; reinvents DNS-SD |
| Manual IP entry only | Zero discovery risk | Poor UX |

## Decision

**NSD for discovery, with manual `host:port` entry always available as a fallback.** Pairing and sync do not depend on how the address was learned, so discovery can be replaced later without protocol changes.

Service definition: type `_clickarr._tcp`, instance name is the device's friendly name, port is the coordinator's HTTP port (default 47831, random free port advertised if taken). TXT record: `v` (protocol version), `hid` (household id), `did` (device id), `role` (`coordinator` or `member`), `name` (household name), `pk` (public key fingerprint).

## Consequences

- Clients browse for 10 s, list coordinators found, and resolve on selection, sequentially on API below 28.
- A `WifiManager.MulticastLock` is held during browse and released afterwards; several Fire TV and Android TV builds filter multicast otherwise. This requires the `CHANGE_WIFI_MULTICAST_STATE` permission.
- The coordinator's last known address is cached in `sync_state` so reconnection after reboot needs no discovery; a fresh browse runs only if the cached address fails.
- Members advertise too with `role=member`, so a future "promote member" or "which devices are in my household" screen works without a registry.
- NSD reliability on Fire OS 6/7 is an accepted risk, verified by Phase 0 Spike D (Fire TV to Android TV emulator with multicast lock) and covered by an instrumented loopback test.
