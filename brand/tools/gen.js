// Clickarr brand asset generator. Produces SVG masters (font-free, text outlined) and PNG exports.
const fs = require('fs');
const path = require('path');
const opentype = require('opentype.js');
const { Resvg } = require('@resvg/resvg-js');

const OUT = process.argv[2];
const FONT_DIR = process.argv[3];
const black = opentype.loadSync(path.join(FONT_DIR, 'InterDisplay-Black.ttf'));
const semi = opentype.loadSync(path.join(FONT_DIR, 'InterDisplay-SemiBold.ttf'));

const C = {
  bg: '#05070F', cyan: '#00E2FD', blue: '#1284FD', magenta: '#AF2EFC', violet: '#7E4DFD',
  frameLight: '#F4F7FF', frameDark: '#4FA8FF', bezel: '#0B1324', control: '#D6E6FF', white: '#FFFFFF',
};

// ---------- TV mark (512 x 512 viewBox) ----------
function markDefs(id) {
  return `
  <linearGradient id="${id}-screen" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="${C.cyan}"/><stop offset="0.5" stop-color="${C.blue}"/><stop offset="1" stop-color="${C.magenta}"/>
  </linearGradient>
  <linearGradient id="${id}-frame" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="${C.frameLight}"/><stop offset="1" stop-color="${C.frameDark}"/>
  </linearGradient>
  <linearGradient id="${id}-gloss" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.28"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
  </linearGradient>
  <filter id="${id}-glow" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur stdDeviation="14" result="b"/>
    <feColorMatrix in="b" type="matrix" values="0 0 0 0 0.07  0 0 0 0 0.52  0 0 0 0 1  0 0 0 0.55 0" result="c"/>
    <feMerge><feMergeNode in="c"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>`;
}

// Geometry shared by color and mono variants.
function markBody(id, mono) {
  const frame = mono ? 'currentColor' : `url(#${id}-frame)`;
  const screen = mono ? C.bg : `url(#${id}-screen)`;
  const bezel = mono ? C.bg : C.bezel;
  const control = mono ? 'currentColor' : C.control;
  const ant = mono ? 'currentColor' : C.frameLight;
  return `
  <!-- antennae -->
  <g stroke="${ant}" stroke-width="20" stroke-linecap="round" fill="none">
    <line x1="208" y1="150" x2="150" y2="52"/>
    <line x1="304" y1="150" x2="362" y2="52"/>
  </g>
  <circle cx="150" cy="52" r="22" fill="${ant}"/>
  <circle cx="362" cy="52" r="22" fill="${ant}"/>
  <!-- frame -->
  <rect x="48" y="132" width="416" height="316" rx="70" fill="${frame}"/>
  <!-- bezel -->
  <rect x="78" y="162" width="356" height="256" rx="48" fill="${bezel}"/>
  <!-- screen -->
  <rect x="96" y="180" width="240" height="220" rx="36" fill="${screen}"/>
  ${mono ? '' : `<path d="M132 180 h170 a36 36 0 0 1 36 36 v30 c-70 10 -140 40 -206 110 z" fill="url(#${id}-gloss)"/>`}
  <!-- controls -->
  <circle cx="386" cy="218" r="17" fill="${control}"/>
  <circle cx="386" cy="264" r="17" fill="${control}"/>
  <rect x="366" y="306" width="40" height="9" rx="4.5" fill="${control}"/>
  <rect x="366" y="326" width="40" height="9" rx="4.5" fill="${control}"/>
  <rect x="366" y="346" width="40" height="9" rx="4.5" fill="${control}"/>`;
}

function markSvg({ mono = false, glow = false, size = 512 } = {}) {
  const id = 'ck';
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="${size}" height="${size}" ${mono ? 'color="#FFFFFF"' : ''}>
  <defs>${mono ? '' : markDefs(id)}</defs>
  <g ${glow && !mono ? `filter="url(#${id}-glow)"` : ''}>${markBody(id, mono)}
  </g>
</svg>`;
}

// ---------- Wordmark (outlined from Inter Display Black) ----------
function textPath(font, text, x, y, size) {
  const p = font.getPath(text, x, y, size);
  return { d: p.toPathData(3), advance: font.getAdvanceWidth(text, size) };
}

function wordmarkParts(size) {
  // Tighter tracking than default for a logo.
  const click = textPath(black, 'Click', 0, 0, size);
  const kern = -size * 0.015;
  const arr = textPath(black, 'arr', click.advance + kern, 0, size);
  const width = click.advance + kern + arr.advance;
  return { click, arr, width, ascent: size * 0.73, descent: size * 0.02 };
}

function wordmarkSvg({ size = 200, mono = false } = {}) {
  const w = wordmarkParts(size);
  const pad = size * 0.08;
  const W = w.width + pad * 2, H = w.ascent + w.descent + pad * 2;
  const baseline = pad + w.ascent;
  const arrFill = mono ? 'currentColor' : 'url(#wm-grad)';
  const clickFill = mono ? 'currentColor' : C.white;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W.toFixed(1)} ${H.toFixed(1)}" width="${W.toFixed(0)}" height="${H.toFixed(0)}" ${mono ? 'color="#FFFFFF"' : ''}>
  <defs><linearGradient id="wm-grad" x1="0" y1="0" x2="1" y2="0.3">
    <stop offset="0" stop-color="${C.blue}"/><stop offset="1" stop-color="${C.violet}"/></linearGradient></defs>
  <g transform="translate(${pad},${baseline})">
    <path d="${w.click.d}" fill="${clickFill}"/>
    <path d="${w.arr.d}" fill="${arrFill}"/>
  </g>
</svg>`;
}

// ---------- Lockups ----------
function stackedSvg({ bg = true, glow = true } = {}) {
  // 1024 x 1024 canvas, mark on top, wordmark below (matches the reference concept).
  const w = wordmarkParts(230);
  const wmX = (1024 - w.width) / 2, wmBaseline = 880;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <defs>${markDefs('st')}<linearGradient id="st-wm" x1="0" y1="0" x2="1" y2="0.3"><stop offset="0" stop-color="${C.blue}"/><stop offset="1" stop-color="${C.violet}"/></linearGradient></defs>
  ${bg ? `<rect width="1024" height="1024" fill="${C.bg}"/>` : ''}
  <g transform="translate(256,110)" ${glow ? 'filter="url(#st-glow)"' : ''}>${markBody('st', false)}</g>
  <g transform="translate(${wmX.toFixed(1)},${wmBaseline})">
    <path d="${w.click.d}" fill="${C.white}"/>
    <path d="${w.arr.d}" fill="url(#st-wm)"/>
  </g>
</svg>`;
}

function horizontalSvg({ bg = false, height = 200 } = {}) {
  // mark (height h) + gap + wordmark, vertically centred. viewBox height = h.
  const h = height, markS = h, gap = h * 0.12;
  const wSize = h * 0.62;
  const w = wordmarkParts(wSize);
  const W = markS + gap + w.width + h * 0.1;
  const baseline = h / 2 + w.ascent / 2 - w.descent;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W.toFixed(1)} ${h}" width="${W.toFixed(0)}" height="${h}">
  <defs>${markDefs('hz')}<linearGradient id="hz-wm" x1="0" y1="0" x2="1" y2="0.3"><stop offset="0" stop-color="${C.blue}"/><stop offset="1" stop-color="${C.violet}"/></linearGradient></defs>
  ${bg ? `<rect width="${W.toFixed(1)}" height="${h}" fill="${C.bg}"/>` : ''}
  <g transform="translate(0,0) scale(${(markS / 512).toFixed(4)})">${markBody('hz', false)}</g>
  <g transform="translate(${(markS + gap).toFixed(1)},${baseline.toFixed(1)})">
    <path d="${w.click.d}" fill="${C.white}"/>
    <path d="${w.arr.d}" fill="url(#hz-wm)"/>
  </g>
</svg>`;
}

// ---------- Platform assets ----------
function adaptiveForegroundSvg() {
  // 108x108 dp canvas; the visible safe zone is the central 66 dp circle. Keep the mark inside ~62 dp.
  const s = 62 / 512;
  const off = (108 - 62) / 2;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
  <defs>${markDefs('af')}</defs>
  <g transform="translate(${off},${off + 2}) scale(${s.toFixed(5)})">${markBody('af', false)}</g>
</svg>`;
}

function legacyIconSvg(px) {
  // Rounded-square dark tile with the mark, for pre-adaptive launchers and the website.
  const r = Math.round(px * 0.2);
  const s = (px * 0.72) / 512, off = px * 0.14;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${px} ${px}" width="${px}" height="${px}">
  <defs>${markDefs('li')}</defs>
  <rect width="${px}" height="${px}" rx="${r}" fill="${C.bg}"/>
  <g transform="translate(${off},${off + px * 0.01}) scale(${s.toFixed(5)})">${markBody('li', false)}</g>
</svg>`;
}

function bannerSvg(W = 320, H = 180) {
  // Android TV / Fire TV banner. Horizontal lockup centred on the dark field.
  const lockH = H * 0.5;
  const w = wordmarkParts(lockH * 0.62);
  const lockW = lockH + lockH * 0.12 + w.width;
  const x = (W - lockW) / 2, y = (H - lockH) / 2;
  const baseline = y + lockH / 2 + w.ascent / 2 - w.descent;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <defs>${markDefs('bn')}<linearGradient id="bn-wm" x1="0" y1="0" x2="1" y2="0.3"><stop offset="0" stop-color="${C.blue}"/><stop offset="1" stop-color="${C.violet}"/></linearGradient>
  <radialGradient id="bn-bg" cx="0.5" cy="0.5" r="0.7"><stop offset="0" stop-color="#0E1B3A"/><stop offset="1" stop-color="${C.bg}"/></radialGradient></defs>
  <rect width="${W}" height="${H}" fill="url(#bn-bg)"/>
  <g transform="translate(${x.toFixed(1)},${y.toFixed(1)}) scale(${(lockH / 512).toFixed(5)})">${markBody('bn', false)}</g>
  <g transform="translate(${(x + lockH + lockH * 0.12).toFixed(1)},${baseline.toFixed(1)})">
    <path d="${w.click.d}" fill="${C.white}"/>
    <path d="${w.arr.d}" fill="url(#bn-wm)"/>
  </g>
</svg>`;
}

function ogImageSvg() {
  const W = 1200, H = 630;
  const w = wordmarkParts(150);
  const tag = textPath(semi, 'Turn your media library into TV', 0, 0, 44);
  const markS = 300;
  const lockW = markS + 36 + w.width;
  const x = (W - lockW) / 2, y = 150;
  const baseline = y + markS / 2 + w.ascent / 2;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <defs>${markDefs('og')}<linearGradient id="og-wm" x1="0" y1="0" x2="1" y2="0.3"><stop offset="0" stop-color="${C.blue}"/><stop offset="1" stop-color="${C.violet}"/></linearGradient>
  <radialGradient id="og-bg" cx="0.5" cy="0.4" r="0.8"><stop offset="0" stop-color="#101E42"/><stop offset="1" stop-color="${C.bg}"/></radialGradient></defs>
  <rect width="${W}" height="${H}" fill="url(#og-bg)"/>
  <g transform="translate(${x.toFixed(1)},${y}) scale(${(markS / 512).toFixed(5)})" filter="url(#og-glow)">${markBody('og', false)}</g>
  <g transform="translate(${(x + markS + 36).toFixed(1)},${baseline.toFixed(1)})">
    <path d="${w.click.d}" fill="${C.white}"/>
    <path d="${w.arr.d}" fill="url(#og-wm)"/>
  </g>
  <g transform="translate(${((W - tag.advance) / 2).toFixed(1)},520)"><path d="${tag.d}" fill="#A9B4C8"/></g>
</svg>`;
}

// ---------- IO ----------
function write(rel, content) {
  const p = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, content);
  return p;
}
function png(rel, svg, width) {
  const r = new Resvg(svg, { fitTo: { mode: 'width', value: width }, background: 'rgba(0,0,0,0)' });
  const buf = r.render().asPng();
  write(rel, buf);
}

// SVG masters
write('svg/logo-mark.svg', markSvg());
write('svg/logo-mark-glow.svg', markSvg({ glow: true }));
write('svg/logo-mark-mono.svg', markSvg({ mono: true }));
write('svg/wordmark.svg', wordmarkSvg());
write('svg/wordmark-mono.svg', wordmarkSvg({ mono: true }));
write('svg/logo-horizontal.svg', horizontalSvg());
write('svg/logo-horizontal-on-dark.svg', horizontalSvg({ bg: true }));
write('svg/logo-stacked.svg', stackedSvg({ bg: false, glow: true }));
write('svg/logo-stacked-on-dark.svg', stackedSvg({ bg: true, glow: true }));

// General PNG exports
png('png/logo-mark-512.png', markSvg(), 512);
png('png/logo-mark-1024.png', markSvg({ glow: true }), 1024);
png('png/logo-mark-mono-512.png', markSvg({ mono: true }), 512);
png('png/logo-stacked-1024.png', stackedSvg({ bg: true }), 1024);
png('png/logo-stacked-transparent-1024.png', stackedSvg({ bg: false }), 1024);
png('png/logo-horizontal-1600.png', horizontalSvg({ bg: false, height: 200 }), 1600);
png('png/logo-horizontal-on-dark-1600.png', horizontalSvg({ bg: true, height: 200 }), 1600);

// Android: adaptive icon foreground (vector source + PNG fallback), legacy mipmaps, TV banner
write('android/ic_launcher_foreground.svg', adaptiveForegroundSvg());
png('android/ic_launcher_foreground-432.png', adaptiveForegroundSvg(), 432);
write('android/ic_launcher_background.txt', `${C.bg}\n`);
// Fire TV draws a sideloaded app's android:icon (not its banner) inside the 16:9 rounded tile on the home
// screen, so the launcher icon itself is the rounded lockup. Larger than the nominal density sizes on purpose:
// the tile is drawn at several hundred pixels wide. Android TV's launcher still uses the banner.
function tileIconSvg(W) {
  const H = Math.round(W * 9 / 16);
  return bannerSvg(W, H).replace(`<rect width="${W}" height="${H}" fill="url(#bn-bg)"/>`,
    `<rect width="${W}" height="${H}" rx="${Math.round(H * 0.09)}" fill="url(#bn-bg)"/>`);
}
const densities = { mdpi: 160, hdpi: 240, xhdpi: 320, xxhdpi: 480, xxxhdpi: 640 };
for (const [d, px] of Object.entries(densities)) png(`android/drawable-${d}/ic_launcher.png`, tileIconSvg(px), px); // drawable, not mipmap: Fire TV's launcher misses mipmap icons
png('android/ic_launcher-512.png', legacyIconSvg(512), 512);               // Play/Amazon listing icon
write('android/banner.svg', bannerSvg(320, 180));
png('android/drawable-xhdpi/banner.png', bannerSvg(320, 180), 320);        // Android TV banner spec: 320x180 @ xhdpi
png('android/banner-1280x720.png', bannerSvg(1280, 720), 1280);           // Amazon Appstore / large banner
png('android/banner-1920x1080.png', bannerSvg(1920, 1080), 1920);

// Web: favicons and social image
for (const px of [16, 32, 48, 64, 180, 192, 256, 512]) png(`web/icon-${px}.png`, legacyIconSvg(px), px);
png('web/apple-touch-icon.png', legacyIconSvg(180), 180);
write('web/favicon.svg', legacyIconSvg(64));
write('web/og-image.svg', ogImageSvg());
png('web/og-image.png', ogImageSvg(), 1200);

console.log('done', OUT);
