#!/usr/bin/env node
/* Stamp your public address into the page so link previews and search engines use it.
   usage: node scripts/set-origin.js https://heat.example.com
   Sets canonical, og:url and absolute og:image / twitter:image in public/index.html, and writes sitemap.xml + the robots.txt Sitemap line. */
const fs = require('fs'), path = require('path');
const origin = (process.argv[2] || '').replace(/\/+$/, '');
if (!/^https:\/\/[^/\s]+$/.test(origin)) { console.error('usage: node scripts/set-origin.js https://your-domain'); process.exit(1); }
const pub = path.join(__dirname, '..', 'public'), file = path.join(pub, 'index.html');
let h = fs.readFileSync(file, 'utf8');
h = h.replace(/<link rel="canonical"[^>]*>\n?/g, '').replace(/<meta property="og:url"[^>]*>/g, '');
h = h.replace(/content="(?:https?:\/\/[^"]*)?icons\/og\.png"/g, `content="${origin}/icons/og.png"`);
h = h.replace('<link rel="manifest"', `<link rel="canonical" href="${origin}/">\n<meta property="og:url" content="${origin}/">\n<link rel="manifest"`);
fs.writeFileSync(file, h);
fs.writeFileSync(path.join(pub, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${['', 'privacy', 'terms'].map(p => `  <url><loc>${origin}/${p}</loc></url>`).join('\n')}\n</urlset>\n`);
const rb = path.join(pub, 'robots.txt');
fs.writeFileSync(rb, fs.readFileSync(rb, 'utf8').replace(/^Sitemap:.*\n?/m, '').trimEnd() + `\nSitemap: ${origin}/sitemap.xml\n`);
console.log('Stamped', origin);
