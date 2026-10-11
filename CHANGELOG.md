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
- Settings, Appearance, Theme: Clickarr (navy), Dark, Light (a cool slate, picked on a real TV from six candidates), and Dracula.
- Shell is Guide and Favorites with a settings cog; channels are created and edited under Settings, Channels. New channels default to back-to-back slots.
- Guide: the focused program scrolls its title and episode when they do not fit; OK on a channel cell stars it as a favorite.
- Settings, Sync: Off, Local household, or Google Drive (ADR 0020). Drive mode signs in with a TV code, keeps the household document in Drive's app folder, replays local edits over a newer remote state, and refuses a backup from a different Plex server. Needs a Google client in the build.
- Settings, Channels, Suggest channels: a practical starting lineup. Rerun channels for the shows with the most episodes,
  collections and playlists with enough in them, genre themes (Sitcoms, Cartoons, Horror Movies) and decades with plenty of
  titles, and one shuffled movie channel; everything back to back, thresholds relaxed for a small library.
- Settings, About and Updates: nightly or release channel, check, download with checksum, and install.
- Updates: nightlies are signed with one long-lived key so they install over each other; the install goes through a
  package-installer session and reports the system's verdict on the pane; one button carries check, download (with
  a progress bar), and install so focus stays put; the app says up front when a build is signed with another key.
- Settings, General, Time: automatic network time and a time zone; the manual clock nudge is gone.
- Launcher icon is the square TV mark again. Fire TV shows every sideloaded app as a square icon on its own wide tile and ignores the banner, so a 16:9 icon only got letterboxed; a full-width tile needs an Amazon Appstore listing. Android TV and Google TV keep using the 16:9 banner.
- Guide preview card with thumbnail and synopsis; player mini-guide on Left and Right.
- Episodes in a row: a channel can play 2, 3, or a range of one show's episodes back to back in aired order, shuffled between runs or alternating shows in order (scheduler version 2).
- Clock in the shell beside the settings cog; Settings, General, Time: zone and automatic network time (SNTP).
- Guide shows two hours across the screen at any size and starts at the current half hour.
- Channel editor: the number starts at the next one not in use (counting from 1), the form says when a number is taken and by
  which channel, and Save stays on the form instead of failing out.
- Settings, General is rows that open a picker (Theme, Size, Overlay stays for, Clock, Time zone): each row shows its current
  value, OK opens a modal list, OK on a choice returns to the pane. The chip rows are gone from General.
- Settings, Channels, Favorites tab: Show or Hide. Hide takes Favorites out of the top row for lineups small enough not to
  need it; the stars in the guide keep working.
- Settings, General, Settings lock: a four-digit code (entered on an on-screen pad) that covers Settings and the channel editor
  whenever they are opened; the guide, favorites, and watching stay open. The code lives on one TV and is never synced;
  five wrong tries earn a thirty-second wait; clearing the app's data is the recovery for a forgotten code.
- Settings, Playback, Tell Plex what you watched: Off, Mark watched (default; an episode counts once this TV has played most of
  it in one sitting, so tuning in for the ending or leaving a channel on overnight does not), or Progress too (also sends the
  running position, so episodes appear in Continue Watching).
- Settings, Channels, Keep lineups current: once a day Clickarr re-reads every channel's source from Plex, so new episodes
  join at the next program boundary without anyone pressing Refresh. Members of a household leave it to the coordinator.
- Nightly debug builds at clickarr.net/nightly.

### Security
- Plex tokens stay in the Android Keystore on each device and are never synchronized between devices.
- Hardening from the 2026-10-10 review (SECURITY.md): household is TLS only with proofs bound to the observed certificate,
  PIN guesses counted per PIN, channel bounds enforced on every device, request and pool limits on the coordinator, Drive
  sync forgets its base on leave, Plex client follows no redirects, SNTP replies verified, exception text redacted,
  nightlies non-debuggable, CI actions pinned and keystores wiped.
- Household transport is TLS from each device's Keystore certificate, with trust-on-first-use pinning, and the
  pairing proof binds the PIN to both certificate fingerprints. Plain HTTP only as a loudly labelled fallback.
