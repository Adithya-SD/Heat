/* Optional: hands the page fresh TURN credentials from a provider you pay for or run, so calls across strict
   networks do not depend on the shared public relays. Set ONE of these in the Vercel project's environment:
     CLOUDFLARE_TURN_KEY_ID + CLOUDFLARE_TURN_API_TOKEN     (Cloudflare Realtime TURN)
     METERED_APP + METERED_API_KEY                           (Metered TURN: <METERED_APP>.metered.live)
   With neither set this answers 204 and the page keeps its built-in server list. Credentials are short-lived. */
module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.statusCode = 405; return res.end(); }
  const env = process.env;
  const none = () => { res.statusCode = 204; res.end(); };
  const timeout = ms => { const c = new AbortController(); setTimeout(() => c.abort(), ms); return c.signal; };
  try {
    let list = null;
    if (env.CLOUDFLARE_TURN_KEY_ID && env.CLOUDFLARE_TURN_API_TOKEN) {
      const r = await fetch('https://rtc.live.cloudflare.com/v1/turn/keys/' + encodeURIComponent(env.CLOUDFLARE_TURN_KEY_ID) + '/credentials/generate-ice-servers', {
        method: 'POST', signal: timeout(4000),
        headers: { Authorization: 'Bearer ' + env.CLOUDFLARE_TURN_API_TOKEN, 'Content-Type': 'application/json' },
        body: JSON.stringify({ ttl: 86400 })
      });
      if (r.ok) list = [].concat((await r.json()).iceServers || []);
    } else if (env.METERED_APP && env.METERED_API_KEY) {
      const r = await fetch('https://' + encodeURIComponent(env.METERED_APP) + '.metered.live/api/v1/turn/credentials?apiKey=' + encodeURIComponent(env.METERED_API_KEY), { signal: timeout(4000) });
      if (r.ok) list = [].concat(await r.json());
    }
    /* only relay entries (they carry credentials); the page keeps its own STUN list */
    const iceServers = (list || []).filter(s => s && s.urls && s.credential && s.username);
    if (!iceServers.length) return none();
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ iceServers }));
  } catch { none(); }
};
