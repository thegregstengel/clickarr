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
- Shell is Guide and Favorites with a settings cog; channels are created and edited under Settings, Channels. New channels default to back-to-back slots.
- Guide: the focused program scrolls its title and episode when they do not fit; OK on a channel cell stars it as a favorite.
- Settings, Sync: Off, Local household, or Google Drive (ADR 0020). Drive mode signs in with a TV code, keeps the household document in Drive's app folder, replays local edits over a newer remote state, and refuses a backup from a different Plex server. Needs a Google client in the build.
- Settings, Channels, Suggest channels: a practical starting lineup from your collections, genres, decades, and playlists.
- Settings, About and Updates: nightly or release channel, check, download with checksum, and install.
- Guide preview card with thumbnail and synopsis; player mini-guide on Left and Right.
- Nightly debug builds at clickarr.net/nightly.

### Security
- Plex tokens stay in the Android Keystore on each device and are never synchronized between devices.
- Household transport is TLS from each device's Keystore certificate, with trust-on-first-use pinning, and the
  pairing proof binds the PIN to both certificate fingerprints. Plain HTTP only as a loudly labelled fallback.
