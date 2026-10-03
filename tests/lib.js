// shared helpers for the browser tests: a static server for public/ that applies the headers from vercel.json, and the Chromium launcher
const fs = require('fs'), http = require('http'), path = require('path');
const { chromium } = require('playwright-core');
const ROOT = path.join(__dirname, '..', 'public');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.txt': 'text/plain' };
/* the response headers Vercel would add. CSP_STRICT=1 swaps 'unsafe-inline' for hashes of the inline blocks (scripts/csp-hash.js) */
function headers() {
  try {
    const v = JSON.parse(fs.readFileSync(process.env.VERCEL_JSON || path.join(ROOT, '..', 'vercel.json'), 'utf8')), h = {};
    (v.headers || []).filter(x => x.source === '/(.*)').forEach(x => x.headers.forEach(y => h[y.key] = y.value));
    if (h['Content-Security-Policy']) {
      if (process.env.CSP_STRICT) h['Content-Security-Policy'] = require('../scripts/csp-hash').strict(h['Content-Security-Policy'], fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8'));
      h['Content-Security-Policy'] = h['Content-Security-Policy'].replace("connect-src 'self'", "connect-src 'self' http://127.0.0.1:9000 ws://127.0.0.1:9000");   // the local test signaling server
    }
    return h;
  } catch { return {}; }
}
/* the encryption module and its worker, cut out of index.html, for the isolated unit tests */
function e2eePage() {
  const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
  const worker = html.match(/<script type="text\/plain" id="e2ee-worker">\n([\s\S]*?)\n<\/script>/)[1];
  const js = html.slice(html.indexOf('/* ===== e2ee ===== */'), html.indexOf('/* ===== app ===== */'));
  return '<!doctype html><title>t</title><script type="text/plain" id="e2ee-worker">' + worker + '</script><script>' + js + '</script><body>';
}
function serve(port, opts = {}) {
  const H = opts.noHeaders ? {} : headers();
  return new Promise(res => {
    const s = http.createServer((q, r) => {
      let p = decodeURIComponent(q.url.split('?')[0]); if (p.endsWith('/')) p += 'index.html';
      if (p === '/__t.html') { r.writeHead(200, { 'content-type': 'text/html' }); return r.end(e2eePage()); }
      let f = path.join(ROOT, p);
      if (!fs.existsSync(f) && fs.existsSync(f + '.html')) f += '.html';
      if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { r.writeHead(404, { 'content-type': 'text/html', ...H }); return r.end(fs.readFileSync(path.join(ROOT, '404.html'))); }
      r.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream', ...H });
      r.end(fs.readFileSync(f));
    }).listen(port, '127.0.0.1', () => res(s));
  });
}
/* CHROMIUM=/path/to/chrome overrides; otherwise a common location, otherwise Playwright's own download (npx playwright install chromium) */
const exe = process.env.CHROMIUM || (fs.existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
const launch = (extra = []) => chromium.launch({
  executablePath: exe,
  args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream=device-count=2', '--no-sandbox',
    '--disable-features=WebRtcHideLocalIpsWithMdns', '--autoplay-policy=no-user-gesture-required',
    '--auto-select-desktop-capture-source=Entire screen', '--enable-usermedia-screen-capturing', ...extra],
});
module.exports = { serve, launch, ROOT };
