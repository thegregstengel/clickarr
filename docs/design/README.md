# Clickarr design reference

Source mockups live in `mockups/`. `interface-showcase.png` is the original five-screen composite; the `screen-*.png` files are crops of it for easier reference. `logo-neon.png` is the logo concept.

This document records what the mockups establish, the tokens extracted from them, how they map onto the architecture proposal, and the gaps that still need a decision. It is the design input for Phase 1.

## 1. Screen inventory

| Screen | File | What it shows |
|---|---|---|
| Player with overlay | `screen-player-overlay.png` | Full-screen video. Top-left logo pill, top-right clock. Bottom-left: channel number badge, settings gear, channel icon + name, program title, episode title, slot time, progress bar with `17:03 / 30:00`. Bottom-right: "Up Next" card with thumbnail, title, slot time. |
| Guide | `screen-guide.png` | Top bar: logo, tabs **Guide · Channels · Favorites · Settings**, clock. Left column headed "Today" with channel number, name, icon. Time header at 30-minute marks. Vertical glowing now-line. Focused cell solid blue; other cells dark navy with faint border. |
| Channels | `screen-channels.png` | Left list of channels (number, icon, name), focused row solid blue. Right: live preview of the focused channel with an info card (channel badge, channel name, thumbnail, program title, slot time). |
| Settings → Household | `screen-settings-household.png` | Left nav: General, Media Server, Channels, Scheduling, Household, Appearance, Playback, About. Household pane: "Connected to Household: Living Room (Coordinator)" with status dot; device list with role, "Synced N minutes ago", status dots; "Manage Household" button. |
| First run | `screen-setup-server.png` | Logo + tagline "Turn your media library into TV". Card "Connect to Your Media Server" with Plex, Jellyfin, Emby rows, brand icons, chevrons. Blurred living-room backdrop. |

Logo: retro CRT television with two antennae, screen filled with a cyan→blue→magenta gradient, on a near-black navy field. Wordmark is a heavy geometric sans: "Click" in white, "arr" in a blue→violet gradient.

## 2. Tokens extracted from the mockups

Hex values were sampled from the PNG, then rounded to a small palette. Treat them as the starting set for `ui/design`, not pixel law.

### Color

| Token | Value | Sampled from |
|---|---|---|
| `bg.base` | `#05070F` | deep background behind panels, logo field |
| `bg.panel` | `#0A1220` | settings cards, setup rows, channel list panel |
| `bg.surface` | `#131A36` | overlay cards, up-next card |
| `bg.cell` | `#1A2A47` | guide program cells |
| `bg.cellBorder` | `#0F2038` | guide cell outline |
| `bg.elevated` | `#1E2B45` | setup card, header band |
| `accent.primary` | `#1479FD` | focused guide cell, focused channel row, primary buttons |
| `accent.primaryDeep` | `#063677` | active top tab, focused channel label in guide |
| `accent.glow` | `#00ACFF` | now-line, progress bar fill |
| `accent.cyan` | `#00E2FD` | logo screen highlight |
| `accent.violet` | `#7E4DFD` | logo wordmark gradient end, logo screen |
| `accent.magenta` | `#AF2EFC` | logo screen gradient |
| `status.ok` | `#0CE568` | household online dots |
| `text.primary` | `#F2F5FA` | titles, numbers |
| `text.secondary` | `#A9B4C8` | episode names, timestamps, captions |
| `text.muted` | `#6B7890` | progress track, dividers |
| `focus.ring` | `#FFFFFF` at 80 % | not in mockup; see §4 |

Brand gradient (logo, splash, optional hero accents): `linear-gradient(135deg, #00E2FD, #1284FD, #AF2EFC)`.

### Typography

The mockups use a bold geometric grotesque. The closest open-licensed match is **Inter** (SIL OFL): Inter Bold for titles and channel numbers, Inter SemiBold for labels, Inter Regular for secondary text. Inter has tabular figures, which the guide time header and the clock need.

Suggested TV type scale at 1080p (Compose `sp`, with TV default density):

| Role | Size | Weight |
|---|---|---|
| Channel number badge | 40 | Bold |
| Program title (overlay) | 34 | Bold |
| Screen title / tab label | 26 | SemiBold |
| Guide cell, list row | 24 | Medium |
| Secondary / timestamps | 20 | Regular |
| Caption ("Up Next", "Synced 2 minutes ago") | 18 | Regular |

Nothing below 18 sp on a television.

### Shape and spacing

- Corner radius: 12 dp for cells and rows, 16 dp for cards, 20 dp for the logo pill and number badge.
- Base grid 8 dp. Guide cell gutter 6 dp. Card padding 20 dp. Safe area inset 48 dp on all sides (TV overscan).
- Guide: 30 minutes ≈ 220 dp at 1080p, so a 3-hour window fills the width with the channel column.

### Icons

Rounded, 2 px stroke, line icons (guide, channel categories, settings nav). **Lucide** (ISC license) matches the style and has every glyph the mockups use: `tv`, `calendar`, `heart`, `settings`, `home`, `monitor`, `theater`/`drama`, `rocket`, `film`, `smile`, `snowflake`, `play`, `info`.

Channel icons in the mockups are category glyphs (drama masks for Comedy, TV set for Sitcoms, planet for Sci-Fi, film reel for Movies, smiley for Kids, snowflake for Holiday). A built-in glyph picker from the Lucide set is enough for the MVP; custom artwork later.

## 3. How the mockups map to the proposal

| Mockup element | Proposal section | Fit |
|---|---|---|
| Overlay layout and contents | §10.1, §1.3 | Matches exactly, including Up Next. The `17:03 / 30:00` readout is position within the padded slot (`end - start`), not file duration. |
| Guide grid, now-line, sticky channel column | §11.2 | Matches. Window should start at the previous half-hour boundary rather than `now - 30 min`, so the header reads `7:00 PM` as in the mockup. |
| Top tabs Guide · Channels · Favorites · Settings | §10.6 (Menu → Guide) | **New navigation shell.** Back from playback lands in this tabbed shell, with Guide as the default tab. Adopt. |
| Channels screen with live preview | §11.3 (mini-guide) | **Not in the proposal.** See §4 below. |
| Settings left nav (8 entries) | §3 feature modules | Matches `feature:settings`; "Scheduling" and "Appearance" are new entries. Scheduling = household time zone, default slot rounding, filler style. Appearance = overlay timeout, guide density, clock format. |
| Household pane with coordinator and synced devices | §13, §14 | Matches. "Synced 2 minutes ago" is `HouseholdDevice.lastSeen`; the dot is green when seen within 5 minutes. |
| First-run server picker | §6 | Matches. Each row starts that provider's `AuthFlow`. |
| Clock top-right on every screen | — | Trivial; add to the shell. |

## 4. Decisions still needed

1. **Live preview on the Channels screen.** The mockup shows the focused channel playing on the right. Options:
   - *Static card:* current program's artwork plus the info card, no player involvement. Instant, cheap, works on every Fire stick.
   - *Live preview after dwell:* static card immediately, then tune the real stream into the preview pane after the focus has rested about 1 s. Feels like surfing; costs a tune per dwell and a transcode session per dwell on servers that transcode.
   - *Always live:* tune on every focus change. Not viable on transcode-heavy servers.

   Recommendation: ship the static card in Phase 1, add dwell-based live preview in Phase 3 behind an Appearance toggle. The player module already supports this because the preview is just a second `PlayerEngine` surface.

2. **Focus indication.** The mockup uses a solid blue fill for the focused item. Compose for TV convention is a scale plus border. Solid fill reads well at 10 feet and is what the mockups show; use fill as the primary cue and add a 2 dp white ring at 80 % so focus remains visible on blue-fill elements that are also "selected" (for example the active tab).

3. **Provider brand marks on the first-run screen.** Jellyfin's logo is CC-BY-SA and fine to use. Plex and Emby publish brand guidelines that allow "works with" style usage but restrict modification. Before the first public release, confirm each one's guideline and keep the marks unmodified; fallback is a text-only row with a colored initial.

4. **Logo as a production asset.** Done. `brand/` holds vector masters (mark, mono mark, wordmark outlined from Inter Display Black, horizontal and stacked lockups), Android launcher and banner assets, and web favicons, all generated by `brand/tools/gen.js`. `logo-neon.png` remains the reference concept.
5. **Overlay auto-hide timing.** Not visible in a still. Proposal says about 4 s; suggest 5 s, user-adjustable under Appearance.

## 5. Open-source hygiene for design assets

- Everything in `docs/design/` is covered by the repository's MIT license except third-party brand marks, which are used under their owners' guidelines and are not relicensed.
- Mockups were produced by the maintainer with AI image tooling. Contributors should treat them as direction, not as pixel specs; the token table above is the spec.
- Fonts: Inter (OFL) bundled in the app. Icons: Lucide (ISC) as a dependency, not vendored.
