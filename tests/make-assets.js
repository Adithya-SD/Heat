const fs = require('fs'), { chromium } = require('playwright-core');
const OUT = require('path').join(__dirname, '..', 'public', 'icons') + '/';
const defs = `<defs>
<radialGradient id="a" cx="22%" cy="18%" r="70%"><stop offset="0" stop-color="#4f7bff"/><stop offset="1" stop-color="#4f7bff" stop-opacity="0"/></radialGradient>
<radialGradient id="b" cx="92%" cy="24%" r="60%"><stop offset="0" stop-color="#c14bff"/><stop offset="1" stop-color="#c14bff" stop-opacity="0"/></radialGradient>
<radialGradient id="c" cx="55%" cy="102%" r="78%"><stop offset="0" stop-color="#ff5a7a"/><stop offset="1" stop-color="#ff5a7a" stop-opacity="0"/></radialGradient>
<linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".98"/><stop offset=".55" stop-color="#fff" stop-opacity=".45"/><stop offset="1" stop-color="#ffd7c8" stop-opacity=".85"/></linearGradient>
<clipPath id="r"><rect width="512" height="512" rx="116"/></clipPath></defs>`;
const bg = '<rect width="512" height="512" fill="#0a0a16"/><rect width="512" height="512" fill="url(#a)"/><rect width="512" height="512" fill="url(#b)"/><rect width="512" height="512" fill="url(#c)"/>';
const mark = s => `<g transform="translate(256 256) scale(${s}) translate(-256 -256)"><circle cx="256" cy="256" r="124" fill="#fff" fill-opacity=".1" stroke="url(#g)" stroke-width="15"/><circle cx="256" cy="256" r="48" fill="#fff"/><path d="M168 212a104 104 0 0 1 76-66" stroke="#fff" stroke-width="11" fill="none" stroke-linecap="round" opacity=".9"/></g>`;
const svg = (round, s) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">${defs}${round ? '<g clip-path="url(#r)">' + bg + '</g>' : bg}${mark(s)}</svg>`;
fs.writeFileSync(OUT + 'icon.svg', svg(true, 1));
(async () => {
  const b = await chromium.launch({ executablePath: process.env.CHROMIUM || '/opt/pw-browsers/chromium', args: ['--no-sandbox'] });
  const shot = async (html, w, h, file, transparent) => {
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    await p.setContent(html); await p.screenshot({ path: OUT + file, omitBackground: !!transparent }); await p.close();
  };
  const sq = (s, name, round, k) => shot(`<body style="margin:0;background:transparent"><div style="width:${s}px;height:${s}px">${svg(round, k).replace('<svg ', `<svg width="${s}" height="${s}" `)}</div>`, s, s, name, round);
  await sq(192, 'icon-192.png', true, 1); await sq(512, 'icon-512.png', true, 1);
  await sq(180, 'apple-touch-icon.png', false, .9); await sq(512, 'maskable-512.png', false, .74);
  await shot(`<body style="margin:0;width:1200px;height:630px;background:#0a0a16;color:#fff;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;position:relative;overflow:hidden">
<div style="position:absolute;inset:-10%;background:radial-gradient(55% 50% at 14% 20%,#2f63ff,transparent 70%),radial-gradient(50% 45% at 92% 26%,#b23cff,transparent 70%),radial-gradient(60% 55% at 60% 105%,#ff5a7a,transparent 70%),radial-gradient(40% 40% at 4% 96%,#00c7be,transparent 70%)"></div>
<div style="position:absolute;left:84px;top:150px;width:330px;height:330px">${svg(true, 1).replace('<svg ', '<svg width="330" height="330" ')}</div>
<div style="position:absolute;left:470px;top:150px;width:700px"><div style="font-size:148px;font-weight:700;letter-spacing:-6px;line-height:1">Heat</div>
<div style="font-size:42px;line-height:1.25;margin-top:18px;opacity:.92;text-shadow:0 2px 12px rgba(0,0,0,.4)">Private video calls and motion cameras, device to device.</div>
<div style="display:flex;gap:12px;margin-top:34px;font-size:23px;font-weight:600;white-space:nowrap">
${['End-to-end lock', 'No accounts', 'No video server'].map(t => `<span style="padding:11px 20px;border-radius:40px;background:rgba(255,255,255,.16);box-shadow:inset 0 0 0 1.5px rgba(255,255,255,.5)">${t}</span>`).join('')}</div></div>`, 1200, 630, 'og.png');
  await b.close();
})();
