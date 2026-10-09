# Clickarr brand assets

Everything here is generated from `tools/gen.js`. Do not hand-edit the exports; change the geometry or colors in the script and rebuild.

```bash
cd brand/tools
npm install
npm run fetch-font   # downloads Inter 4.1 (SIL OFL) into .fonts/
npm run build        # rewrites ../svg ../png ../android ../web
```

The wordmark is set in Inter Display Black and outlined to paths, so no SVG here depends on a font being installed.

## Files

| Folder | Contents |
|---|---|
| `svg/` | Masters. `logo-mark` (TV), `logo-mark-mono` (single color, uses `currentColor`), `wordmark`, `logo-horizontal`, `logo-stacked`, each with on-dark variants where useful. |
| `png/` | General-purpose exports of the masters. |
| `android/` | `ic_launcher_foreground.svg` (adaptive icon foreground, 108 dp canvas, mark inside the 66 dp safe zone), `ic_launcher_background.txt` (the background color), legacy `mipmap-*/ic_launcher.png`, `ic_launcher-512.png` for store listings, and the TV banner at 320×180 (the Android TV spec size) plus 1280×720 and 1920×1080. |
| `web/` | `favicon.ico` (16/32/48), `favicon.svg`, PNG icons at common sizes, `apple-touch-icon.png`, and `og-image.png` (1200×630) for link previews. |

## Colors

| Role | Hex |
|---|---|
| Background | `#05070F` |
| Screen gradient | `#00E2FD` → `#1284FD` → `#AF2EFC` |
| Wordmark "arr" gradient | `#1284FD` → `#7E4DFD` |
| Frame | `#F4F7FF` → `#4FA8FF` |
| Bezel | `#0B1324` |

## Usage

The mark and wordmark are part of the Clickarr project and are MIT licensed like the rest of the repository. Please do not use them to imply endorsement of an unrelated product. The design concept originated as `docs/design/mockups/logo-neon.png`.

Clear space: keep at least the height of one antenna ball free around the mark. Minimum sizes: mark 24 px, horizontal lockup 120 px wide. On light backgrounds use `logo-mark-mono.svg` with a dark `color`.
