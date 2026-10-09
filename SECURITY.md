# Security policy

Clickarr runs on televisions on a home LAN and holds credentials for your media server. Please report vulnerabilities privately.

## Reporting

Open a GitHub Security Advisory on this repository (Security tab, "Report a vulnerability"). Include the device, the app version, and reproduction steps. Expect an acknowledgement within a week.

## Scope that matters most

- Media server tokens leaking (logs, exports, sync traffic).
- Household pairing being bypassed by a device on the same network.
- Any path that lets a household member read another device's credentials.

## What is documented already

The threat model and the known residual risks are written down in [ADR 0013](docs/adr/0013-pairing-tls-tofu-pin-proof.md) and [ADR 0014](docs/adr/0014-media-credentials-never-synced.md). A report that restates a documented residual risk is still welcome if it comes with a practical fix.
