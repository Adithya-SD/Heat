# Heat: private video calls and motion cameras, device to device

One standalone page, [`public/index.html`](public/index.html). No accounts, no video server, no build step. Media goes straight between the two devices over WebRTC; a small signaling relay only introduces them. An optional **end-to-end lock** seals chat, alerts and every audio/video frame with a key that never leaves the invite link.

## What it does

| | |
| --- | --- |
| **Video call** | 1:1 two-way video and audio. Mute, camera on/off, front/back camera, screen share (desktop), chat, reactions, call timer, local recording (the other person is told), picture-in-picture, swap big/small picture. |
| **Motion camera** | One device watches for movement inside a box you can drag and resize. Any number of viewers get live video, motion alerts with a snapshot (tap one to save it), sound/vibration/notifications, remote sensitivity, **arm in 10 seconds** so you can walk away, and the camera phone's **battery level**. |
| **Privacy** | **End-to-end lock** (on by default), **approve each person** who asks to join a call, display names, a **safety code** to compare out loud, a connection panel that says whether the path is direct or relayed. |
| **Viewing** | Follows the sender's phone rotation (turns the picture on a phone to fill the screen, never crops it), manual-only zoom (draw a box, pinch, wheel, double-tap), full screen, keyboard shortcuts (`?` lists them). |
| **Settings** | Name, camera, microphone and speaker pickers, video quality (Auto / High 1080p / Data saver 360p), noise suppression, alert sounds, glass level. |
| **Everywhere** | Layouts for a watch-sized screen up to a 4K monitor, landscape phones included. Installable as an app (PWA); the shell loads offline. |

## Security model, plainly

- **Always on:** WebRTC encrypts media and data between the two devices (DTLS-SRTP). The signaling server never sees video, audio or chat.
- **With the lock (default):** a random 12-character key is made when you start a room. It rides in the invite link's `#fragment`/QR code, which browsers never send to any server. From it Heat derives AES-GCM keys (PBKDF2 → HKDF). Chat, alerts and control messages are sealed; every audio and video frame is sealed in the sender's browser (insertable streams, [`RTCRtpScriptTransform`](https://developer.mozilla.org/docs/Web/API/RTCRtpScriptTransform)) and opened in the receiver's. A hostile signaling server or relay can neither read nor swap anything, and a guest with the wrong key never gets media. Video is forced to VP8 in locked rooms because the frame header the packetizer needs is known for it.
- **Without the lock:** protected in transit, but a hostile signaling server could in theory sit in the middle. Compare the **safety code** (hash of both devices' certificate fingerprints) out loud on both screens.
- **What it does not do:** hide that two IP addresses are talking from the STUN/relay servers; protect a room from someone you gave the key to; stop a screen recording on the other device. Locked rooms need a browser with `RTCRtpScriptTransform` (Chrome/Edge 110+, Safari 15.4+, Firefox 117+); others get a clear message instead of a silent downgrade.
- **Hardening in the repo:** no third-party scripts, styles or fonts (PeerJS and the QR library are vendored in `public/vendor/`); strict CSP and Permissions-Policy in [`vercel.json`](vercel.json); no cookies, analytics or trackers; the room key is kept only in memory (and in `sessionStorage` for "Reconnect", tab-only).

## Using it

1. Open the deployed **HTTPS** URL on both devices (camera access needs HTTPS, except on `localhost`).
2. Pick **Video call** or **Motion camera**, leave the lock on, tap **Start…**.
3. Share the QR code, the invite link, or the code (+ key). Joining by link or QR needs nothing else; typing the code by hand asks for the key.
4. In a call, the host sees "Gina wants to join" and taps Admit or Decline (switch off in Settings).

## If it won't connect

- Tap **Check connection** (home footer, or on the stuck screen). It tests the signaling server and every STUN/relay server from *that* device and says what is wrong in plain words. **Copy report** gives you something to paste. Run it on both devices.
- *Contacting the host…* means looking for the other device; *Host not found* means nobody is hosting that code; *Waiting for the host to let you in…* means the host has to tap Admit.
- Turn off VPNs / ad-blockers that block WebRTC (Opera's built-in VPN included). iOS: Safari. Android: Chrome.
- The footer shows a **build** id; both devices should show the same one.

## Deploying (Vercel)

`public/` is served as-is, no build. Only the **production branch** (`main`) updates your real URL; other branches get preview URLs. So: open a pull request and merge it into `main`.

### Launch checklist

1. **Custom domain** (optional): Vercel → Settings → Domains.
2. **Stamp your address** for link previews and search: `node scripts/set-origin.js https://your-domain` (sets canonical, `og:url`, absolute `og:image`, writes `sitemap.xml`, adds it to `robots.txt`).
3. **Reliable relays.** The built-in TURN servers are public, shared and best-effort. For anything you depend on, give Heat your own:
   - quickest: set `CLOUDFLARE_TURN_KEY_ID` + `CLOUDFLARE_TURN_API_TOKEN` (Cloudflare Realtime TURN) **or** `METERED_APP` + `METERED_API_KEY` (Metered) in the Vercel project's environment variables. [`api/ice.js`](api/ice.js) then hands every visitor short-lived credentials; without them it answers `204` and the page keeps its built-in list.
   - or edit the `CONFIG.iceServers` block at the top of the script in `index.html`.
4. **Your own signaling server** (the free PeerJS cloud has no uptime promise): run [`peerjs-server`](https://github.com/peers/peerjs-server) and set `CONFIG.signaling = {host, port, path, secure:true}`; add its `https://` and `wss://` origin to `connect-src` in `vercel.json`.
5. **Legal.** `privacy.html` and `terms.html` are plain-language templates matching what the app does. They are not legal advice: have them reviewed for your jurisdiction and add contact details if you run this as a public service.
6. Re-check the headers after any change at securityheaders.com.

### Configuration

Everything you may want to change is the `CONFIG` object near the top of the script in `public/index.html`:

```js
const CONFIG={
 iceServers:null,        // replace the whole STUN/TURN list
 signaling:null,         // {host, port, path, secure} for your own PeerServer
 iceEndpoint:'/api/ice'  // optional TURN-credentials function
};
```

### Stricter CSP (optional)

The page keeps its script and style inline (that is what makes it one file), so the shipped CSP allows `'unsafe-inline'` for those two. There is no HTML injection point (chat is rendered as text), but you can go stricter: `node scripts/csp-hash.js --apply` replaces `'unsafe-inline'` in `vercel.json` with hashes of exactly the inline blocks, so the browser refuses anything else. **Re-run it after every edit to the inline script or style**, or the browser will (correctly) refuse the edited code. `--loose` puts `'unsafe-inline'` back. The test suite can run against the strict policy: `CSP_STRICT=1 node tests/suite.js call`.

## Glass effects

The interface is liquid glass, tuned for readability first:

- **Thin refractive bezel, flat middle.** In Chromium each panel gets a narrow rim (a convex-edge profile pushed through Snell's law) that bends what is behind it, while the middle is left undistorted so text is never warped.
- **Prismatic fringe.** Red, green and blue are displaced by slightly different amounts (blue bends most, like real glass), and a specular layer (lit rim, soft caustic opposite, hairline edge) sits on top.
- **Almost no blur** (sub-pixel smoothing only). Readability comes from a base tint and an **adaptive scrim** instead: every ~0.7 s the app samples how bright the picture behind the glass is and dims it only as much as needed, so the glass stays clear over dark video and darkens over bright video.
- **Tiers:** Full / Lite (no colour fringe) / Off (plain panels), in Settings and the home footer. A frame-rate guard steps down on slow devices by itself. Other browsers get the CSS look (bright rim, dark readable core, no refraction).
- **Measured, not guessed:** the test suite screenshots the screens and computes WCAG contrast from the pixels. On the test scenes text is at least 5.9:1 and icons at least 6.9:1, even over a deliberately bright-yellow video. (Headless Chromium and synthetic video; real cameras vary.)

One implementation note worth knowing if you edit the CSS: Chromium sizes an SVG `backdrop-filter` from the element's *visual overflow*, so a `.glass` element must never paint outside its own box (no outer `box-shadow`, and `overflow:clip`), otherwise the lens shifts. Depth is drawn with inset shadows instead.

## Browser support

| | Calls | Lock | Glass |
| --- | --- | --- | --- |
| Chrome / Edge / Opera / Android Chrome | yes | yes | full refraction |
| Safari / iOS Safari 15.4+ | yes | yes | CSS look |
| Firefox 117+ | yes | yes | CSS look |
| Older browsers | yes | no (told clearly) | CSS look |

## Development

The app is one file: edit `public/index.html` directly (markup, `<style>`, then one `<script>` with clearly marked sections: `e2ee`, `app`, `glass`, `extras`). Serve `public/` with any static server (`localhost` is a secure context). The service worker is network-first; bump `V` in `public/sw.js` together with `BUILD` in the script.

Things that cannot live inside the HTML file by browser rules: the service worker (`sw.js`), the manifest, icons, and the two vendored libraries (`public/vendor/`, MIT licensed, see `public/vendor/LICENSES.txt`).

### Tests

`tests/` is self-contained (its own `package.json`; Vercel never installs it). It drives real Chromium through two or three browser contexts against a local signaling server and a local TURN server.

```
cd tests && npm install          # playwright-core, peer, node-turn  (+ npx playwright install chromium, or set CHROMIUM=/path/to/chrome)
npm run unit                     # TURN function, frame sealing, late answers (a few seconds)
node suite.js call               # one scenario;  LOCK=1 runs it with the end-to-end lock on
bash run-all.sh                  # everything, about 25 minutes
```

Scenarios: `call join watch relay slowanswer qol rotate rotatewatch zoom diag unavail noroute blackhole` (connection and viewing), `lock admit settings extras` (the privacy and product features), `fx` (glass tiers and the frame-rate guard), `contrast` (WCAG contrast measured from screenshots), `layout` (a layout audit on 13 viewports from a 184x224 watch to 2560x1440), `static pwa` (headers, assets, manifest, offline). Every scenario also fails on any CSP violation. `perf.js` checks that opening the page never freezes the main thread. `make-assets.js` regenerates the icons and social card.

## Third-party

[PeerJS](https://peerjs.com) 1.5.4 and [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) 1.4.4, both MIT, vendored unmodified.
