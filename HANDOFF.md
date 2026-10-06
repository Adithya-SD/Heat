# Heat: session handoff

Written so a fresh Claude Code session (cloud or local) can pick up the work with no chat history. Facts here were true at commit `bdc7524` on branch `claude/funny-mccarthy-l5y94g` (2026-10-06).

## 1. What Heat is

A single standalone web page, `public/index.html` (~138 KB): WebRTC **video calls** (1:1) and a **motion-detecting camera** (one camera, many viewers), device to device through PeerJS 1.5.4. No accounts, no media server. Served by Vercel from `public/`.

- The phone, watch-size and desktop UIs are the **same page**, switched by responsive CSS. There is no native phone or watch app in this repo.
- The owner loves it as one standalone HTML file. **Do not split it into separate JS/CSS files.** (Splitting would not help performance either; that was discussed and answered.)
- Things that cannot live inside the HTML by browser rules: `public/sw.js`, `manifest.webmanifest`, icons, and the two vendored libs `public/vendor/peerjs.min.js` and `public/vendor/qrcode.js`.

## 2. Standing rules from the owner

- Develop on `claude/funny-mccarthy-l5y94g`; push only there. **Do not open a PR unless asked.**
- The owner does their own Vercel merges. Production only updates when the branch is merged into `main` (`main` still has the old single-file app; this branch is far ahead).
- Prefer small token use: they asked for a stripped-down "gauntlet" loop without subagents (own 8-category rubric, ended around 89/100).
- Pronouns: use they/them unless told otherwise.

## 3. Repo map

```
public/index.html          THE app. Sections in the one <script>: /* ===== e2ee ===== */ (~l.504), app (~635), glass (~1239), extras (~1350)
                           CONFIG={iceServers,signaling,iceEndpoint} at top of the script (~l.638); BUILD='2026-10-03.1'; PV=2 (protocol version)
                           <script type="text/plain" id="e2ee-worker"> holds the frame-encryption worker source (turned into a Blob worker)
public/sw.js               network-first service worker; const V='heat-2026-10-03.1' MUST match BUILD in index.html
public/manifest.webmanifest, public/icons/*, public/privacy.html, terms.html, 404.html, robots.txt
public/vendor/*            peerjs.min.js, qrcode.js (MIT, unmodified), LICENSES.txt
vercel.json                outputDirectory public, cleanUrls, CSP / Permissions-Policy / security headers, cache headers
api/ice.js                 optional TURN credential function (Cloudflare or Metered via env vars; 204 when unset)
scripts/set-origin.js      stamps canonical/OG URLs + sitemap for a real domain
scripts/csp-hash.js        --apply swaps 'unsafe-inline' for hashes in vercel.json (re-run after ANY edit to inline script/style); --loose reverts
tests/                     own package.json (Vercel never installs it): lib.js, suite.js, api.js, e2ee-basic.js, e2ee.js, perf.js, run-all.sh, make-assets.js
README.md                  user-facing docs (features, security model, deploy checklist, config, glass, browser support, tests)
```

## 4. How it works (the parts that are easy to break)

**Connection.** Room id is `heat-` + 8-char code on PeerJS cloud (`0.peerjs.com`). A custom `config` replaces PeerJS's default ICE list, so TURN servers must be listed explicitly. `startConnection` in PeerJS is synchronous (the RTCPeerConnection exists right after `call()`/`answer()`). Host checks capacity in `c.on('open')` *before* sending `hello`, so a third device gets "full" without seeing a camera prompt.

**Messages** (data channel): `hello{kind,pv,lock,kc}`, `hi{name}`, `wait`, `no`, `full`, `bye`, `p` (heartbeat), `st`, `m`, `a`, `ms`, `chat`, `cfg`, `rx{x}`, `rec`, `bat`. The plain set is `p/hello/full/bye/wait/no`; everything else is sealed when the lock is on, via `put()`/`got()` ordered promise chains. Sealed envelope is `{_e:1,i,c}` (the marker is `_e` because reactions already use a field called `e`).

**End-to-end lock (default on).**
- 12-char secret (alphabet without 0/O/1/I/L) lives in the invite link `#join=CODE&k=KEY` fragment; stripped from the URL after use; `sessionStorage 'hk'` keeps it for same-tab reconnect. Typed codes ask for the key; links and QR do not.
- PBKDF2-SHA256 (150k) then HKDF to data and media subkeys; AES-GCM.
- Media frames: `[clear header][12-byte IV][ciphertext+tag]`; header is 10 bytes for a VP8 keyframe, 3 for a delta frame (decided from VP8 first-byte bit 0), 1 for Opus. IV byte 0 = `(role<<2)|kind`. `E.role` 1=host, 2=guest. Video is forced to VP8 (`vp8Only` SDP munge) in locked rooms.
- **Fail-closed hooks** on `RTCPeerConnection.prototype.addTrack` (seals the sender and the paired receiver at addTrack time; Chrome requires the receiver transform before `setRemoteDescription`), wrapped `ontrack` setter, a `setRemoteDescription` post-hook, a constructor wrapper, and a `lockCall` second pass. Do not "simplify" these away: a late-answer bug (guest `dec:0`) was caused by attaching the receiver transform too late.
- Safety code = SHA-256 of the sorted DTLS fingerprints, shown for out-loud comparison.

**Product features.** Admit/decline waiting room (calls only; `admitted` set keyed by peer id, so a rejoin is a new peer id and is asked again), display names, settings (camera/mic/speaker, quality, noise suppression, alert sounds, glass level), stats panel (`getStats`), local recording with a `rec` notice to the other side, PiP, reactions, arm-in-10-seconds, camera battery level, offline banner, install prompt, service-worker update toast, QR + share + copy invite.

**Liquid glass v2 (Chromium-only SVG `backdrop-filter`).** Per-element displacement map from a rounded-rect SDF with a convex-squircle to Snell profile, `LENS={bzF:.2,bzMin:6,bzMax:15,peak:1.9,ca:.18,n:1.5}`; three displacement maps with channel scales (1-ca, 1, 1+ca) + `feColorMatrix` + screen blend for the colour fringe; specular overlay via `feImage`. Tiers: full / lite / off (+ `css` look for non-Chromium); blur is ~0.4px. Readability comes from a per-class `--bri` tint plus an adaptive scrim `bk` measured from the picture behind (sampled 16x9 every ~0.7 s). A frame-rate guard steps full to lite to off. Maps are drawn at dpr<=2, rim-only, encoded with async `toBlob`, queued one per task and shared by size key.
- **Chromium quirk to respect:** any outer `box-shadow` or visual overflow on a `.glass` element (or its descendants) shifts where the SVG `backdrop-filter` image lands. Glass elements use inset shadows only, `overflow:hidden;overflow:clip`, and inside focus outlines.

**Responsive tiers.** watch (<=260px wide or tall: text-only cards, stacked segmented control, hidden self-view, full-screen sheets, `--bs:34px`), <=360, <=380, <=520, <=600 (bottom sheets), landscape <=480 tall (2-column home), >=900, >=1500. `--dock-h` is measured by a ResizeObserver; the dock uses `width:max-content`.

**Hardening.** CSP in `vercel.json` (`'unsafe-inline'` for script and style because it is one file; hashes optional), Permissions-Policy, no third-party requests, no cookies or analytics.

## 5. Tests (all in headless Chromium)

```
cd tests && npm install                  # playwright-core, peer, node-turn; set CHROMIUM=/path/to/chrome or: npx playwright install chromium
npm run unit                             # api.js + e2ee-basic.js + e2ee.js (seconds)
node suite.js call                       # one scenario; LOCK=1 runs it locked; CSP_STRICT=1 uses hashed CSP
bash run-all.sh                          # everything, ~25 min
```
Scenarios: `call join watch relay slowanswer qol rotate rotatewatch zoom diag unavail noroute blackhole lock admit settings extras fx contrast layout static pwa`. The suite starts a local PeerServer on 127.0.0.1:9000 and `node-turn`, uses fake camera/mic and canvas `captureStream`, spies on CSP violations (every scenario fails on one), checks WCAG contrast from screenshots (text >= 5.9:1, icons >= 6.9:1 on the test scenes) and audits layout on 13 viewports (184x224 up to 2560x1440). Last full run: about 800 checks passed (layout 116/116, contrast 18/18). One `qol` paste-invite check failed once and passed 3/3 reruns (treated as a flake).
`tests/node_modules` is git-ignored; `tests/out/` (screenshots) is git-ignored.

## 6. Edit checklist

1. Edit `public/index.html` directly.
2. If you changed what ships: bump `BUILD` in `index.html` **and** `V` in `public/sw.js` together.
3. If you changed the inline `<script>` or `<style>` and use strict CSP: `node scripts/csp-hash.js --apply`.
4. Run `npm run unit` and the relevant scenario(s), with `LOCK=1` too when touching connection or encryption code.
5. Commit and push to `claude/funny-mccarthy-l5y94g`. No PR unless asked.

## 7. Behaviour changes the owner was told about

- The lock is on by default; typed codes ask for the key, links and QR carry it.
- Admit prompt is on by default for calls; leaving and rejoining asks again (new peer id). A dropped-and-reconnected connection with the same peer id is not re-asked.

## 8. Not verified / known gaps

- **The owner's original real-world problem** (phone to PC in Opera stuck on "Looking for the host...") was never reproduced on real hardware. The in-app **Check connection** tool exists to diagnose it (signaling + each STUN/TURN server, copyable report). Suspects: Opera's built-in VPN or WebRTC blocking, and the shared public TURN list.
- Real devices, Safari and Firefox (especially sealed video), PiP (not exercisable headless), and a live Vercel deploy were not tested.
- TURN providers (Cloudflare/Metered) were only tested against a mock.
- `privacy.html` and `terms.html` are templates, not legal advice.
- The public PeerJS cloud signaling server has no uptime promise (README explains self-hosting).

## 9. Open question from the last conversation

The owner asked where "the phone and watch app" is and suggested it might be in a **"zcode" project**. Findings from the cloud session: no such directory exists in the cloud container, none of their five GitHub repos (`dynamic`, `Heat`, `moderdb`, `mdlab`, `GIT`) is named that, the other Heat branches contain only the web app, and a Drive search for "zcode" was empty. The cloud session cannot see the owner's computer. If a local session in their zcode folder finds a separate native/watch app, the next step is to look at how it relates to Heat (does it wrap `public/index.html` in a WebView? does it speak the same PeerJS protocol and `PV=2`?) before changing anything. If nothing exists, the answer is simply: the Heat phone and watch UI is `public/index.html`.
