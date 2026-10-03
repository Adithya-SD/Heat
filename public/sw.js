/* Heat service worker: makes the app installable and lets the page itself load offline (calls still need a network).
   Network first, so a new deploy is picked up on the next load; the cache is only the fallback. Bump V with BUILD. */
const V = 'heat-2026-10-03.1';
const SHELL = ['./', 'index.html', 'manifest.webmanifest', 'vendor/peerjs.min.js', 'vendor/qrcode.js', 'icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png'];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(V).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== V).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const r = e.request, u = new URL(r.url);
  if (r.method !== 'GET' || u.origin !== location.origin || u.pathname.startsWith('/api/')) return;
  e.respondWith((async () => {
    try {
      const net = await Promise.race([fetch(r), new Promise((_, no) => setTimeout(() => no(new Error('slow')), 4000))]);
      if (net && net.ok && net.type === 'basic') { const c = await caches.open(V); c.put(r, net.clone()); }
      return net;
    } catch {
      const hit = await caches.match(r, { ignoreSearch: true });
      return hit || (r.mode === 'navigate' ? (await caches.match('index.html')) || Response.error() : Response.error());
    }
  })());
});
