# clickarr.net

Static site, no build step. `index.html` plus `assets/`. Deployed to GitHub Pages by `.github/workflows/pages.yml` on every push to `main` that touches `site/`.

- `CNAME` holds the custom domain. Do not remove it or Pages drops the domain.
- Images are copies of `brand/` and `docs/design/mockups/`; refresh them from there when those change.
- Styling follows `docs/design/design-language.md` (same tokens, Inter from Google Fonts for the web).
