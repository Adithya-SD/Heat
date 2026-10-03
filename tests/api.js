// unit test of api/ice.js with mocked provider responses
const handler = require('../api/ice.js');
let pass = 0, fail = 0; const ok = (n, c, x = '') => { c ? pass++ : fail++; console.log(c ? 'PASS' : 'FAIL', n, x); };
const run = async (env, method = 'GET', fetchImpl) => {
  const saved = { ...process.env }; ['CLOUDFLARE_TURN_KEY_ID', 'CLOUDFLARE_TURN_API_TOKEN', 'METERED_APP', 'METERED_API_KEY'].forEach(k => delete process.env[k]); Object.assign(process.env, env);
  const calls = []; global.fetch = async (u, o) => { calls.push({ u, o }); return fetchImpl(u, o); };
  const res = { headers: {}, statusCode: 0, body: '', setHeader(k, v) { this.headers[k] = v; }, end(b) { this.body = b || ''; this.done = 1; } };
  await handler({ method }, res); Object.assign(process.env, saved); return { res, calls };
};
const json = (o, s = 200) => ({ ok: s < 400, status: s, json: async () => o });
(async () => {
  let r = await run({}, 'GET', () => { throw new Error('should not be called'); });
  ok('no provider configured -> 204, no outbound call', r.res.statusCode === 204 && r.calls.length === 0 && r.res.headers['Cache-Control'] === 'no-store');
  r = await run({ CLOUDFLARE_TURN_KEY_ID: 'k1', CLOUDFLARE_TURN_API_TOKEN: 'tok' }, 'GET', () => json({ iceServers: [{ urls: ['stun:stun.cloudflare.com:3478'] }, { urls: ['turn:turn.cloudflare.com:3478?transport=udp'], username: 'u', credential: 'c' }] }));
  const j = r.res.statusCode === 200 && JSON.parse(r.res.body);
  ok('Cloudflare: returns only relay entries with credentials', j && j.iceServers.length === 1 && j.iceServers[0].credential === 'c', r.res.body);
  ok('Cloudflare: correct URL, bearer token, ttl', /rtc\.live\.cloudflare\.com\/v1\/turn\/keys\/k1\/credentials\/generate-ice-servers$/.test(r.calls[0].u) && r.calls[0].o.headers.Authorization === 'Bearer tok' && JSON.parse(r.calls[0].o.body).ttl === 86400 && r.calls[0].o.method === 'POST');
  ok('responds as JSON, never cached', r.res.headers['Content-Type'] === 'application/json' && r.res.headers['Cache-Control'] === 'no-store');
  r = await run({ METERED_APP: 'myapp', METERED_API_KEY: 'key&x' }, 'GET', () => json([{ urls: 'stun:stun.relay.metered.ca:80' }, { urls: 'turn:global.relay.metered.ca:80', username: 'a', credential: 'b' }]));
  ok('Metered: array shape handled, key is URL-encoded', r.res.statusCode === 200 && JSON.parse(r.res.body).iceServers.length === 1 && /^https:\/\/myapp\.metered\.live\/api\/v1\/turn\/credentials\?apiKey=key%26x$/.test(r.calls[0].u), r.calls[0].u);
  r = await run({ METERED_APP: 'a', METERED_API_KEY: 'b' }, 'GET', () => json({}, 500));
  ok('provider error -> 204 (page falls back to its built-in list)', r.res.statusCode === 204);
  r = await run({ CLOUDFLARE_TURN_KEY_ID: 'k', CLOUDFLARE_TURN_API_TOKEN: 't' }, 'GET', () => { throw new Error('network'); });
  ok('network failure -> 204, no crash', r.res.statusCode === 204);
  r = await run({ CLOUDFLARE_TURN_KEY_ID: 'k', CLOUDFLARE_TURN_API_TOKEN: 't' }, 'GET', () => json({ iceServers: [{ urls: ['stun:x'] }] }));
  ok('only STUN returned -> 204 (nothing to add)', r.res.statusCode === 204);
  r = await run({ CLOUDFLARE_TURN_KEY_ID: 'k', CLOUDFLARE_TURN_API_TOKEN: 't' }, 'POST', () => json({}));
  ok('POST -> 405', r.res.statusCode === 405 && r.calls.length === 0);
  console.log(`\nSCENARIO api: ${pass}/${pass + fail} checks passed -> ${fail ? 'FAIL' : 'PASS'}`); process.exit(fail ? 1 : 0);
})();
