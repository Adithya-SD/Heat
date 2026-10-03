// Frame-sealing unit tests part 1: same key decodes, wrong key / unsealed receiver does not; message sealing; key checks.
// isolated test of e2ee.js + e2ee-worker.js with in-page loopback peer connections
const { serve, launch } = require('./lib');
(async () => {
  const web = await serve(8090, { noHeaders: true }), browser = await launch();
  const ctx = await browser.newContext({ permissions: ['camera', 'microphone'] }), page = await ctx.newPage();
  page.on('pageerror', e => console.log('PAGEERROR', e.message));
  page.on('console', m => { if (['error', 'warning'].includes(m.type())) console.log('console.' + m.type(), m.text().slice(0, 200)); });
  await page.goto('http://127.0.0.1:8090/__t.html');
  await page.evaluate(() => { ['v1','v2','v3'].forEach(i => { const v = document.createElement('video'); v.id = i; v.muted = true; v.autoplay = true; document.body.append(v); }); });
  const res = await page.evaluate(async () => {
        await lockWith('ABCDEFGHJKMN');
    const wrong = new Worker('e2ee-worker.js'); wrong.postMessage({ t: 'key', raw: crypto.getRandomValues(new Uint8Array(32)) });
    const cvs = document.createElement('canvas'); cvs.width = 320; cvs.height = 240; const g = cvs.getContext('2d'); let f = 0;
    setInterval(() => { f++; g.fillStyle = '#0a5'; g.fillRect(0, 0, 320, 240); g.fillStyle = '#fc0'; g.beginPath(); g.arc(40 + (f * 5) % 240, 120, 30, 0, 7); g.fill(); }, 50);
    const mic = new AudioContext(), osc = mic.createOscillator(), dst = mic.createMediaStreamDestination(); osc.connect(dst); osc.start();
    const out = {};
    async function pair(name, sendWorker, recvWorker, vid) {
      const a = new RTCPeerConnection(), b = new RTCPeerConnection();
      a.onicecandidate = e => e.candidate && b.addIceCandidate(e.candidate); b.onicecandidate = e => e.candidate && a.addIceCandidate(e.candidate);
      const st = cvs.captureStream(15);
      a.addTrack(st.getVideoTracks()[0], st); a.addTrack(dst.stream.getAudioTracks()[0], dst.stream);
      a.getSenders().forEach(s => { if (sendWorker) s.transform = new RTCRtpScriptTransform(sendWorker, { op: 'enc', id: (1 << 2) | (s.track.kind === 'audio' ? 1 : 2) }); });
      b.ontrack = e => { if (recvWorker) e.receiver.transform = new RTCRtpScriptTransform(recvWorker, { op: 'dec', id: 0 }); const v = document.getElementById(vid); if (e.track.kind === 'video') { v.srcObject = new MediaStream([e.track]); v.play().catch(() => {}); } };
      let off = await a.createOffer(); off = { type: off.type, sdp: sendWorker ? vp8Only(off.sdp) : off.sdp };
      out[name + '_mvideo'] = off.sdp.split('\r\n').filter(l => /^m=video/.test(l))[0];
      await a.setLocalDescription(off); await b.setRemoteDescription(off);
      const ans = await b.createAnswer(); await b.setLocalDescription(ans); await a.setRemoteDescription(ans);
      await new Promise(r => setTimeout(r, 4000));
      const stats = await b.getStats(); let fd = 0, codec = '';
      stats.forEach(s => { if (s.type === 'inbound-rtp' && s.kind === 'video') fd = s.framesDecoded || 0; if (s.type === 'codec' && /video/.test(s.mimeType)) codec = s.mimeType; });
      out[name] = { framesDecoded: fd, codec };
    }
    ensureWorker();
    await pair('same_key', E.worker, E.worker, 'v1');
    await pair('wrong_key', E.worker, wrong, 'v2');
    E.on = 0; await pair('no_transform_on_receiver', E.worker, null, 'v3'); E.on = 1;   // the page-level hook would otherwise open frames automatically
    out.stats = E.ws;
    out.safe = await safetyCode(new RTCPeerConnection());
    out.sealRoundtrip = (await unseal(await seal({ t: 'chat', text: 'hi ✓' }))).text;
    const sp = await seal({ x: 1 }); sp.c[3] ^= 1; out.tamperRejected = (await unseal(sp)) === null;
    out.kc = await kcCheck(await kcMake());
    out.secretSample = newSecret(); out.normKey = normKey('abcd-efgh-jkmn-oo11');
    return out;
  });
  console.log(JSON.stringify(res, null, 1));
  const ok = res.same_key.framesDecoded > 5 && res.wrong_key.framesDecoded === 0 && res.no_transform_on_receiver.framesDecoded === 0 && res.sealRoundtrip === 'hi ✓' && res.tamperRejected && res.kc && res.same_key.codec.includes('VP8');
  console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL');
  await browser.close(); web.close(); process.exit(ok ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
