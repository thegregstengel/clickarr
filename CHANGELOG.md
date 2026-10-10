# Changelog

Notable changes to Clickarr. The format follows Keep a Changelog; versions follow semver while pre-1.0 means
the household protocol and database may still change between minor versions.

## [Unreleased]

### Added
- Plex sign-in (plex.tv PIN or manual address and token) and library browsing through the provider contract.
- Channels from shows, whole libraries with decade and genre filters, Plex collections, and playlists; in order or
  shuffled; back-to-back, 15, or 30 minute slots; Lucide icons.
- Deterministic schedules: every device computes the same program at the same offset from a frozen lineup.
- Player with tune-in offset, overlay, Up Next, boundary advancement, filler card, numeric channel entry.
- Program guide with time-column focus, details card, current-program progress, row and time jumps; Favorites.
- Settings: General, Media Server, Channels (refresh lineups), Appearance, Playback, Household, Diagnostics, About.
- Households: one TV coordinates, others join with a PIN over the LAN and follow the same lineup.
- Device profile probed from the device's decoders and display (4K, HEVC, HDR flags).
- Settings, Appearance, Size: Small, Medium (default), or Large scale for the whole interface, sized for a real TV.
- Edit existing channels; 1 hour time slots; a Reality glyph and friends; show picker with a side panel.
- Settings, Appearance, Theme: Clickarr (navy), Dark, Light, and Dracula.
- Guide: the focused program scrolls its title and episode when they do not fit; OK on a channel cell stars it as a favorite.
- Nightly debug builds at clickarr.net/nightly.

### Planned
- Google Drive sync as the alternative to a LAN household (ADR 0020): backup, restore, and TVs in different homes on one lineup.

### Security
- Plex tokens stay in the Android Keystore on each device and are never synchronized between devices.
- Pairing proof binds the PIN to both devices' certificate fingerprints. Household transport is plain LAN HTTP until
  the TLS acceptor lands; do not pair across untrusted networks.
