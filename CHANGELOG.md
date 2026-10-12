# Changelog

Notable changes to Clickarr. The format follows Keep a Changelog. Versions are dates (`2026.10.11`, and `.2` for a
second release the same day); the household protocol, scheduler, and database carry their own version numbers.

## [Unreleased]

### Security
- Pairing uses SPAKE2 (RFC 9382): a rogue device answering a typed coordinator address can no longer test PINs offline;
  each attempt is one guess and three cancel the PIN. Household protocol version 2: both TVs need this build to pair;
  TVs already paired keep working.

### Added
- Jellyfin, alongside Plex (ADR 0022, replacing the Plex-only ADR 0019). Setup offers Jellyfin: type the server's
  address, then approve a Quick Connect code in a Jellyfin app or sign in with username and password (sent once; only
  the token is kept, on the TV). Channels, the guide, captions, "Coming up next" (without theme music for now),
  watch reporting, households, and Drive sync work the same. Jellyfin picks direct play or HLS from Clickarr's
  device profile; a file whose default audio track the TV cannot decode, but which carries another it can, plays
  as it is rather than being re-encoded.
- Holiday channels (Settings, Channels): Valentine's Day and Easter (the week before), Halloween, Thanksgiving, and
  Christmas (from the 1st of the month to the day). Switch one on and fill it from genres, Plex collections, or
  hand-picked movies; it shows in the guide only in its window and returns each year. Easter and US Thanksgiving are
  worked out per year. Holiday channels can share a channel number (100 by default), since only one is ever on.
  Database schema 6 keeps everything on upgrade.
- Captions, off by default. Turn them on in Settings, Playback, or hold OK while watching to switch them. Clickarr picks
  the program's subtitles in the TV's language; separate subtitle files and those inside the video both work, and a
  transcode has the chosen track burned in. Clickarr draws them in its own style: Inter in white with an outline and
  no box, the same for every program.
- "Coming up next" between programs on channels with 15, 30, or 60-minute slots: the next program's backdrop, time, and
  synopsis, the rest of the evening, and the show's theme music, all from what Plex already has. Settings, Playback,
  Between programs switches back to the countdown.
- Sleep timer (30 minutes to 2 hours) and "Are you still watching?" after 2, 3, or 4 hours without a button press,
  in Settings, Playback. With no answer in a minute, playback stops and the TV's own screensaver can take over; any
  button tunes back in live.

### Changed
- A TV is signed in to one server, Plex or Jellyfin. Settings has a Plex section and a Jellyfin section in place of
  Plex Server; the one this TV does not use is disabled. Signing in to a different server needs a disconnect first.
- A LAN household records its server, and a TV signed in to another server, or another kind, is refused when it
  joins; Drive sync checks the kind as well as the server.
- Tell Plex what you watched moved to Settings, Plex Server, and is now off by default. A TV that already chose a
  setting keeps it.
- Every picker and list sorts alphabetically, ignoring a leading The, A, or An (the guide keeps channel order).

### Known issues
- Captions may not appear on some movies that Plex transcodes; TV episodes are fine. Under investigation.

## 2026.10.11.2

### Changed
- New installs check the Release channel for updates by default; nightly is one choice away in Settings, About.

### Removed
- The Phase 0 spike screens and the `spike` module; the shipped app answered every question they were built for.

## 2026.10.11

The first release: everything below, built and verified on Fire TV Stick 4K Max (Fire OS 8) and the Android TV emulator.

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
- Settings, Sync: Off, Local household, or Google Drive (ADR 0020). Drive mode signs in with a TV code, keeps the household document in a Clickarr folder in your Drive (the permission covers only files Clickarr created), replays local edits over a newer remote state, and refuses a backup from a different Plex server. Needs a Google client in the build.
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
- Settings, Plex Server, Tell Plex what you watched: Off (default), Mark watched (an episode counts once this TV has played most of
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
