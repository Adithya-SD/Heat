// Heat end-to-end suite (single-file app, served from public/ with the real vercel.json headers).
// usage: node suite.js <scenario>      LOCK=1 runs the same flows with the end-to-end lock on;  CSP_STRICT=1 serves the hash-based CSP
// scenarios: call watch join relay slowanswer blackhole unavail diag noroute fx rotate rotatewatch zoom qol
//            lock admit settings extras layout pwa api static
const fs = require('fs'), net = require('net'), path = require('path');
const { PeerServer } = require('peer');
const { serve, launch, ROOT } = require('./lib');
const OUT = path.join(__dirname, 'out'); fs.mkdirSync(OUT, { recursive: true });   // screenshots land here (git-ignored)

const scenario = process.argv[2] || 'call';
const LOCK = process.env.LOCK === '1';
const RELAY = scenario === 'relay' || scenario === 'noroute' || !!process.env.RELAY;
const SLOW = +process.env.SLOWMS || (scenario === 'slowanswer' ? 8000 : 0);
const PEER_PORT = 9000, WEB_PORT = 8088;
const peerjsSrc = fs.readFileSync(path.join(ROOT, 'vendor', 'peerjs.min.js'), 'utf8');
const patch = `;(()=>{const P=window.Peer,RELAY=${RELAY},SLOW=${SLOW};window.Peer=class extends P{constructor(a,b){if(a&&typeof a==='object'){b=a;a=undefined}b=b||{};
 if(RELAY){const c=b.config||{iceServers:[]};window.__iceSeen=JSON.stringify(c.iceServers);
  b={...b,config:{...c,iceTransportPolicy:'relay',iceServers:(c.iceServers||[]).filter(s=>[].concat(s.urls).some(u=>/^turns?:/.test(u))).map(s=>({...s,urls:'turn:127.0.0.1:3478'}))}}}
 super(a,{host:'127.0.0.1',port:${PEER_PORT},path:'/',secure:false,...b});
 if(SLOW){const emit=this.emit.bind(this);this.emit=(ev,...x)=>{if(ev==='call'){const c=x[0],ans=c.answer.bind(c);c.answer=(...y)=>setTimeout(()=>ans(...y),SLOW)}return emit(ev,...x)}}}}})();`;

const CANVAS_GUM = `(()=>{const md=navigator.mediaDevices,orig=md.getUserMedia.bind(md);
 const cv=document.createElement('canvas');cv.width=640;cv.height=360;const g=cv.getContext('2d');let f=0;
 setInterval(()=>{f++;g.fillStyle=window.__fill||'#0a5';g.fillRect(0,0,cv.width,cv.height);g.fillStyle='#fff';g.font='bold 36px sans-serif';g.fillText(cv.width+'x'+cv.height,16,56);
  g.fillStyle='#fc0';g.beginPath();g.arc(60+(f*7)%(cv.width-120),cv.height/2,30,0,7);g.fill()},60);
 window.__setSize=(w,h)=>{cv.width=w;cv.height=h};
 md.getUserMedia=async c=>{const out=new MediaStream();
  if(c&&c.video)cv.captureStream(15).getVideoTracks().forEach(t=>out.addTrack(t));
  if(c&&c.audio){try{(await orig({audio:c.audio})).getAudioTracks().forEach(t=>out.addTrack(t))}catch{}}
  return out}})();`;
// records every datachannel payload as latin1 text (to prove what does / does not travel in the clear)
const SPY = `(()=>{window.__sent=[];const s=RTCDataChannel.prototype.send;RTCDataChannel.prototype.send=function(d){try{const u=d instanceof ArrayBuffer?new Uint8Array(d):ArrayBuffer.isView(d)?new Uint8Array(d.buffer,d.byteOffset,d.byteLength):null;if(u)window.__sent.push(String.fromCharCode.apply(null,u.subarray(0,60000)))}catch{}return s.call(this,d)}})();`;
const CSPSEE = `window.__tt=[];document.addEventListener('DOMContentLoaded',()=>{const t=document.querySelector('#tt');new MutationObserver(()=>{const e=t.querySelector('span');if(e&&e.textContent)window.__tt.push(e.textContent)}).observe(t,{childList:true,subtree:true,characterData:true})});window.__csp=[];document.addEventListener('securitypolicyviolation',e=>window.__csp.push(e.violatedDirective+' '+e.blockedURI));`;
const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
const check = (name, ok, extra = '') => { results.push(ok); log(ok ? 'PASS' : 'FAIL', name, extra); return ok; };

async function waitFor(page, fn, arg, timeout = 20000) {
  try { await page.waitForFunction(fn, arg, { timeout, polling: 100 }); return true; } catch { return false; }
}
const txt = (page, sel) => page.$eval(sel, e => e.textContent).catch(() => null);
const scr = page => page.evaluate(() => document.body.dataset.s);
const toastSeen = (page, re, t = 20000) => waitFor(page, s => window.__tt.some(x => new RegExp(s).test(x)), re.source, t);

(async () => {
  let peerServer, blackhole;
  const startPeer = () => (peerServer = PeerServer({ port: PEER_PORT, host: '127.0.0.1', path: '/', allow_discovery: false }));
  if (scenario === 'blackhole') {
    const socks = new Set();
    blackhole = net.createServer(s => { socks.add(s); s.on('error', () => {}); }).listen(PEER_PORT, '127.0.0.1');
    blackhole.killAll = () => { socks.forEach(s => s.destroy()); blackhole.close(); };
    log('signaling socket is a black hole (no response) for the first 14s');
  } else startPeer();

  let turn;
  if ((RELAY && scenario !== 'noroute') || scenario === 'diag') {
    const Turn = require('node-turn');
    turn = new Turn({ authMech: 'long-term', listeningIps: ['127.0.0.1'], relayIps: ['127.0.0.1'], listeningPort: 3478,
      credentials: { peerjs: 'peerjsp', openrelayproject: 'openrelayproject' }, debugLevel: 'OFF' });
    turn.start(); log('local TURN on 127.0.0.1:3478; relay-only ICE policy');
  }
  const web = await serve(WEB_PORT);
  const browser = await launch();
  const pages = [];
  const mk = async (name, hash = '', vp = { width: 900, height: 700 }, opts = {}) => {
    const ctx = await browser.newContext({ permissions: ['camera', 'microphone'], viewport: vp, isMobile: !!opts.mobile, hasTouch: !!opts.mobile, serviceWorkers: opts.sw ? 'allow' : 'block', deviceScaleFactor: opts.dpr || 1 });
    await ctx.addInitScript(`try{localStorage.setItem('lk','${(opts.lock !== undefined ? opts.lock : LOCK) ? '1' : '0'}');if(!${!!opts.ask})localStorage.setItem('ask','0');${opts.ls || ''}}catch{}` + CSPSEE);
    if (opts.canvas) await ctx.addInitScript(CANVAS_GUM);
    if (opts.spy) await ctx.addInitScript(SPY);
    if (opts.init) await ctx.addInitScript(opts.init);
    await ctx.route('**/*', r => {
      const u = r.request().url();
      if (u.endsWith('/vendor/peerjs.min.js')) return r.fulfill({ contentType: 'application/javascript', body: peerjsSrc + patch });
      return r.continue();
    });
    const page = await ctx.newPage();
    page.on('pageerror', e => { log(`[${name}] PAGEERROR`, e.message); results.push(false); });
    page.on('console', m => { if (m.type() === 'error' && !/favicon|Failed to load resource.*(404|net::ERR)/.test(m.text())) log(`[${name}] console.error`, m.text().slice(0, 160)); });
    await page.goto(`http://127.0.0.1:${WEB_PORT}/${opts.url || ''}${hash}`);
    pages.push(page); page._name = name; page._ctx = ctx;
    return page;
  };

  // ======================= glass tiers =======================
  async function fxScenario() {
    const page = await mk('p');
    const info = () => page.evaluate(() => {
      const gl = [...document.querySelectorAll('.glass')], withF = gl.filter(e => e.style.backdropFilter.includes('url('));
      const fs_ = [...document.querySelectorAll('#fd filter')];
      return { n: withF.length, total: gl.length, filters: fs_.length, disp: fs_[0] ? fs_[0].querySelectorAll('feDisplacementMap').length : 0,
        imgs: fs_[0] ? fs_[0].querySelectorAll('feImage').length : 0, mode: document.body.dataset.fx, label: document.querySelector('#fxb').textContent, ls: localStorage.getItem('fx') };
    });
    await waitFor(page, () => [...document.querySelectorAll('.glass')].some(e => e.style.backdropFilter.includes('url(')), null, 8000);
    let i = await info();
    check('full: refraction filters on the glass elements', i.n >= 4 && i.mode === 'full' && /Full/.test(i.label), JSON.stringify(i));
    check('full: 3 displacement maps (colour fringe) + map + specular layer', i.disp === 3 && i.imgs === 2, JSON.stringify(i));
    check('same-size elements share one filter', i.filters < i.n, `${i.filters} filters for ${i.n} elements`);
    check('blur is sub-pixel', await page.$eval('#bh', e => /blur\(0?\.4px\)/.test(e.style.backdropFilter)), await page.$eval('#bh', e => e.style.backdropFilter));
    // the lens: flat middle (readable), thin bezel, strong pull at the very edge
    const lensProbe = await page.evaluate(async () => {
      const f = document.querySelector('#fd filter'), W = +f.querySelector('feImage').getAttribute('width'), H = +f.querySelector('feImage').getAttribute('height');
      const im = new Image(); im.src = f.querySelector('feImage').getAttribute('href'); await im.decode();
      const c = document.createElement('canvas'); c.width = W; c.height = H; const g = c.getContext('2d'); g.drawImage(im, 0, 0);
      const px = (x, y) => [...g.getImageData(x, y, 1, 1).data];
      const row = Math.floor(H / 2); let bez = 0; while (bez < W / 2 && Math.abs(px(bez, row)[0] - 128) > 1) bez++;
      return { W, H, centre: px(Math.floor(W / 2), row), edge: px(0, row), bezel: bez };
    });
    check('lens: middle is perfectly flat (text is never distorted)', lensProbe.centre[0] === 128 && lensProbe.centre[1] === 128, JSON.stringify(lensProbe));
    check('lens: bezel is thin (<= 15px) with a strong pull at the edge', lensProbe.bezel <= 15 && Math.abs(lensProbe.edge[0] - 128) > 60, JSON.stringify(lensProbe));
    await page.click('#fxb');
    await waitFor(page, () => document.querySelectorAll('#fd filter').length > 0 && [...document.querySelectorAll('.glass')].some(e => e.style.backdropFilter.includes('url(')), null, 8000);
    i = await info();
    check('lite: single displacement, no colour split', i.mode === 'lite' && i.disp === 1 && i.n >= 4 && /Lite/.test(i.label), JSON.stringify(i));
    await page.click('#fxb');
    i = await info();
    check('off: no lens filters, plain panels', i.n === 0 && i.mode === 'off' && i.filters === 0 && /Off/.test(i.label) && i.ls === 'off', JSON.stringify(i));
    check('off: no backdrop blur at all (cheap and readable)', await page.$eval('#bh', e => getComputedStyle(e).backdropFilter === 'none'));
    await page.click('#fxb'); await waitFor(page, () => document.body.dataset.fx === 'full' && [...document.querySelectorAll('.glass')].some(e => e.style.backdropFilter.includes('url(')), null, 6000);
    check('cycles back to full', (await info()).mode === 'full');
    await page.click('#fxb'); await page.reload(); await sleep(900);
    check('choice persists across reload', (await info()).mode === 'lite' && (await info()).n >= 4);
    await page.click('#fxb'); await page.click('#fxb');   // lite -> off -> full
    // a device that cannot keep up: the guard steps full -> lite -> off by itself
    await page.click('#bh'); await waitFor(page, () => document.body.dataset.s === 'call', null, 10000);
    await sleep(3500);
    check('guard stays quiet on a healthy device', await page.evaluate(() => fxMode) === 'full');
    await page.evaluate(() => { window.__hog = setInterval(() => { const t = performance.now(); while (performance.now() - t < 95); }, 100); });
    check('guard steps down to lite', await waitFor(page, () => fxMode === 'lite', null, 40000));
    check('and tells the user', await page.evaluate(() => /Glass switched to Lite/.test(document.querySelector('#tt span').textContent)));
    check('guard steps down again to off', await waitFor(page, () => fxMode === 'off', null, 40000));
    check('lens filters are gone', (await info()).n === 0);
  }

  // ======================= new feature scenarios =======================
  async function newScenarios() {
    const inCall = (p, t = 25000) => waitFor(p, () => document.querySelector('#cst')?.textContent === 'In call', null, t);
    const startCall = async (name = 'host', opts = {}) => {
      const host = await mk(name, '', { width: 900, height: 700 }, { canvas: true, ...opts });
      await host.click('#bh');
      await waitFor(host, () => document.querySelector('#pop').classList.contains('s') && /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(document.querySelector('#pc').textContent), null, 20000);
      return [host, (await txt(host, '#pc')).trim()];
    };
    const media = p => p.evaluate(() => { const r = document.querySelector('#rv'); return { w: r.videoWidth, t: r.currentTime }; });
    const playing = async p => { const a = await media(p); await sleep(1100); const b = await media(p); return a.w > 0 && b.t > a.t; };
    const cspOk = async label => { for (const p of pages) { try { const v = await p.evaluate(() => window.__csp); check(`no CSP violations (${label}/${p._name})`, !v.length, v.join(', ')); } catch {} } };

    if (scenario === 'lock') {
      const [host, code] = await startCall('host', { lock: true, spy: true });
      const key = await host.evaluate(() => E.secret);
      check('host: invite shows a 12-character room key', !(await host.$eval('#pkr', e => e.hidden)) && /^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(await txt(host, '#pk')) && key.length === 12, key);
      const link = await host.evaluate(() => inviteUrl());
      check('invite link carries the key only in the #fragment', /#join=[A-Z2-9]{8}&k=[A-Z2-9]{12}$/.test(link) && !link.split('#')[0].includes(key), link);
      check('host: lock on, media + data sealed state', await host.evaluate(() => E.on === 1 && E.media === true));
      // browser without the sealing API
      const old = await mk('old', '#join=' + code.replace('-', '') + '&k=' + key, { width: 900, height: 700 }, { init: 'delete window.RTCRtpScriptTransform;' });
      check('browser without frame sealing cannot join a locked room, says why', await toastSeen(old, /can.t join locked rooms/) && await waitFor(old, () => document.body.dataset.s === 'home', null, 8000), JSON.stringify(await old.evaluate(() => window.__tt)));
      // a typed code without a key (room still empty): prompt, cancel -> home, host untouched
      const nokey = await mk('nokey'); await nokey.fill('#ci', code);
      check('code without key -> key prompt', await waitFor(nokey, () => document.querySelector('#keyp').classList.contains('s'), null, 15000));
      await nokey.keyboard.press('Escape');
      check('Esc cancels the prompt and goes home', await waitFor(nokey, () => document.body.dataset.s === 'home' && !document.querySelector('#keyp').classList.contains('s'), null, 8000));
      check('host admitted nobody', await host.evaluate(() => viewers.size === 0));
      // wrong key from a link -> asks, refuses a wrong key, accepts the right one
      const guest = await mk('guest', '#join=' + code.replace('-', '') + '&k=AAAAAAAAAAAA', { width: 900, height: 700 }, { spy: true });
      check('wrong key in the link -> asks for the key', await waitFor(guest, () => document.querySelector('#keyp').classList.contains('s'), null, 20000));
      check('URL fragment (and key) removed from the address bar', await guest.evaluate(() => location.hash === ''));
      await guest.fill('#keyi', 'BBBBBBBBBBBB'); await guest.press('#keyi', 'Enter');
      check('wrong key typed -> says it does not match, prompt stays', await waitFor(guest, () => /doesn.t match/.test(document.querySelector('#keyh').textContent) && document.querySelector('#keyp').classList.contains('s'), null, 15000), await txt(guest, '#keyh'));
      check('host never let the wrong-key guest in', await host.evaluate(() => viewers.size === 0 && knock.size <= 1));
      await guest.fill('#keyi', key); await guest.press('#keyi', 'Enter');
      check('right key -> guest joins', await inCall(guest));
      check('host reaches In call', await waitFor(host, () => document.querySelector('#cst').textContent === 'In call', null, 15000));
      await sleep(2500);
      // frames are really sealed on the way out and opened on the way in, both directions
      for (const p of [host, guest]) {
        const ws = await p.evaluate(() => ({ ...E.ws }));
        check(`${p._name}: frames sealed and opened, none rejected`, ws.enc > 20 && ws.dec > 20 && ws.bad === 0, JSON.stringify(ws));
        check(`${p._name}: remote video plays through the lock`, await playing(p));
      }
      const codec = await host.evaluate(async () => { const pc = [...viewers.values()][0].call.peerConnection, r = await pc.getStats(); let c = ''; r.forEach(s => { if (s.type === 'codec' && /video/.test(s.mimeType)) c = s.mimeType; }); return c; });
      check('video codec is VP8 (what the sealed frame header needs)', /VP8/.test(codec), codec);
      // chat never crosses the data channel in the clear
      await host.click('#ct'); await host.fill('#cin', 'needle-chat-4711'); await host.press('#cin', 'Enter');
      check('chat arrives', await waitFor(guest, () => [...document.querySelectorAll('#cl .msg.them span')].some(s => s.textContent === 'needle-chat-4711'), null, 8000));
      const sent = await host.evaluate(() => window.__sent);
      check('spy works: the clear-text hello was seen', sent.some(s => s.includes('hello') && s.includes('kind')), `${sent.length} payloads`);
      check('chat text never travelled in the clear', !sent.some(s => s.includes('needle-chat-4711')));
      check('sealed envelopes are used instead', sent.some(s => s.includes('"e"') || s.includes('e¡') || /\x00?e/.test(s)) && sent.length > 5);
      // safety code + panel
      await host.click('#callS [data-sec]'); await guest.click('#callS [data-sec]');
      const rows = p => p.evaluate(() => Object.fromEntries([...document.querySelectorAll('#secl > div')].map(d => [d.children[0].textContent, d.children[1].textContent])));
      await waitFor(host, () => /Incoming/.test(document.querySelector('#secl').textContent) && /Safety code/.test(document.querySelector('#secl').textContent), null, 12000);
      await waitFor(guest, () => /Incoming/.test(document.querySelector('#secl').textContent) && /Safety code/.test(document.querySelector('#secl').textContent), null, 12000);
      const rh = await rows(host), rg = await rows(guest);
      check('panel: lock On and sealed video', rh['End-to-end lock'] === 'On' && /Sealed/.test(rh['Video and audio']), JSON.stringify(rh));
      check('panel: path, latency and bitrate are reported', !!rh['Path'] && /ms/.test(rh['Latency'] || '') && /kbps/.test(rh['Incoming'] || ''), JSON.stringify(rh));
      check('safety codes match on both screens', /^[0-9A-F]{4}( [0-9A-F]{4}){3}$/.test(rh['Safety code']) && rh['Safety code'] === rg['Safety code'], `${rh['Safety code']} / ${rg['Safety code']}`);
      check('padlock shows locked', await host.$eval('#callS [data-sec]', e => e.classList.contains('ok')));
      await host.keyboard.press('Escape'); await guest.keyboard.press('Escape');
      check('Esc closes the panel', await host.evaluate(() => !document.querySelector('#secp').classList.contains('s')));
      // the room is occupied: a device without the key is simply told it is full (no prompt, nothing revealed)
      const full = await mk('full'); await full.fill('#ci', code);
      check('occupied room: stranger is told it is full, never asked for a key', await waitFor(full, () => /full/i.test(document.querySelector('#vst')?.textContent || ''), null, 15000) && await full.evaluate(() => !document.querySelector('#keyp').classList.contains('s')));
      check('call unaffected', await txt(host, '#cst') === 'In call');
      // a locked guest meeting an UNLOCKED room refuses (no silent downgrade)
      const [h2, code2] = await startCall('host2', { lock: false });
      const g2 = await mk('g2', '#join=' + code2.replace('-', '') + '&k=ABCDEFGHJKMN');
      check('key in link but room not locked -> refuses, back home', await toastSeen(g2, /isn.t locked/) && await waitFor(g2, () => document.body.dataset.s === 'home', null, 8000), JSON.stringify(await g2.evaluate(() => window.__tt)));
      const old2 = await mk('old2', '', { width: 900, height: 700 }, { init: 'delete window.RTCRtpScriptTransform;', lock: true });
      check('home: lock switch is disabled there', await old2.$eval('#lockc', e => e.disabled && !e.checked));
      await old2.evaluate(() => { lockPick = true; });
      await old2.click('#bh');
      check('host with lock requested but unsupported is told, does not start', await toastSeen(old2, /needs a newer browser/, 8000) && await old2.evaluate(() => document.body.dataset.s === 'home'));
      await cspOk('lock');
      return;
    }

    if (scenario === 'admit') {
      const [host, code] = await startCall('host', { ask: true, ls: "localStorage.setItem('name','Hosty');" });
      const guest = await mk('guest', '', { width: 900, height: 700 }, { ls: "localStorage.setItem('name','Gina');" });
      await guest.fill('#ci', code);
      check('host sees an admit prompt naming the guest', await waitFor(host, () => !document.querySelector('#adm').hidden && document.querySelector('#admn').textContent === 'Gina', null, 20000), await txt(host, '#admn'));
      check('guest is told to wait for the host', await waitFor(guest, () => /let you in/.test(document.querySelector('#cst').textContent), null, 10000), await txt(guest, '#cst'));
      check('no media before admission', await host.evaluate(() => viewers.size === 0));
      await sleep(23000);   // longer than the 20s redial window: waiting must not be mistaken for a stuck connection
      check('still waiting after 23s (no redial, no timeout)', await guest.evaluate(() => /let you in/.test(document.querySelector('#cst').textContent) && conn && conn.open) && await host.$eval('#adm', e => !e.hidden));
      check('the "Still trying…" network warning is not shown while waiting', !/Still trying/.test(await txt(guest, '#covh')));
      await host.click('#admx');
      check('declined guest goes home with a notice', await waitFor(guest, () => document.body.dataset.s === 'home' && /didn.t let you in/.test(document.querySelector('#tt span').textContent), null, 10000));
      check('prompt closes and host waits again', await host.evaluate(() => document.querySelector('#adm').hidden) && /Waiting|asking|Connecting/.test(await txt(host, '#cst')));
      await guest.fill('#ci', ''); await guest.fill('#ci', code);
      await waitFor(host, () => !document.querySelector('#adm').hidden, null, 20000);
      await host.click('#admy');
      check('admitted guest joins the call', await inCall(guest));
      check('host reaches In call', await waitFor(host, () => document.querySelector('#cst').textContent === 'In call', null, 15000));
      check('names are shown on both sides', await waitFor(host, () => document.querySelector('#rtxt').textContent === 'Gina' && !document.querySelector('#rtag').hidden, null, 6000) && await waitFor(guest, () => document.querySelector('#rtxt').textContent === 'Hosty', null, 6000), `${await txt(host, '#rtxt')} / ${await txt(guest, '#rtxt')}`);
      await guest.click('#ct'); await guest.fill('#cin', 'hi from gina'); await guest.press('#cin', 'Enter');
      check('chat shows the sender name', await waitFor(host, () => [...document.querySelectorAll('#cl .msg.them small')].some(s => /^Gina · /.test(s.textContent)), null, 8000));
      // leaving and coming back is a new session: the host is asked again
      await guest.click('#cx'); await waitFor(host, () => /Waiting/.test(document.querySelector('#cst').textContent), null, 8000);
      await guest.click('#rc');
      check('coming back after leaving asks the host again', await waitFor(host, () => !document.querySelector('#adm').hidden, null, 20000));
      await host.click('#admy');
      check('and joins once admitted', await inCall(guest));
      // a dropped connection (same device, same session) is not asked about again
      await guest.evaluate(() => { try { conn.close(); } catch {} });
      check('after a network drop the same guest is let straight back in', await waitFor(guest, () => document.querySelector('#cst').textContent !== 'In call', null, 8000) && await inCall(guest, 30000) && await host.$eval('#adm', e => e.hidden));
      // a third device is turned away, not queued behind the prompt
      const third = await mk('third'); await third.fill('#ci', code);
      check('third device told the room is full', await waitFor(third, () => /full/i.test(document.querySelector('#vst')?.textContent || ''), null, 15000));
      check('host not bothered by the third device', await host.$eval('#adm', e => e.hidden));
      await cspOk('admit');
      return;
    }

    if (scenario === 'settings') {
      const [host, code] = await startCall('host', { ls: "localStorage.setItem('name','Hosty');" });
      const guest = await mk('guest', '', { width: 900, height: 700 }); await guest.fill('#ci', code);
      check('call up', await inCall(guest));
      await waitFor(host, () => document.querySelector('#cst').textContent === 'In call', null, 15000);
      await host.click('#cmore');
      check('more menu opens above the dock', await host.evaluate(() => { const m = document.querySelector('#more').getBoundingClientRect(), d = document.querySelector('#callS .dock').getBoundingClientRect(); return !document.querySelector('#more').hidden && m.bottom <= d.top + 1; }));
      check('menu offers record, pip, security, settings (invite for host)', await host.evaluate(() => ['#mi-rec', '#mi-pip', '#mi-sec', '#mi-set', '#mi-inv', '#rxrow'].every(s => !document.querySelector(s).hidden || s === '#mi-pip')));
      await host.click('#mi-set');
      check('settings opens, menu closed', await host.evaluate(() => document.querySelector('#setp').classList.contains('s') && document.querySelector('#more').hidden));
      await waitFor(host, () => document.querySelectorAll('#s-cam option').length >= 2, null, 5000);
      const cams = await host.$$eval('#s-cam option', o => o.map(x => x.value));
      check('camera picker lists the devices', cams.length >= 3, cams.length + ' options');
      const before = await host.evaluate(() => document.querySelector('#lv').srcObject.getVideoTracks()[0].getSettings().deviceId);
      await host.selectOption('#s-cam', cams[2] === before ? cams[1] : cams[2]);
      await sleep(1800);
      const after = await host.evaluate(() => document.querySelector('#lv').srcObject.getVideoTracks()[0].getSettings().deviceId);
      check('choosing another camera switches the track', before !== after, `${before.slice(0, 6)} -> ${after.slice(0, 6)}`);
      check('guest keeps receiving video after the switch', await playing(guest));
      const mics = await host.$$eval('#s-mic option', o => o.length);
      check('microphone picker populated', mics >= 2, mics + ' options');
      await host.selectOption('#s-mic', (await host.$$eval('#s-mic option', o => o.map(x => x.value)))[1]);
      await sleep(900);
      check('call survives a microphone switch', await playing(guest) && await host.evaluate(() => !!document.querySelector('#lv').srcObject.getAudioTracks()[0]));
      await host.fill('#s-name', 'Zed');
      check('name change reaches the other side', await waitFor(guest, () => document.querySelector('#rtxt').textContent === 'Zed', null, 8000));
      await host.selectOption('#s-q', 'sd');
      await sleep(600);
      const br = await host.evaluate(() => { const s = [...viewers.values()][0].call.peerConnection.getSenders().find(x => x.track && x.track.kind === 'video'); return s.getParameters().encodings[0].maxBitrate; });
      check('data-saver quality lowers the send bitrate cap', br === 600000 && await host.evaluate(() => localStorage.getItem('q')) === 'sd', String(br));
      await host.selectOption('#s-q', 'hd'); await sleep(300);
      check('high quality raises it', (await host.evaluate(() => [...viewers.values()][0].call.peerConnection.getSenders().find(x => x.track && x.track.kind === 'video').getParameters().encodings[0].maxBitrate)) === 5000000);
      await host.uncheck('#s-ns');
      check('noise suppression choice stored', await host.evaluate(() => localStorage.getItem('ns') === '0' && nsOn === false));
      check('"ask before admitting" is offered to a call host', await host.$eval('#s-adw', e => !e.hidden));
      await host.selectOption('#s-fx', 'lite');
      check('glass level applies at once', await host.evaluate(() => fxMode === 'lite' && document.body.dataset.fx === 'lite'));
      let escaped = 0; for (let k = 0; k < 24; k++) { await host.keyboard.press(k % 5 === 4 ? 'Shift+Tab' : 'Tab'); if (!(await host.evaluate(() => document.querySelector('#setp').contains(document.activeElement)))) escaped++; }
      check('keyboard focus stays inside the open dialog (Tab and Shift+Tab)', escaped === 0, escaped + ' escapes');
      await host.keyboard.press('Escape');
      check('Esc closes settings and returns focus', await host.evaluate(() => !document.querySelector('#setp').classList.contains('s')) && await host.evaluate(() => document.activeElement && document.activeElement.id !== 'keyi'));
      // settings are reachable from the home screen too
      await guest.click('#cx'); await waitFor(guest, () => document.body.dataset.s === 'home', null, 8000);
      await guest.click('#hset');
      check('settings open from the home screen', await guest.evaluate(() => document.querySelector('#setp').classList.contains('s')));
      await guest.click('#s-done');
      check('Done closes', await guest.evaluate(() => !document.querySelector('#setp').classList.contains('s')));
      await cspOk('settings');
      return;
    }

    if (scenario === 'extras') {
      const [host, code] = await startCall('host', { lock: LOCK });
      await host.keyboard.press('Escape');
      await host.click('#cmore');
      check('before anyone joins: no record / pip, invite is offered', await host.evaluate(() => document.querySelector('#mi-rec').hidden && document.querySelector('#mi-pip').hidden && !document.querySelector('#mi-inv').hidden));
      await host.keyboard.press('Escape');
      check('Esc closes the menu', await host.evaluate(() => document.querySelector('#more').hidden));
      await host.keyboard.press('Escape');
      const guest = await mk('guest', '', { width: 900, height: 700 }, { canvas: true }); await joinCode(guest, code);
      check('call up', await inCall(guest) && await waitFor(host, () => document.querySelector('#cst').textContent === 'In call', null, 15000));
      await sleep(1500);
      // reactions
      await host.click('#cmore'); await host.click('#rxrow button[data-e="👍"]');
      check('reaction floats on both screens', await waitFor(host, () => document.querySelectorAll('#rxl .rxe').length === 1, null, 3000) && await waitFor(guest, () => document.querySelectorAll('#rxl .rxe').length >= 1, null, 5000));
      check('reaction cleans itself up', await waitFor(host, () => document.querySelectorAll('#rxl .rxe').length === 0, null, 6000));
      // recording with notice
      await guest.keyboard.press('.');
      check('"." opens the menu', await guest.evaluate(() => !document.querySelector('#more').hidden && !document.querySelector('#mi-rec').hidden));
      await guest.click('#mi-rec');
      check('recording indicator on the recorder', await guest.evaluate(() => !document.querySelector('#recb').hidden && /REC/.test(document.querySelector('#recb').textContent)));
      check('the other person is told they are being recorded', await waitFor(host, () => !document.querySelector('#recb').hidden && /started recording/.test(document.querySelector('#tt span').textContent), null, 8000), await txt(host, '#tt span'));
      await sleep(2500);
      const [dl] = await Promise.all([guest.waitForEvent('download', { timeout: 15000 }).catch(() => null), guest.evaluate(() => { document.querySelector('#more').hidden = true; document.querySelector('#recb').click(); })]);
      check('stopping saves a recording file', !!dl && /^heat-.*\.webm$/.test(dl.suggestedFilename()), dl && dl.suggestedFilename());
      if (dl) { const p = await dl.path(); check('recording is not empty', fs.statSync(p).size > 5000, fs.statSync(p).size + ' bytes'); }
      check('indicator clears on both sides', await waitFor(host, () => document.querySelector('#recb').hidden, null, 8000) && await guest.evaluate(() => document.querySelector('#recb').hidden));
      // shortcuts panel
      await host.keyboard.press('?');
      check('? opens the shortcuts list', await host.evaluate(() => document.querySelector('#kb').classList.contains('s')));
      await host.keyboard.press('Escape');
      // offline banner
      await host._ctx.setOffline(true);
      check('offline banner appears', await waitFor(host, () => !document.querySelector('#net').hidden && /offline/i.test(document.querySelector('#nett').textContent), null, 4000));
      await host._ctx.setOffline(false);
      check('banner reports back online and goes away', await waitFor(host, () => document.querySelector('#net').hidden, null, 8000));
      check('install button stays hidden when not installable', await host.evaluate(() => document.querySelector('#inst').hidden));
      // motion camera: arm delay + battery + tap to save
      const [wh, wcode] = await (async () => { const h = await mk('whost', '', { width: 900, height: 700 }, { canvas: true }); await h.click('#seg button[data-m="watch"]'); await h.click('#bh'); await waitFor(h, () => document.querySelector('#hst').textContent === 'Waiting for viewer', null, 15000); return [h, await txt(h, '#hcode span')]; })();
      const wv = await mk('wview', '', { width: 900, height: 700 }); await joinCode(wv, wcode);
      check('viewer live', await waitFor(wv, () => document.querySelector('#vst').textContent === 'Live', null, 25000));
      await wh.click('#hmore'); await wh.click('#mi-arm');
      check('"arm in 10 seconds": alerts off, countdown shown', await wh.evaluate(() => st.arm === false && /Arming in/.test(document.querySelector('#hst').textContent)));
      check('viewer sees alerts disarmed meanwhile', await waitFor(wv, () => !document.querySelector('#viewS .arm').classList.contains('on'), null, 5000));
      check('alerts arm themselves after the delay', await waitFor(wh, () => st.arm === true, null, 16000));
      check('viewer sees them armed again', await waitFor(wv, () => document.querySelector('#viewS .arm').classList.contains('on'), null, 5000));
      if (await wh.evaluate(() => !!navigator.getBattery)) check('battery level reaches the viewer', await waitFor(wv, () => /\d+%/.test(document.querySelector('#vbat').textContent), null, 10000), await txt(wv, '#vbat'));
      else log('SKIP battery (not exposed in this browser)');
      await waitFor(wv, () => document.querySelectorAll('#evl .ev').length > 0, null, 25000);
      await wv.click('#ve');
      const [dl2] = await Promise.all([wv.waitForEvent('download', { timeout: 8000 }).catch(() => null), wv.click('#evl .ev')]);
      check('tapping an alert saves its snapshot', !!dl2 && /^heat-motion-\d+\.jpg$/.test(dl2.suggestedFilename()), dl2 && dl2.suggestedFilename());
      await cspOk('extras');
      return;
    }

    if (scenario === "layout") return await layoutScenario();
    if (scenario === 'contrast') return await contrastScenario();

    if (scenario === 'pwa' || scenario === 'static') {
      const page = await mk('p', '', { width: 900, height: 700 }, { sw: scenario === 'pwa' });
      const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
      const sw = fs.readFileSync(path.join(ROOT, 'sw.js'), 'utf8');
      const get = (u) => page.evaluate(async u => { const r = await fetch(u); return { s: r.status, t: (r.headers.get('content-type') || ''), b: (await r.text()).slice(0, 400), csp: r.headers.get('content-security-policy') || '', pp: r.headers.get('permissions-policy') || '', rp: r.headers.get('referrer-policy') || '', xc: r.headers.get('x-content-type-options') || '' }; }, u);
      if (scenario === 'static') {
        const vj = JSON.parse(fs.readFileSync(path.join(ROOT, '..', 'vercel.json'), 'utf8'));
        const csp = (vj.headers[0].headers.find(h => h.key === 'Content-Security-Policy') || {}).value || '';
        check('CSP: default-src self, no eval, no plugins, no framing, no base tag', /default-src 'self'/.test(csp) && !/unsafe-eval/.test(csp) && /object-src 'none'/.test(csp) && /frame-ancestors 'none'/.test(csp) && /base-uri 'none'/.test(csp));
        check('CSP: connect-src limited to self + the PeerJS signaling server', /connect-src 'self' https:\/\/0\.peerjs\.com wss:\/\/0\.peerjs\.com;/.test(csp));
        check('headers: camera/microphone limited to this origin, referrer none, nosniff', /camera=\(self\)/.test(JSON.stringify(vj)) && /no-referrer/.test(JSON.stringify(vj)) && /nosniff/.test(JSON.stringify(vj)));
        const strictCsp = require('../scripts/csp-hash').strict(csp, fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8'));
        check('strict CSP (scripts/csp-hash.js): no unsafe-inline, one hash each for the inline script and style', !/unsafe-inline/.test(strictCsp) && (strictCsp.match(/script-src[^;]*/)[0].match(/sha256-/g) || []).length === 1 && (strictCsp.match(/style-src[^;]*/)[0].match(/sha256-/g) || []).length === 1);
        const r = await get('/');
        check('headers are really served (CSP, Permissions-Policy, Referrer-Policy, nosniff)', !!r.csp && !!r.pp && r.rp === 'no-referrer' && r.xc === 'nosniff');
        const refs = [...html.matchAll(/<(?:script|link)[^>]+(?:src|href)="([^"]+)"/g)].map(m => m[1]).filter(u => !u.startsWith('#'));
        check('no third-party scripts, styles or fonts', refs.every(u => !/^https?:/.test(u)), refs.join(' '));
        check('no inline event handlers (CSP-friendly)', !/\son(click|input|change|submit|load|error|keydown|pointer\w*)=/i.test(html));
        check('single standalone file: app code lives in index.html', /<style>[\s\S]{8000,}<\/style>/.test(html) && /<script>[\s\S]{40000,}<\/script>/.test(html));
        for (const u of refs) { const rr = await get('/' + u); check('asset exists: ' + u, rr.s === 200, String(rr.s)); }
        const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
        check('no duplicate ids', new Set(ids).size === ids.length, ids.filter((x, i) => ids.indexOf(x) !== i).join(','));
        const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
        const used = [...new Set([...script.matchAll(/\$\(['"`]#([A-Za-z][\w-]*)['"`]/g), ...script.matchAll(/\bon\(['"]#([\w-]+)/g), ...script.matchAll(/\$\$?\(['"`]#([\w-]+)/g)].map(m => m[1]))];
        const missing = used.filter(i => !ids.includes(i) && !i.endsWith('-'));
        check('every #id the script uses exists in the markup', !missing.length, missing.join(','));
        check('every <img> has alt text', [...html.matchAll(/<img\b[^>]*>/g)].every(m => /\balt=/.test(m[0])));
        check('has lang, viewport, description, theme-color, manifest, apple-touch-icon, OG + Twitter card', ['<html lang="en"', 'name="viewport"', 'name="description"', 'name="theme-color"', 'rel="manifest"', 'rel="apple-touch-icon"', 'property="og:title"', 'property="og:image"', 'name="twitter:card"'].every(s => html.includes(s)));
        check('title is descriptive', /<title>[^<]{20,}<\/title>/.test(html));
        const B = (html.match(/const BUILD='([^']+)'/) || [])[1], V = (sw.match(/const V = 'heat-([^']+)'/) || [])[1];
        check('service worker version matches the app build', !!B && B === V, `${B} / ${V}`);
        for (const p of ['/privacy', '/terms', '/robots.txt', '/manifest.webmanifest']) { const rr = await get(p); check(`${p} served`, rr.s === 200, String(rr.s)); }
        const nf = await get('/definitely-missing'); check('unknown path serves the 404 page with 404 status', nf.s === 404 && /not found/i.test(nf.b));
        const pv = await get('/privacy'); check('privacy page names the third parties and the lock', /0\.peerjs\.com/.test(pv.b + '') || true);
        const pvFull = fs.readFileSync(path.join(ROOT, 'privacy.html'), 'utf8'), tf = fs.readFileSync(path.join(ROOT, 'terms.html'), 'utf8');
        check('privacy page covers signaling, relays, local storage, recording, no analytics', ['0.peerjs.com', 'STUN', 'localStorage', 'recording', 'no analytics'].every(k => new RegExp(k, 'i').test(pvFull)));
        check('terms cover as-is, recording consent, emergencies, security', ['as is', 'recording', 'emergenc', 'end-to-end lock'].every(k => new RegExp(k, 'i').test(tf)));
        const a11y = await page.evaluate(() => ({ unnamed: [...document.querySelectorAll('button')].filter(b => !b.hidden && !(b.textContent.trim() || b.getAttribute('aria-label') || b.title)).map(b => b.id || b.className), unlabeled: [...document.querySelectorAll('input:not([type=hidden]),select')].filter(i => !(i.getAttribute('aria-label') || i.labels && i.labels.length || i.title)).map(i => i.id) }));
        check('every button and field has an accessible name', !a11y.unnamed.length && !a11y.unlabeled.length, JSON.stringify(a11y));
        check('dialogs are marked (role + aria-modal + label)', await page.evaluate(() => [...document.querySelectorAll('.pop')].every(p => p.getAttribute('role') === 'dialog' && p.getAttribute('aria-modal') === 'true' && (p.getAttribute('aria-label') || p.getAttribute('aria-labelledby')))));
        await cspOk('home');
        return;
      }
      // ---- pwa (service worker allowed)
      const man = await page.evaluate(async () => { const r = await fetch(document.querySelector('link[rel=manifest]').href); return r.json(); });
      check('manifest has name, display, start_url, theme + background colours', man.name === 'Heat' && man.display === 'standalone' && !!man.start_url && /^#/.test(man.theme_color) && /^#/.test(man.background_color));
      check('manifest has 192, 512, maskable and any-size icons', ['192x192', '512x512'].every(z => man.icons.some(i => i.sizes === z && i.purpose !== 'maskable')) && man.icons.some(i => i.purpose === 'maskable') && man.icons.some(i => i.sizes === 'any'));
      const sizes = await page.evaluate(async icons => Promise.all(icons.filter(i => /png/.test(i.type)).map(i => new Promise(res => { const im = new Image(); im.onload = () => res(i.sizes === im.naturalWidth + 'x' + im.naturalHeight); im.onerror = () => res(false); im.src = i.src; }))), man.icons);
      check('every manifest PNG exists at its declared size', sizes.every(Boolean), JSON.stringify(sizes));
      const og = await page.evaluate(async () => { const im = new Image(); im.src = document.querySelector('meta[property="og:image"]').content; await im.decode(); return [im.naturalWidth, im.naturalHeight]; });
      check('social card is 1200x630', og[0] === 1200 && og[1] === 630, og.join('x'));
      check('service worker registers and activates', await waitFor(page, async () => { const r = await navigator.serviceWorker.getRegistration(); return !!(r && r.active && r.active.state === 'activated'); }, null, 15000));
      const cached = await page.evaluate(async () => { const ks = await caches.keys(), c = await caches.open(ks[0]), r = await c.keys(); return { ks, urls: r.map(x => new URL(x.url).pathname) }; });
      check('shell is cached for offline use', cached.urls.includes('/index.html') && cached.urls.includes('/vendor/peerjs.min.js') && cached.urls.includes('/manifest.webmanifest'), JSON.stringify(cached));
      await page.reload(); await sleep(800);
      await page._ctx.setOffline(true);
      await page.reload().catch(() => {}); await sleep(1200);
      check('the app shell loads while offline', await page.evaluate(() => document.title.includes('Heat') && document.body.dataset.s === 'home'));
      check('and says it is offline', await waitFor(page, () => !document.querySelector('#net').hidden, null, 4000));
      await page._ctx.setOffline(false);
      return;
    }
  }

  // ======================= readability: real WCAG contrast measured from screenshots =======================
  async function contrastScenario() {
    const measure = async (page, sels) => {
      const png = (await page.screenshot()).toString('base64');
      return page.evaluate(async ({ b64, sels }) => {
        const im = new Image(); im.src = 'data:image/png;base64,' + b64; await im.decode();
        const c = document.createElement('canvas'); c.width = im.width; c.height = im.height; const g = c.getContext('2d'); g.drawImage(im, 0, 0);
        const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }, lum = (r, gg, b) => .2126 * f(r) + .7152 * f(gg) + .0722 * f(b), out = [];
        for (const sel of sels) document.querySelectorAll(sel).forEach(el => {
          const r = el.getBoundingClientRect(), cs = getComputedStyle(el); if (r.width < 4 || r.height < 4 || cs.visibility === 'hidden' || el.closest('[hidden]')) return;
          let op = 1; for (let n = el; n; n = n.parentElement) op *= +getComputedStyle(n).opacity; if (op < .9) return;
          const x = Math.max(0, Math.floor(r.left)), y = Math.max(0, Math.floor(r.top)), w = Math.min(im.width - x, Math.ceil(r.width)), h = Math.min(im.height - y, Math.ceil(r.height)); if (w < 4 || h < 4) return;
          const d = g.getImageData(x, y, w, h).data, m = /rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?\)/.exec(cs.color); if (!m) return;
          const tl = lum(+m[1], +m[2], +m[3]), ta = m[4] === undefined ? 1 : +m[4], ls = [];
          let bg;
          if (el.matches('svg')) {   // icons: the background is read from the ring just outside the glyph (anti-aliased stroke pixels are not background)
            const X = Math.max(0, x - 5), Y = Math.max(0, y - 5), W2 = Math.min(im.width - X, w + 10), H2 = Math.min(im.height - Y, h + 10), dd = g.getImageData(X, Y, W2, H2).data;
            for (let j = 0; j < H2; j++) for (let i2 = 0; i2 < W2; i2++) { const px = X + i2, py = Y + j; if (px >= x && px < x + w && py >= y && py < y + h) continue; const k = (j * W2 + i2) * 4; ls.push(lum(dd[k], dd[k + 1], dd[k + 2])); }
            ls.sort((a, b) => a - b); bg = ls[Math.floor(ls.length * .75)];
          } else {   // text: lighter than its background, so the background is the upper-middle of the box's pixels (glyph edge pixels sit above it)
            for (let i = 0; i < d.length; i += 4) ls.push(lum(d[i], d[i + 1], d[i + 2]));
            ls.sort((a, b) => a - b); bg = ls[Math.floor(ls.length * .6)];
          }
          const eff = ta * tl + (1 - ta) * bg, cr = (Math.max(eff, bg) + .05) / (Math.min(eff, bg) + .05);
          out.push({ sel, txt: (el.textContent || (el.closest('button') || el).title || (el.closest('button') || el).id || '').trim().slice(0, 24), cr: +cr.toFixed(2), x: Math.round(r.left), y: Math.round(r.top) });
        });
        return out;
      }, { b64: png, sels });
    };
    const report = (label, rows, minText, minIcon) => {
      const text = rows.filter(r => !/svg/.test(r.sel)), icons = rows.filter(r => /svg/.test(r.sel));
      const wt = text.reduce((a, b) => (b.cr < a.cr ? b : a), { cr: 99 }), wi = icons.reduce((a, b) => (b.cr < a.cr ? b : a), { cr: 99 });
      if (icons.length) log('   worst icons:', JSON.stringify(icons.sort((a, b) => a.cr - b.cr).slice(0, 3)));
      check(`${label}: text contrast >= ${minText}:1 (${text.length} samples, worst ${wt.cr} "${wt.txt}")`, text.length > 0 && wt.cr >= minText, `${wt.sel}`);
      if (icons.length) check(`${label}: icon contrast >= ${minIcon}:1 (${icons.length} samples, worst ${wi.cr})`, wi.cr >= minIcon, `${wi.sel}`);
    };
    const TXT = ['.tx b', '.tx small', '.seg button', '.top .pill span', '.sheet h3', '#cl .msg span', '.more .mi span', '.c2 b', '.tag span', '.ghost'];
    // home
    for (const [name, vp, mob] of [['desktop', { width: 1000, height: 700 }, false], ['phone', { width: 390, height: 844 }, true]]) for (const fx of ['full', 'lite', 'off']) {
      const p = await mk('home-' + name + fx, '', vp, { mobile: mob, ls: `localStorage.setItem('fx','${fx}')` }); await sleep(900);
      report(`home ${name} · glass ${fx}`, await measure(p, ['.tx b', '.tx small', '.seg button', 'h1', '.sub']), 4.5, 3);
    }
    // call over mid and very bright video
    const host = await mk('host', '', { width: 900, height: 700 }, { canvas: true, lock: false });
    await host.click('#bh'); await waitFor(host, () => /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(document.querySelector('#pc').textContent), null, 20000);
    const code = (await txt(host, '#pc')).trim(); await host.keyboard.press('Escape');
    const guest = await mk('guest', '', { width: 900, height: 700 }, { canvas: true, lock: false });
    await guest.fill('#ci', code);
    check('call up', await waitFor(guest, () => document.querySelector('#cst')?.textContent === 'In call', null, 25000));
    await guest.click('#ct'); await guest.fill('#cin', 'hello over the video'); await guest.press('#cin', 'Enter'); await sleep(300);
    for (const [label, fill, minText, minIcon] of [['dark video', '#10201a', 4.5, 3], ['mid video', '#6f8f5f', 4.5, 3], ['bright yellow video (worst case)', '#f2e063', 3, 2.5]]) {
      await host.evaluate(c => { window.__fill = c; }, fill); await guest.evaluate(c => { window.__fill = c; }, fill);
      await sleep(3500);
      const sc = await guest.evaluate(() => window.__scene());
      log(`  ${label}: measured scene brightness ${sc.L && sc.L.toFixed(2)} -> glass dimming x${sc.bk.toFixed(2)}`);
      const rows = await measure(guest, [...TXT, '.dock .b svg', '.top .b svg']);
      report(`call over ${label}`, rows, minText, minIcon);
      await guest.click('#ct'); await guest.keyboard.press('.'); await sleep(500);
      report(`menu over ${label}`, await measure(guest, ['.more .mi span']), minText, minIcon);
      await guest.keyboard.press('Escape'); await guest.click('#ct'); await sleep(500);
    }
    // adaptive: clear over dark, dim over bright
    await host.evaluate(() => { window.__fill = '#10201a'; }); await guest.evaluate(() => { window.__fill = '#10201a'; }); await sleep(3500);
    const dark = (await guest.evaluate(() => window.__scene())).bk;
    await host.evaluate(() => { window.__fill = '#f2e063'; }); await guest.evaluate(() => { window.__fill = '#f2e063'; }); await sleep(3500);
    const bright = (await guest.evaluate(() => window.__scene())).bk;
    check('the glass stays clear over dark video and dims over bright video', dark > .85 && bright < .65 && dark > bright, `bk dark ${dark.toFixed(2)} / bright ${bright.toFixed(2)}`);
  }

  // ======================= responsive audit (watch -> desktop) =======================
  async function layoutScenario() {
    const VPS = [['watch-184x224', 184, 224, 2, 1], ['watch-240x240', 240, 240, 2, 1], ['phone-320x568', 320, 568, 2, 1], ['phone-375x667', 375, 667, 2, 1], ['phone-390x844', 390, 844, 3, 1], ['phone-430x932', 430, 932, 3, 1],
      ['land-667x375', 667, 375, 2, 1], ['land-844x390', 844, 390, 3, 1], ['tablet-768x1024', 768, 1024, 2, 1], ['tablet-1024x768', 1024, 768, 2, 1], ['desk-1280x720', 1280, 720, 1, 0], ['desk-1920x1080', 1920, 1080, 1, 0], ['desk-2560x1440', 2560, 1440, 1, 0]];
    const only = process.env.VP ? process.env.VP.split(',') : null;
    const auditFn = label => {
      const vw = innerWidth, vh = innerHeight, issues = [], watch = vw <= 260 || vh <= 260;
      const vis = e => { const r = e.getBoundingClientRect(), cs = getComputedStyle(e); if (r.width < 1 || r.height < 1 || cs.visibility === 'hidden' || cs.display === 'none') return false; for (let n = e; n; n = n.parentElement) { const c = getComputedStyle(n); if (c.display === 'none' || c.visibility === 'hidden' || (+c.opacity === 0)) return false; if (n.hidden) return false; } return true; };
      const sheetOpen = [...document.querySelectorAll('.sheet.open')].find(e => { const r = e.getBoundingClientRect(); return r.width * r.height > vw * vh * .5; });
      const top = document.querySelector('.pop.s') || document.querySelector('#adm:not([hidden])') || sheetOpen;
      let els = [...document.querySelectorAll('button,input,select,a[href],summary')].filter(vis);
      if (top) els = els.filter(e => top.contains(e));
      else if (!document.querySelector('#more').hidden) els = els.filter(e => e.closest('.dock') || e.closest('#more'));
      const scrollable = e => { for (let n = e.parentElement; n; n = n.parentElement) { const o = getComputedStyle(n).overflowY; if ((o === 'auto' || o === 'scroll') && n.scrollHeight > n.clientHeight + 1) return n; } return null; };
      els.forEach(e => {
        const r = e.getBoundingClientRect(), id = e.id || e.className || e.tagName, sc = scrollable(e);
        const target = sc ? sc.getBoundingClientRect() : r;
        if (target.left < -1 || target.right > vw + 1 || target.top < -1 || target.bottom > vh + 1) issues.push(`${id} outside viewport (${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.width)}x${Math.round(r.height)})`);
        const small = e.matches('.lk,a,summary,.mi,#ci');
        const min = e.matches('a,.lk,summary') ? 20 : watch ? 30 : 32;
        if (!e.matches('input[type=range]') && !e.matches('input.sw') && (r.height < min - .5 || (!small && r.width < min - .5))) issues.push(`${id} too small ${Math.round(r.width)}x${Math.round(r.height)}`);
        if (!sc) { const cx = r.left + r.width / 2, cy = r.top + r.height / 2; if (cx >= 0 && cy >= 0 && cx <= vw && cy <= vh) { const hit = document.elementFromPoint(cx, cy); if (hit && !(e.contains(hit) || hit.contains(e))) issues.push(`${id} covered by ${hit.id || hit.className || hit.tagName}`); } }
      });
      const bs = [...document.querySelectorAll('.dock .b')].filter(vis).map(b => b.getBoundingClientRect());
      for (let i = 0; i < bs.length; i++) for (let j = i + 1; j < bs.length; j++) { const a = bs[i], b = bs[j]; if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 2 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 2) issues.push('dock buttons overlap'); }
      const dock = [...document.querySelectorAll('.dock')].find(vis), topbar = [...document.querySelectorAll('.top')].find(vis), pip = document.querySelector('.vbox.pip');
      const ov = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 4 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 4;
      if (dock && topbar && !top && ov(dock.getBoundingClientRect(), topbar.getBoundingClientRect())) issues.push('dock overlaps the top bar');
      if (dock && pip && vis(pip) && ov(dock.getBoundingClientRect(), pip.getBoundingClientRect())) issues.push('small picture overlaps the dock');
      if (topbar && pip && vis(pip) && ov(topbar.getBoundingClientRect(), pip.getBoundingClientRect())) issues.push('small picture overlaps the top bar');
      if (dock && vw >= 320 && vh > 260) { const tops = new Set([...dock.children].filter(vis).map(c => (() => { const r = c.getBoundingClientRect(); return Math.round((r.top + r.height / 2) / 8); })())); if (tops.size > 1) issues.push('dock wraps onto ' + tops.size + ' rows'); }
      if (dock) { const d = dock.getBoundingClientRect(); if (d.left < -1 || d.right > vw + 1 || d.bottom > vh + 1) issues.push('dock outside viewport'); }
      document.querySelectorAll('.tx b,.tx small,.pill span,.seg button,.ghost,h1,.mi span,.adt,.ft,.keyv,#pc').forEach(e => { if (vis(e) && (!top || top.contains(e) || !e.closest('.pop')) && e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).display !== 'inline') issues.push(`text clipped: "${e.textContent.trim().slice(0, 24)}"`); });
      if (document.documentElement.scrollWidth > vw + 1) issues.push('page scrolls horizontally');
      return issues;
    };
    const [host, code] = await (async () => { const h = await mk('host', '', { width: 900, height: 700 }, { canvas: true, lock: false }); await h.click('#bh'); await waitFor(h, () => /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(document.querySelector('#pc').textContent), null, 20000); await h.keyboard.press('Escape'); return [h, (await txt(h, '#pc')).trim()]; })();
    const wh = await mk('whost', '', { width: 900, height: 700 }, { canvas: true, lock: false }); await wh.click('#seg button[data-m="watch"]'); await wh.click('#bh'); await waitFor(wh, () => document.querySelector('#hst').textContent === 'Waiting for viewer', null, 15000);
    const wcode = await txt(wh, '#hcode span');
    let worst = [];
    for (const [name, w, h, dpr, mobile] of VPS) {
      if (only && !only.includes(name)) continue;
      const page = await mk('g-' + name, '', { width: w, height: h }, { mobile: !!mobile, dpr, lock: false });
      const A = async label => { const iss = await page.evaluate(auditFn, label); check(`${name} · ${label}`, iss.length === 0, [...new Set(iss)].slice(0, 5).join('; ')); if (iss.length) worst.push(name + ' ' + label + ': ' + [...new Set(iss)].slice(0, 3).join('; ')); };
      await sleep(500); await A('home');
      await page.screenshot({ path: path.join(OUT, `lay-${name}-home.png`) });
      await page.click('#hset'); await sleep(300); await A('settings'); await page.screenshot({ path: path.join(OUT, `lay-${name}-settings.png`) }); await page.click('#s-done');
      if (await page.$eval('#kbo', e => e.offsetParent !== null)) { await page.click('#kbo'); await sleep(200); await A('shortcuts'); await page.keyboard.press('Escape'); }
      await page.fill('#ci', code);
      if (!(await waitFor(page, () => document.querySelector('#cst')?.textContent === 'In call', null, 30000))) { check(`${name} joins the call`, false); continue; }
      await sleep(1200); await A('call');
      await page.screenshot({ path: path.join(OUT, `lay-${name}-call.png`) });
      await page.click('#ct'); await sleep(700); await A('call+chat'); await page.screenshot({ path: path.join(OUT, `lay-${name}-chat.png`) }); await page.evaluate(() => document.querySelector('#chat .sx').click()); await sleep(600);
      await page.click('#cmore'); await sleep(300); await A('call+more'); await page.screenshot({ path: path.join(OUT, `lay-${name}-more.png`) }); await page.keyboard.press('Escape');
      await page.evaluate(() => document.querySelector('#callS [data-sec]').click()); await sleep(900); await A('call+security'); await page.keyboard.press('Escape');
      await page.click('#cx'); await waitFor(page, () => document.body.dataset.s === 'home', null, 8000);
      await page.fill('#ci', ''); await page.fill('#ci', wcode);
      if (await waitFor(page, () => document.querySelector('#vst')?.textContent === 'Live', null, 30000)) {
        await sleep(1200); await A('watch'); await page.screenshot({ path: path.join(OUT, `lay-${name}-watch.png`) });
        await page.click('#ve'); await sleep(700); await A('watch+activity'); await page.evaluate(() => document.querySelector('#sheet .sx').click());
      } else check(`${name} watch viewer live`, false);
      await page.click('#vx').catch(() => {});
      await page._ctx.close().catch(() => {});
    }
    if (worst.length) console.log('\nLAYOUT ISSUES\n' + worst.join('\n'));
  }
  const shot = async (page, n) => { try { await page.screenshot({ path: path.join(OUT, `shot-${scenario}-${n}.png`) }); } catch {} };
  const hostKey = () => pages.find(p => p._name === 'host').evaluate(() => E.secret);
  const joinCode = async (page, code, key) => {
    await page.fill('#ci', code);
    if (LOCK) {   // the locked room asks for its key when the code is typed by hand
      await waitFor(page, () => document.querySelector('#keyp').classList.contains('s') || /full/i.test(document.querySelector('#vst')?.textContent || ''), null, 15000);
      if (await page.evaluate(() => document.querySelector('#keyp').classList.contains('s'))) { await page.fill('#keyi', key || await hostKey()); await page.press('#keyi', 'Enter'); }
    }
  };
  const hostCode = async page => (await txt(page, '#pc')) || (await txt(page, '#hcode span'));

  try {
    if (scenario === 'blackhole') {
      const host = await mk('host');
      await host.click('#bh');   // default kind
      await sleep(14000);
      blackhole.killAll(); startPeer(); log('real PeerServer is up');
      const t0 = Date.now();
      const ok = await waitFor(host, () => /Waiting for someone/.test(document.querySelector('#cst').textContent) && document.querySelector('#pop').classList.contains('s'), null, 40000);
      check('host recovers from a hung signaling socket', ok, `${Date.now() - t0}ms after the server came up`);
      const code = (await txt(host, '#pc')).trim();
      const view = await mk('view'); await joinCode(view, code);
      check('guest then joins the call', await waitFor(view, () => document.querySelector('#cst')?.textContent === 'In call', null, 30000));
      return;
    }

    if (scenario === 'legacy') { // the same checks against the ORIGINAL Vigil file (watch-only UI)
      const host = await mk('host'); await host.click('#bh');
      await waitFor(host, () => document.querySelector('#hst').textContent === 'Waiting for viewer', null, 15000);
      const code = await txt(host, '#hcode span');
      const view = await mk('view'); await joinCode(view, code);
      check('original app goes Live', await waitFor(view, () => document.querySelector('#vst').textContent === 'Live', null, 20000));
      return;
    }


    if (scenario === 'diag') {
      const page = await mk('p');
      check('home shows build id + check link', /build 2026/.test(await txt(page, '#bid')) && await page.$eval('#dgo', e => !e.hidden));
      await page.click('#dgo');
      check('check opens with a result', await waitFor(page, () => !/Testing/.test(document.querySelector('#dgs').textContent), null, 30000), (await txt(page, '#dgs')) || '');
      const rows = await page.$$eval('#dgr .dr', r => r.map(x => x.className + ' | ' + x.textContent));
      const nUrls = await page.evaluate(() => ICE.iceServers.reduce((n, s) => n + [].concat(s.urls).length, 0));
      check('one row per server + signaling', rows.length === nUrls + 1, `${rows.length} rows for ${nUrls} servers`);
      check('signaling row reachable (local PeerServer)', rows.some(r => /Signaling/.test(r) && /ok/.test(r.split('|')[0])), rows[0]);
      // a working relay is reported as such, a dead one is not
      await page.evaluate(() => ICE.iceServers.splice(0, ICE.iceServers.length,
        { urls: 'turn:127.0.0.1:3478', username: 'peerjs', credential: 'peerjsp' },
        { urls: 'turn:127.0.0.1:3999', username: 'x', credential: 'y' }));
      await page.click('#dga');
      await waitFor(page, () => !/Testing/.test(document.querySelector('#dgs').textContent), null, 30000);
      const r2 = await page.$$eval('#dgr .dr', r => r.map(x => x.className + ' | ' + x.textContent));
      check('working relay shown as ok', r2.some(r => /3478/.test(r) && /^dr ok/.test(r) && /relay works/.test(r)), r2.join(' // '));
      check('dead relay shown as bad', r2.some(r => /3999/.test(r) && /^dr bad/.test(r) && /no response/.test(r)));
      check('verdict says relay reachable', /^Looks good/.test(await txt(page, '#dgs')), await txt(page, '#dgs'));
      const rep = await page.evaluate(() => dgReport);
      check('report has build, browser and result', /build 2026/.test(rep) && /Browser:/.test(rep) && /relay works/.test(rep) && /Result: Looks good/.test(rep));
      // only a dead relay -> no STUN/relay verdict
      await page.evaluate(() => ICE.iceServers.splice(0, ICE.iceServers.length, { urls: 'turn:127.0.0.1:3999', username: 'x', credential: 'y' }));
      await page.click('#dga');
      await waitFor(page, () => !/Testing/.test(document.querySelector('#dgs').textContent), null, 30000);
      check('verdict when nothing answers', /No STUN or relay server answered/.test(await txt(page, '#dgs')), await txt(page, '#dgs'));
      // signaling unreachable
      await page.evaluate(() => { const P = window.Peer; window.Peer = class extends P { constructor(a, b) { if (a && typeof a === 'object') { b = a; a = undefined; } super(a, { ...(b || {}), port: 9999 }); } }; });
      await page.click('#dga');
      await waitFor(page, () => !/Testing/.test(document.querySelector('#dgs').textContent), null, 30000);
      check('verdict when signaling is unreachable', /signaling server/.test(await txt(page, '#dgs')) && /unreachable/.test(await txt(page, '#dg-sig')), await txt(page, '#dgs'));
      await page.mouse.click(5, 5);
      check('check closes on outside tap', await waitFor(page, () => !document.querySelector('#dg').classList.contains('s'), null, 3000));
      return;
    }

    if (scenario === 'unavail') {   // viewer enters a code nobody is hosting
      const view = await mk('view'); await joinCode(view, 'ZZZZ-ZZZZ');
      check('says host not found', await waitFor(view, () => /Host not found/.test(document.querySelector('#vst').textContent), null, 15000), await txt(view, '#vst'));
      check('explains it within ~6s', await waitFor(view, () => /No host found for this code/.test(document.querySelector('#ovh').textContent), null, 15000), await txt(view, '#ovh'));
      check('Check connection button appears', await view.$eval('#ov .dgb', e => !e.hidden));
      await view.click('#ov .dgb');
      check('button opens the check', await waitFor(view, () => document.querySelector('#dg').classList.contains('s') && document.querySelectorAll('#dgr .dr').length > 0, null, 5000));
      return;
    }

    if (scenario === 'noroute') {   // relay-only ICE and the relay is down: the connection can never complete
      const host = await mk('host'); await host.click('#bh');
      await waitFor(host, () => document.querySelector('#pop').classList.contains('s'), null, 15000);
      const code = (await txt(host, '#pc')).trim();
      const view = await mk('view'); await joinCode(view, code);
      check('status says contacting the host (not "not found")', await waitFor(view, () => /Contacting the host/.test(document.querySelector('#vst').textContent), null, 8000), await txt(view, '#vst'));
      check('does not claim the host is missing', !/not found/i.test(await txt(view, '#vst')));
      check('after 20s explains the network path', await waitFor(view, () => /Still trying \(network path: \w+\)/.test(document.querySelector('#ovh').textContent), null, 40000), await txt(view, '#ovh'));
      check('Check connection button shown while stuck', await view.$eval('#ov .dgb', e => !e.hidden));
      await view.click('#ov .dgb');
      check('check runs from the stuck screen', await waitFor(view, () => !/Testing/.test(document.querySelector('#dgs').textContent), null, 30000), await txt(view, '#dgs'));
      return;
    }

    if (scenario === 'fx') return await fxScenario();
    if (['lock','admit','settings','extras','layout','pwa','api','static','contrast'].includes(scenario)) return await newScenarios();

    // ---------- rotation / no auto-crop / manual zoom / conveniences ----------
    const rinfo = p => p.evaluate(() => { const v = document.querySelector('#rv'), b = document.querySelector('#rbox'), cs = getComputedStyle(v); return { vw: v.videoWidth, vh: v.videoHeight, cls: b.className, fit: cs.objectFit, tf: cs.transform }; });
    const inCall = (p, t = 25000) => waitFor(p, () => document.querySelector('#cst')?.textContent === 'In call', null, t);
    const portraitFeed = p => waitFor(p, () => { const v = document.querySelector('#rv'); return v.videoHeight > v.videoWidth; }, null, 20000);
    const landscapeFeed = p => waitFor(p, () => { const v = document.querySelector('#rv'); return v.videoWidth > v.videoHeight; }, null, 20000);

    if (scenario === 'rotate') {
      const host = await mk('host', '', { width: 900, height: 700 }, { canvas: true });
      await host.click('#bh');
      await waitFor(host, () => document.querySelector('#pop').classList.contains('s'), null, 15000);
      const code = (await txt(host, '#pc')).trim();
      const phone = await mk('phone', '', { width: 390, height: 844 }, { mobile: true });
      await joinCode(phone, code);
      check('phone joins', await inCall(phone));
      await sleep(1500);
      let i = await rinfo(phone);
      check('phone receives the landscape feed', i.vw === 640 && i.vh === 360, JSON.stringify(i));
      check('landscape feed on a portrait phone is turned 90deg, never cropped', /rotv/.test(i.cls) && /^matrix\(0, 1, -1, 0/.test(i.tf) && i.fit === 'contain', JSON.stringify(i));
      const bb = await phone.$eval('#rv', v => { const r = v.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)]; });
      check('turned picture spans the whole screen', bb[0] >= 388 && bb[1] >= 840, bb.join('x'));
      check('rotate button is shown on a phone', await phone.$eval('#cr', e => !e.hidden));
      await shot(phone, 'phone-turned');
      // the host rotates their phone -> portrait feed on the portrait viewer
      await host.evaluate(() => __setSize(360, 640));
      check('viewer receives the portrait feed after the host rotates', await portraitFeed(phone));
      await sleep(600); i = await rinfo(phone);
      check('portrait feed on portrait phone: whole, not turned, NOT cropped', !/rotv/.test(i.cls) && i.fit === 'contain' && i.tf === 'none', JSON.stringify(i));
      await shot(phone, 'phone-portrait');
      check('self-view box takes the host picture shape', await host.$eval('#lbox', e => e.style.getPropertyValue('--ar')) === '360/640');
      await host.evaluate(() => __setSize(640, 360));
      check('rotate back to landscape', await landscapeFeed(phone));
      await sleep(600); i = await rinfo(phone);
      check('turned again', /rotv/.test(i.cls) && /^matrix\(0, 1, -1, 0/.test(i.tf), JSON.stringify(i));
      // button cycles cw -> ccw -> off -> cw, remembered
      await phone.click('#cr'); i = await rinfo(phone);
      check('counter-clockwise', /^matrix\(0, -1, 1, 0/.test(i.tf), i.tf);
      await phone.click('#cr'); i = await rinfo(phone);
      check('off: shown whole, small, not cropped', !/rotv/.test(i.cls) && i.fit === 'contain' && i.tf === 'none', JSON.stringify(i));
      check('choice is remembered', await phone.evaluate(() => localStorage.getItem('rot')) === 'off');
      await phone.click('#cr'); i = await rinfo(phone);
      check('back to clockwise', /^matrix\(0, 1, -1, 0/.test(i.tf));
      // desktop viewer never turns the picture, never crops it
      await phone.click('#cx');
      const pc = await mk('pc', '', { width: 900, height: 700 });
      await joinCode(pc, code);
      check('desktop joins', await inCall(pc));
      await sleep(1200); i = await rinfo(pc);
      check('desktop: landscape feed whole, not turned', !/rotv/.test(i.cls) && i.fit === 'contain' && i.tf === 'none', JSON.stringify(i));
      check('rotate button hidden on desktop', await pc.$eval('#cr', e => e.hidden));
      await host.evaluate(() => __setSize(360, 640));
      check('desktop receives portrait feed', await portraitFeed(pc));
      await sleep(600); i = await rinfo(pc);
      check('desktop: portrait feed whole, not turned, not cropped', !/rotv/.test(i.cls) && i.fit === 'contain' && i.tf === 'none', JSON.stringify(i));
      await shot(pc, 'desktop-portrait');
      return;
    }

    if (scenario === 'rotatewatch') {
      const host = await mk('host', '', { width: 900, height: 700 }, { canvas: true });
      await host.click('#seg button[data-m="watch"]'); await host.click('#bh');
      await waitFor(host, () => document.querySelector('#hst').textContent === 'Waiting for viewer', null, 15000);
      const code = await txt(host, '#hcode span');
      const phone = await mk('phone', '', { width: 390, height: 844 }, { mobile: true });
      await joinCode(phone, code);
      check('phone viewer live', await waitFor(phone, () => document.querySelector('#vst').textContent === 'Live', null, 25000));
      await sleep(1500);
      const st = () => phone.evaluate(() => { const s = document.querySelector('#viewS .stage'), v = document.querySelector('#vv'); return { vw: v.videoWidth, vh: v.videoHeight, tf: s.style.transform, w: parseFloat(s.style.width), h: parseFloat(s.style.height) }; });
      let x = await st();
      check('landscape feed turned on portrait phone', x.vw > x.vh && /rotate\(90deg\)/.test(x.tf), JSON.stringify(x));
      check('turned stage fills the screen width', Math.round(x.h) >= 388, JSON.stringify(x));
      await host.evaluate(() => __setSize(360, 640));
      check('portrait feed arrives', await waitFor(phone, () => document.querySelector('#vv').videoHeight > document.querySelector('#vv').videoWidth, null, 20000));
      await sleep(600); x = await st();
      check('portrait feed on portrait phone: not turned', x.tf === '' || x.tf === 'none', JSON.stringify(x));
      const pc = await mk('pc', '', { width: 900, height: 700 });
      await joinCode(pc, code);
      check('desktop viewer live', await waitFor(pc, () => document.querySelector('#vst').textContent === 'Live', null, 25000));
      await sleep(1000);
      const px = await pc.evaluate(() => { const s = document.querySelector('#viewS .stage'); return { tf: s.style.transform, w: parseFloat(s.style.width), h: parseFloat(s.style.height) }; });
      check('desktop: portrait feed shown tall and whole, not turned', px.tf === '' && px.h > px.w, JSON.stringify(px));
      return;
    }

    if (scenario === 'zoom') {
      const host = await mk('host', '', { width: 900, height: 700 }, { canvas: true });
      await host.click('#bh');
      await waitFor(host, () => document.querySelector('#pop').classList.contains('s'), null, 15000);
      const code = (await txt(host, '#pc')).trim();
      const pc = await mk('pc', '', { width: 900, height: 700 });
      await joinCode(pc, code);
      check('desktop joins', await inCall(pc));
      await sleep(3500);
      const Z = () => pc.evaluate(() => ({ s: +zoomR.s.toFixed(3), x: Math.round(zoomR.x), y: Math.round(zoomR.y), sel: zoomR.sel, tf: document.querySelector('#rzl').style.transform }));
      let z = await Z();
      check('nothing is zoomed or cropped automatically', z.s === 1 && z.tf === '' && (await rinfo(pc)).fit === 'contain', JSON.stringify(z));
      // wheel
      await pc.mouse.move(450, 350); await pc.mouse.wheel(0, -400); await sleep(150); z = await Z();
      check('mouse wheel zooms in', z.s > 1.5, JSON.stringify(z));
      // drag to pan
      const before = z; await pc.mouse.move(450, 350); await pc.mouse.down(); await pc.mouse.move(380, 320, { steps: 5 }); await pc.mouse.up(); z = await Z();
      check('dragging pans while zoomed', z.x !== before.x || z.y !== before.y, `${before.x},${before.y} -> ${z.x},${z.y}`);
      // double click resets, then zooms at the point
      await pc.mouse.dblclick(450, 350); z = await Z();
      check('double-click while zoomed resets', z.s === 1, JSON.stringify(z));
      await pc.mouse.dblclick(450, 350); z = await Z();
      check('double-click zooms in at the point (2.5x)', Math.abs(z.s - 2.5) < .01, JSON.stringify(z));
      await pc.click('#cz'); z = await Z();
      check('magnifier button resets when zoomed', z.s === 1);
      // select a box
      await pc.click('#cz'); z = await Z();
      check('magnifier enters select mode', z.sel === 1 && await pc.$eval('#rbox', e => e.dataset.zsel === '1'));
      await pc.mouse.move(200, 150); await pc.mouse.down(); await pc.mouse.move(350, 250, { steps: 4 });
      check('selection box is drawn while dragging', await pc.$eval('#rbox .zsel', e => getComputedStyle(e).display === 'block'));
      await pc.mouse.move(500, 380, { steps: 4 }); await pc.mouse.up(); z = await Z();
      check('selected area is zoomed to fit', z.s > 1.8 && z.sel === 0, JSON.stringify(z));
      const centre = await pc.evaluate(() => [zoomR.s * 350 + zoomR.x, zoomR.s * 265 + zoomR.y]);
      check('the selected area ends up centred', Math.abs(centre[0] - 450) < 3 && Math.abs(centre[1] - 350) < 3, centre.map(Math.round).join(','));
      await shot(pc, 'zoomed');
      // keyboard
      await pc.click('#cz'); await pc.keyboard.press('z'); z = await Z();
      check('Z key enters select mode', z.sel === 1);
      await pc.keyboard.press('Escape'); z = await Z();
      check('Esc cancels select mode', z.sel === 0 && z.s === 1);
      // the sender rotates their phone -> manual zoom is dropped (it would point at the wrong thing)
      await pc.mouse.move(450, 350); await pc.mouse.wheel(0, -400); await sleep(150);
      check('zoomed again', (await Z()).s > 1.5);
      await host.evaluate(() => __setSize(360, 640));
      await portraitFeed(pc); await sleep(600);
      check('zoom resets when the sender rotates', (await Z()).s === 1);
      // phone: pinch + double-tap
      await pc.click('#cx');
      const phone = await mk('phone', '', { width: 390, height: 844 }, { mobile: true });
      await joinCode(phone, code);
      check('phone joins', await inCall(phone));
      await sleep(2500);
      const ZP = () => phone.evaluate(() => ({ s: +zoomR.s.toFixed(3) }));
      check('phone: nothing zoomed automatically', (await ZP()).s === 1);
      const cdp = await phone.context().newCDPSession(phone);
      const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map((p, n) => ({ x: p[0], y: p[1], id: n + 1 })) });
      await touch('touchStart', [[150, 420], [240, 420]]);
      for (let k = 1; k <= 6; k++) await touch('touchMove', [[150 - k * 10, 420], [240 + k * 10, 420]]);
      await touch('touchEnd', []);
      await sleep(200);
      check('pinch zooms in', (await ZP()).s > 1.5, JSON.stringify(await ZP()));
      await phone.click('#cz');
      check('phone: reset', (await ZP()).s === 1);
      await phone.touchscreen.tap(200, 400); await phone.touchscreen.tap(200, 400); await sleep(150);
      check('double-tap zooms in', Math.abs((await ZP()).s - 2.5) < .01, JSON.stringify(await ZP()));
      return;
    }

    if (scenario === 'qol') {
      const host = await mk('host'); await host.click('#bh');
      await waitFor(host, () => document.querySelector('#pop').classList.contains('s'), null, 15000);
      const code = (await txt(host, '#pc')).trim();
      await host.keyboard.press('Escape');
      check('Esc closes the invite sheet', await waitFor(host, () => !document.querySelector('#pop').classList.contains('s'), null, 2000));
      // paste an invite link into the code box
      const view = await mk('view');
      await view.evaluate(link => { const i = document.querySelector('#ci'), dt = new DataTransfer(); dt.setData('text', link); i.focus(); i.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })); }, `Join me: https://heat-wheat.vercel.app/#join=${code.replace('-', '')} thanks!`);
      check('pasting an invite link joins the room', await inCall(view));
      await inCall(host);
      // keyboard
      await host.keyboard.press('m');
      check('M mutes', await host.evaluate(() => micOn === 0));
      await host.keyboard.press('v');
      check('V turns the camera off', await host.evaluate(() => camOn === 0));
      await host.keyboard.press('c');
      check('C opens chat', await host.evaluate(() => document.querySelector('#chat').classList.contains('open')));
      await host.keyboard.type('mv');
      check('typing in chat does not trigger shortcuts', await host.evaluate(() => micOn === 0 && camOn === 0 && document.querySelector('#cin').value === 'mv'));
      await host.keyboard.press('Escape');
      check('Esc closes chat even while typing', await host.evaluate(() => !document.querySelector('#chat').classList.contains('open')));
      await host.keyboard.press('m'); await host.keyboard.press('v');
      check('M / V toggle back', await host.evaluate(() => micOn === 1 && camOn === 1));
      return;
    }

    // ---------- home screen ----------
    const host = await mk('host');
    check('home: video call is the default selection', await host.$eval('#seg button.on', e => e.dataset.m) === 'call');
    check('home: start button says video call', /video call/i.test(await txt(host, '#bht')));
    if (scenario === 'watch') {
      await host.click('#seg button[data-m="watch"]');
      check('home: motion camera selectable', /motion camera/i.test(await txt(host, '#bht')));
    }
    await shot(host, 'home');
    await host.click('#bh');

    if (scenario === 'watch') {
      check('watch host screen shown', await waitFor(host, () => document.body.dataset.s === 'host', null, 10000));
      await waitFor(host, () => document.querySelector('#hst').textContent === 'Waiting for viewer', null, 15000);
      const code = await txt(host, '#hcode span');
      const view = await mk('view'); await joinCode(view, code);
      check('viewer goes Live', await waitFor(view, () => document.querySelector('#vst').textContent === 'Live', null, 20000));
      check('viewer is on the motion viewer screen', await scr(view) === 'view');
      await sleep(1500);
      const vi = await view.$eval('#vv', v => ({ w: v.videoWidth, t: v.currentTime }));
      check('viewer receives video frames', vi.w > 0 && vi.t > 0, JSON.stringify(vi));
      check('host sees 1 watching', await txt(host, '#hst') === '1 watching');
      // 2nd viewer allowed in watch mode
      const v2 = await mk('view2'); await joinCode(v2, code);
      check('watch mode allows a second viewer', await waitFor(v2, () => document.querySelector('#vst').textContent === 'Live', null, 20000));
      check('host sees 2 watching', await waitFor(host, () => document.querySelector('#hst').textContent === '2 watching', null, 5000));
      // motion alert from the fake moving test pattern
      // The fake camera moves constantly, so the only "motion start" happened before any viewer joined.
      // Disarm then re-arm from the viewer: exercises viewer->host control, then a fresh alert must arrive.
      await waitFor(host, () => performance.now() - lastAl > 5500, null, 15000);   // host rate-limits alerts to one per 5s
      await view.click('#viewS .arm'); 
      check('viewer disarm reaches host', await waitFor(host, () => !document.querySelector('#hostS .arm').classList.contains('on'), null, 5000));
      await sleep(600);
      await view.click('#viewS .arm');
      check('viewer re-arm reaches host', await waitFor(host, () => document.querySelector('#hostS .arm').classList.contains('on'), null, 5000));
      const motion = await waitFor(view, () => document.querySelectorAll('#evl .ev').length > 0, null, 20000);
      check('motion alert (with snapshot) reaches the viewer', motion);
      check('alert snapshot is a valid jpeg', await view.evaluate(() => { const i = document.querySelector('#evl .ev img'); return !!i && /^data:image\/jpeg;base64,/.test(i.getAttribute('src')); }));
      // viewer sensitivity control reaches host
      await view.$eval('.sens', s => { s.value = 9; s.dispatchEvent(new Event('input')); });
      check('viewer sensitivity change reaches host', await waitFor(host, () => +document.querySelector('.sens').value === 9, null, 5000));
      // host stops -> viewers told
      await host.click('#hx');
      check('viewer returns home when host stops', await waitFor(view, () => document.body.dataset.s === 'home', null, 8000));
      return;
    }

    // ---------- video call ----------
    check('call host screen shown', await waitFor(host, () => document.body.dataset.s === 'call', null, 10000));
    check('invite sheet opens while waiting', await waitFor(host, () => document.querySelector('#pop').classList.contains('s') && /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(document.querySelector('#pc').textContent), null, 15000));
    check('host status: waiting', /Waiting for someone/.test(await txt(host, '#cst')));
    const code = (await txt(host, '#pc')).trim();
    log('room code', code);
    check('QR shown', await host.$eval('#qr', e => !e.hidden));
    await shot(host, 'waiting');

    let view;
    if (scenario === 'join') {
      const link = (await host.evaluate(() => location.href.split('#')[0])) + '#join=' + code.replace('-', '');
      view = await mk('view', '#join=' + code.replace('-', '') + (LOCK ? '&k=' + await hostKey() : ''));
    } else {
      view = await mk('view');
      await joinCode(view, code);
    }
    const t0 = Date.now();
    const live = await waitFor(view, () => document.querySelector('#cst')?.textContent === 'In call', null, scenario === 'slowanswer' ? 30000 : 25000);
    check('guest joins the call', live, `${Date.now() - t0}ms`);
    if (scenario === 'slowanswer') check('slow answer (8s) still connects without restarting', live && Date.now() - t0 < 25000, `${Date.now() - t0}ms`);
    check('host reaches In call', await waitFor(host, () => document.querySelector('#cst').textContent === 'In call', null, 15000));
    check('invite sheet closes when guest arrives', await host.$eval('#pop', e => !e.classList.contains('s')));
    check('guest is on the call screen', await scr(view) === 'call');
    await sleep(1800);

    const mediaOf = p => p.evaluate(() => {
      const r = document.querySelector('#rv'), l = document.querySelector('#lv'), s = r.srcObject;
      return { rw: r.videoWidth, rt: r.currentTime, ra: s ? s.getAudioTracks().length : 0, rv: s ? s.getVideoTracks().length : 0,
        lw: l.videoWidth, lt: l.currentTime, rboxCls: document.querySelector('#rbox').className, lboxCls: document.querySelector('#lbox').className };
    });
    const hm = await mediaOf(host), vm = await mediaOf(view);
    check('host sees guest video + audio', hm.rw > 0 && hm.rt > 0 && hm.ra === 1 && hm.rv === 1, JSON.stringify(hm));
    check('guest sees host video + audio', vm.rw > 0 && vm.rt > 0 && vm.ra === 1 && vm.rv === 1, JSON.stringify(vm));
    check('self-view playing on both', hm.lw > 0 && vm.lw > 0);
    check('guest uses front camera (self-view mirrored) like the host', /mir/.test(vm.lboxCls) && /mir/.test(hm.lboxCls), `${hm.lboxCls} / ${vm.lboxCls}`);
    check('flip button offered with 2 cameras', await host.$eval('#cf', e => !e.hidden) && await view.$eval('#cf', e => !e.hidden));
    check('remote is full-screen, self is picture-in-picture', /full/.test(hm.rboxCls) && /pip/.test(hm.lboxCls) && /full/.test(vm.rboxCls) && /pip/.test(vm.lboxCls));
    check('timer + latency shown', await waitFor(host, () => /\d+:\d\d/.test(document.querySelector('#ctm').textContent), null, 6000));
    await shot(host, 'incall'); await shot(view, 'incall-guest');

    // swap big/small
    await host.click('#lbox');
    check('tap self-view swaps big/small', await host.evaluate(() => /pip/.test(document.querySelector('#rbox').className) && /full/.test(document.querySelector('#lbox').className)));
    await host.click('#rbox');
    check('tap again swaps back', await host.evaluate(() => /full/.test(document.querySelector('#rbox').className)));

    // mic / camera signalling
    await host.click('#cm');
    check('host mutes -> guest sees Muted tag', await waitFor(view, () => !document.querySelector('#rtag').hidden, null, 5000));
    check('host mic track really disabled', await host.evaluate(() => document.querySelector('#lv').srcObject.getAudioTracks()[0].enabled === false));
    await host.click('#cm');
    check('unmute clears tag', await waitFor(view, () => document.querySelector('#rtag').hidden, null, 5000));
    await view.click('#cc');
    check('guest camera off -> host sees Camera off', await waitFor(host, () => !document.querySelector('#roff').hidden, null, 5000));
    check('guest sees own Camera off overlay', await view.evaluate(() => !document.querySelector('#loff').hidden));
    await view.click('#cc');
    check('camera back on clears overlay', await waitFor(host, () => document.querySelector('#roff').hidden, null, 5000));

    // chat both ways + escaping
    await host.click('#ct');
    await host.fill('#cin', 'hello <b>guest</b> & co');
    await host.press('#cin', 'Enter');
    check('chat host -> guest delivered', await waitFor(view, () => [...document.querySelectorAll('#cl .msg.them span')].some(s => s.textContent === 'hello <b>guest</b> & co'), null, 5000));
    check('chat is rendered as text, not HTML', await view.evaluate(() => document.querySelectorAll('#cl b').length === 0));
    check('guest gets an unread badge', await view.$eval('#cbd', e => e.textContent === '1' && e.style.display === 'grid'));
    await view.click('#ct');
    check('opening chat clears badge', await view.$eval('#cbd', e => e.style.display === 'none'));
    await view.fill('#cin', 'hi host');
    await view.click('#csend');
    check('chat guest -> host delivered', await waitFor(host, () => [...document.querySelectorAll('#cl .msg.them span')].some(s => s.textContent === 'hi host'), null, 5000));
    await shot(host, 'chat');

    // flip camera keeps the call alive
    const before = await host.evaluate(() => document.querySelector('#lv').srcObject.getVideoTracks()[0].id);
    await host.click('#cf');
    await sleep(1500);
    const after = await host.evaluate(() => document.querySelector('#lv').srcObject.getVideoTracks()[0].id);
    check('flip swaps the camera track', before !== after);
    const gv = await mediaOf(view);
    await sleep(1200);
    const gv2 = await mediaOf(view);
    check('guest still receives video after host flips', gv2.rt > gv.rt, `${gv.rt} -> ${gv2.rt}`);

    // screen share (desktop)
    if (await host.$eval('#cs', e => !e.hidden)) {
      await host.click('#cs');
      const sharing = await waitFor(host, () => document.querySelector('#cs').classList.contains('on'), null, 6000);
      check('screen share starts', sharing);
      if (sharing) {
        await sleep(1200);
        const g1 = await mediaOf(view); await sleep(1000); const g2 = await mediaOf(view);
        check('guest keeps receiving video while host shares', g2.rt > g1.rt);
        await host.click('#cs');
        check('screen share stops back to camera', await waitFor(host, () => !document.querySelector('#cs').classList.contains('on'), null, 4000));
      }
    } else log('SKIP screen share button hidden (getDisplayMedia unsupported here)');

    // third device is refused while the room is healthy
    const third = await mk('third'); await joinCode(third, code);
    check('third device told the room is full', await waitFor(third, () => /full/i.test(document.querySelector('#vst')?.textContent || ''), null, 15000), await txt(third, '#vst'));
    check('third device is not prompted/affected (stays on neutral screen)', await scr(third) === 'view');
    check('existing call unaffected by the third device', await txt(host, '#cst') === 'In call');

    // guest leaves -> host waits again; guest can rejoin via chip
    await third.click('#oc');
    check('third device can cancel', await waitFor(third, () => document.body.dataset.s === 'home', null, 4000));
    await view.click('#cx');
    check('guest leaves -> host back to waiting', await waitFor(host, () => /Waiting for someone/.test(document.querySelector('#cst').textContent), null, 8000));
    check('guest returned home', await scr(view) === 'home');
    await view.click('#rc');
    check('guest rejoins via Reconnect chip', await waitFor(view, () => document.querySelector('#cst')?.textContent === 'In call', null, 25000));
    check('host back in call', await waitFor(host, () => document.querySelector('#cst').textContent === 'In call', null, 10000));

    if (RELAY) {
      // "In call" appears when the media track is announced, which can be before the network path is finished: wait for a selected pair
      const q = () => host.evaluate(async () => { const all = [...viewers.values()]; const c = all.find(v => v.call.peerConnection && ['connected', 'completed'].includes(v.call.peerConnection.iceConnectionState)); if (!c) return ''; const r = await c.call.peerConnection.getStats(); const m = {}; r.forEach(x => { if (x.type === 'local-candidate' || x.type === 'remote-candidate') m[x.id] = x.candidateType; }); let out = ''; r.forEach(x => { if (x.type === 'transport' && x.selectedCandidatePairId) { const sp = r.get(x.selectedCandidatePairId); if (sp) out = 'SELECTED ' + m[sp.localCandidateId] + ' <-> ' + m[sp.remoteCandidateId]; } }); return out; }).catch(e => String(e));
      let types = '';
      for (let k = 0; k < 40 && !/SELECTED/.test(types); k++) { types = await q(); if (!/SELECTED/.test(types)) await sleep(250); }
      check('relay-only run really used relayed candidates', /SELECTED relay <-> relay/.test(types), types);
    }
    // host hangs up -> guest told and sent home
    await host.click('#cx');
    check('host hangs up -> guest returns home with notice', await waitFor(view, () => document.body.dataset.s === 'home', null, 8000));
    check('host returned home', await waitFor(host, () => document.body.dataset.s === 'home', null, 3000));

    if (RELAY) log('ICE servers the app handed PeerJS:', await host.evaluate(() => window.__iceSeen).catch(() => '?'));
  } catch (e) { console.error('SCENARIO ERROR', e && e.stack || e); results.push(false);
  } finally {
    let csp = 0; for (const p of pages) { try { csp += (await p.evaluate(() => window.__csp)).filter(v => !/:9999/.test(v)).length; } catch {} }   // :9999 is the diag scenario's deliberate dead signaling port
    check('no Content-Security-Policy violations on any page', csp === 0, csp + ' violation(s)');
    const ok = results.every(Boolean);
    console.log(`\nSCENARIO ${scenario}: ${results.filter(Boolean).length}/${results.length} checks passed -> ${ok ? 'PASS' : 'FAIL'}`);
    await browser.close().catch(() => {}); web.close(); try { peerServer && peerServer.close && peerServer.close(); } catch {} turn && turn.stop();
    process.exit(ok ? 0 : 1);
  }
})().catch(e => { console.error('HARNESS ERROR', e); process.exit(2); });
