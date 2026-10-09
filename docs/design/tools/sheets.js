// Renders docs/design/palette.png and docs/design/type-specimen.png from the design language tokens.
// Usage: node sheets.js <outDir> <interTtfDir>   (see brand/tools for how to fetch Inter)
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const OUT = process.argv[2];
const FONTS = process.argv[3];

const base = [
  ['navy-950', '#05070F', 'bg.base'], ['navy-900', '#0A1220', 'bg.panel'], ['navy-800', '#131A36', 'bg.surface'],
  ['navy-700', '#1A2A47', 'bg.cell'], ['navy-750', '#0F2038', 'bg.cellBorder'], ['navy-600', '#1E2B45', 'bg.elevated'],
  ['blue-500', '#1479FD', 'accent.primary'], ['blue-900', '#063677', 'accent.primaryDeep'], ['sky-400', '#00ACFF', 'accent.glow'],
  ['cyan-300', '#00E2FD', 'brand'], ['violet-500', '#7E4DFD', 'brand'], ['magenta-500', '#AF2EFC', 'brand'],
  ['green-400', '#0CE568', 'status.ok'], ['amber-400', '#FFB020', 'status.warn'], ['red-400', '#FF4D5E', 'status.error'],
  ['white-100', '#F2F5FA', 'text.primary'], ['white-60', '#A9B4C8', 'text.secondary'], ['white-35', '#6B7890', 'text.muted'],
];

function paletteSvg() {
  const cols = 6, cw = 300, ch = 200, pad = 48, gap = 20;
  const W = pad * 2 + cols * cw + (cols - 1) * gap;
  const rows = Math.ceil(base.length / cols);
  const H = pad * 2 + 90 + rows * (ch + gap);
  let s = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="Inter">
  <rect width="${W}" height="${H}" fill="#05070F"/>
  <text x="${pad}" y="${pad + 34}" font-size="34" font-weight="700" fill="#F2F5FA">Clickarr palette</text>
  <text x="${pad}" y="${pad + 66}" font-size="20" fill="#A9B4C8">Base colors with their semantic role. Components reference roles, never base names.</text>`;
  base.forEach(([name, hex, role], i) => {
    const x = pad + (i % cols) * (cw + gap), y = pad + 90 + Math.floor(i / cols) * (ch + gap);
    const light = ['white-100', 'white-60', 'cyan-300', 'green-400', 'amber-400', 'sky-400'].includes(name);
    const fg = light ? '#05070F' : '#F2F5FA', fg2 = light ? '#1E2B45' : '#A9B4C8';
    s += `
  <rect x="${x}" y="${y}" width="${cw}" height="${ch}" rx="16" fill="${hex}" stroke="#1E2B45" stroke-width="1"/>
  <text x="${x + 20}" y="${y + 40}" font-size="22" font-weight="600" fill="${fg}">${name}</text>
  <text x="${x + 20}" y="${y + 70}" font-size="20" fill="${fg2}">${hex}</text>
  <text x="${x + 20}" y="${y + ch - 24}" font-size="18" fill="${fg2}">${role}</text>`;
  });
  return s + '\n</svg>';
}

const scale = [
  ['display.channel', 40, 700, -0.5, '10'],
  ['headline.program', 34, 700, -0.25, 'The Office'],
  ['title.screen', 26, 600, 0, 'Household'],
  ['title.row', 24, 500, 0, 'Parks and Recreation'],
  ['body', 24, 400, 0, 'Choose your server and sign in to get started.'],
  ['body.secondary', 20, 400, 0, '7:00 PM – 7:30 PM'],
  ['caption', 18, 400, 0.1, 'Synced 2 minutes ago'],
  ['label.allcaps', 18, 600, 1.0, 'SITCOMS'],
];

function typeSvg() {
  const W = 1400, pad = 48, rowH = 96;
  const H = pad * 2 + 100 + scale.length * rowH;
  let s = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="Inter">
  <rect width="${W}" height="${H}" fill="#05070F"/>
  <text x="${pad}" y="${pad + 34}" font-size="34" font-weight="700" fill="#F2F5FA">Clickarr type scale</text>
  <text x="${pad}" y="${pad + 66}" font-size="20" fill="#A9B4C8">Inter, 1080p canvas, sp = px. Nothing below 18 sp. Tabular figures for times and channel numbers.</text>`;
  scale.forEach(([name, size, weight, tracking, sample], i) => {
    const y = pad + 100 + i * rowH;
    s += `
  <line x1="${pad}" y1="${y + rowH - 14}" x2="${W - pad}" y2="${y + rowH - 14}" stroke="#1E2B45"/>
  <text x="${pad}" y="${y + 50}" font-size="18" fill="#6B7890">${name}</text>
  <text x="${pad + 260}" y="${y + 50}" font-size="18" fill="#6B7890">${size} / ${weight} / ${tracking}</text>
  <text x="${pad + 500}" y="${y + 58}" font-size="${size}" font-weight="${weight}" letter-spacing="${tracking}" fill="#F2F5FA" style="font-variant-numeric: tabular-nums">${sample}</text>`;
  });
  return s + '\n</svg>';
}

function render(name, svg) {
  const r = new Resvg(svg, {
    font: { fontFiles: fs.readdirSync(FONTS).filter(f => /^Inter-(Regular|Medium|SemiBold|Bold)\.ttf$/.test(f)).map(f => path.join(FONTS, f)), loadSystemFonts: false, defaultFontFamily: 'Inter' },
  });
  fs.writeFileSync(path.join(OUT, name + '.svg'), svg);
  fs.writeFileSync(path.join(OUT, name + '.png'), r.render().asPng());
  console.log('wrote', name);
}

render('palette', paletteSvg());
render('type-specimen', typeSvg());
