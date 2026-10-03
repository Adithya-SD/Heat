// Frame-sealing unit test 2 (module + worker cut out of index.html): a bidirectional sealed call whose answer is late, the way a camera-permission prompt makes it.
// Frame-sealing unit test 2: a bidirectional sealed call whose answer is late (as when a camera-permission prompt delays it), no app, no PeerJS
const { serve, launch } = require('./lib');
let allGood = true;
(async () => {
  const web = await serve(8090, { noHeaders: true }), browser = await launch();
  const ctx = await browser.newContext({ permissions: ['camera', 'microphone'] }), page = await ctx.newPage();
  page.on('pageerror', e => console.log('PAGEERROR', e.message));
  await page.goto('http://127.0.0.1:8090/__t.html');
  await page.evaluate(() => { for (const id of ['va', 'vb']) { const v = document.createElement('video'); v.id = id; v.muted = true; v.autoplay = true; document.body.append(v); } });
  await page.evaluate(() => lockWith('ABCDEFGHJKMN'));
  for (const cfg of [{ delay: 0, withAudio: 1, tune: 0 }, { delay: 300, withAudio: 0, tune: 0 }, { delay: 300, withAudio: 1, tune: 0 }, { delay: 300, withAudio: 1, tune: 1 }, { delay: 2500, withAudio: 1, tune: 1 }]) {
    const res = await page.evaluate(async cfg => {
      const mkStream = (tag) => { const cvs = document.createElement('canvas'); cvs.width = 640; cvs.height = 360; const g = cvs.getContext('2d'); let f = 0; const iv = setInterval(() => { f++; g.fillStyle = tag === 'a' ? '#0a5' : '#a05'; g.fillRect(0, 0, 640, 360); g.fillStyle = '#fc0'; g.beginPath(); g.arc(60 + (f * 7) % 500, 180, 40, 0, 7); g.fill(); }, 60); const s = cvs.captureStream(15);
        if (cfg.withAudio) { const ac = new AudioContext(), o = ac.createOscillator(), d = ac.createMediaStreamDestination(); o.connect(d); o.start(); s.addTrack(d.stream.getAudioTracks()[0]); } return { s, iv }; };
      const A = mkStream('a'), B = mkStream('b'), a = new RTCPeerConnection(), b = new RTCPeerConnection();
      a.onicecandidate = e => e.candidate && setTimeout(() => b.remoteDescription && b.addIceCandidate(e.candidate), 0); b.onicecandidate = e => e.candidate && a.addIceCandidate(e.candidate);
      A.s.getTracks().forEach(t => a.addTrack(t, A.s));
      a.ontrack = e => { if (e.track.kind === 'video') document.getElementById('va').srcObject = new MediaStream([e.track]); };
      b.ontrack = e => { if (e.track.kind === 'video') document.getElementById('vb').srcObject = new MediaStream([e.track]); };
      let off = await a.createOffer(); off = { type: off.type, sdp: vp8Only(off.sdp) }; await a.setLocalDescription(off);
      if (cfg.tune) setTimeout(() => a.getSenders().filter(s => s.track && s.track.kind === 'video').forEach(s => { const p = s.getParameters(); if (!p.encodings.length) p.encodings = [{}]; p.encodings[0].maxBitrate = 3e6; s.setParameters(p).catch(() => {}); }), 1200);
      await new Promise(r => setTimeout(r, cfg.delay));
      B.s.getTracks().forEach(t => b.addTrack(t, B.s));   // PeerJS adds the answerer's tracks BEFORE setRemoteDescription(offer)
      await b.setRemoteDescription(off);
      let ans = await b.createAnswer(); ans = { type: ans.type, sdp: vp8Only(ans.sdp) }; await b.setLocalDescription(ans); await a.setRemoteDescription(ans);
      await new Promise(r => setTimeout(r, 5000));
      const fr = async pc => { let o = 0; (await pc.getStats()).forEach(s => { if (s.type === 'inbound-rtp' && s.kind === 'video') o = s.framesReceived; }); return o; };
      const out = { cfg, A_receives: await fr(a), B_receives: await fr(b), ws: { ...E.ws } };
      clearInterval(A.iv); clearInterval(B.iv); a.close(); b.close(); return out;
    }, cfg);
    const good = res.A_receives > 20 && res.B_receives > 20; allGood = allGood && good;
    console.log(good ? 'PASS' : 'FAIL', JSON.stringify({ ...res.cfg, A_receives: res.A_receives, B_receives: res.B_receives, sealedFramesOpened: res.ws.dec }));
  }
  console.log(allGood ? 'RESULT: PASS' : 'RESULT: FAIL');
  await browser.close(); web.close(); process.exit(allGood ? 0 : 1);
})();
