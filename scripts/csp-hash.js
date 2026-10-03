#!/usr/bin/env node
/* Optional: tighten the Content-Security-Policy by replacing 'unsafe-inline' with hashes of the page's inline <script> and <style> blocks.
   The app is one HTML file on purpose, so those blocks are inline; hashes let the browser run exactly that code and nothing injected.
   usage:  node scripts/csp-hash.js            print the strict policy
           node scripts/csp-hash.js --apply    write it into vercel.json
           node scripts/csp-hash.js --loose    put 'unsafe-inline' back
   Re-run with --apply after EVERY edit to the inline script or style, otherwise the browser will refuse to run the edited code. */
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const root = path.join(__dirname, '..');
const sha = t => "'sha256-" + crypto.createHash('sha256').update(t, 'utf8').digest('base64') + "'";
const blocks = (html, tag) => [...html.matchAll(new RegExp('<' + tag + '(?![^>]*\\bsrc=)(?![^>]*type="text/plain")[^>]*>([\\s\\S]*?)</' + tag + '>', 'g'))].map(m => m[1]);
function strict(csp, html) {
  const set = (c, dir, vals) => c.replace(new RegExp(dir + ' [^;]*'), dir + ' ' + vals.join(' '));
  let out = set(csp, 'script-src', ["'self'", ...blocks(html, 'script').map(sha)]);
  return set(out, 'style-src', ["'self'", ...blocks(html, 'style').map(sha)]);
}
const loose = csp => csp.replace(/script-src [^;]*/, "script-src 'self' 'unsafe-inline'").replace(/style-src [^;]*/, "style-src 'self' 'unsafe-inline'");
module.exports = { strict, loose, blocks, sha };
if (require.main === module) {
  const vf = path.join(root, 'vercel.json'), v = JSON.parse(fs.readFileSync(vf, 'utf8')), html = fs.readFileSync(path.join(root, 'public', 'index.html'), 'utf8');
  const h = v.headers.find(x => x.source === '/(.*)').headers.find(x => x.key === 'Content-Security-Policy');
  const next = process.argv.includes('--loose') ? loose(h.value) : strict(h.value, html);
  if (process.argv.includes('--apply') || process.argv.includes('--loose')) { h.value = next; fs.writeFileSync(vf, JSON.stringify(v, null, 2) + '\n'); console.log('vercel.json updated'); }
  console.log(next);
}
