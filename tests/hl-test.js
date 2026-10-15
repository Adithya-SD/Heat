/* Heat Link protocol tests (Node, no browser): the JS side of the session protocol the Android app speaks.
   Run: node tests/hl-test.js
   The cross-language half (this code against the real Kotlin implementation) is the JUnit test JsInteropTest. */
const assert = require('assert');
const { loadAll } = require('./lib-hl');
const { HL, AVC } = loadAll();

let pass = 0, fail = 0;
async function test(name, fn) {
  try { await fn(); pass++; console.log('  ok  ' + name); }
  catch (e) { fail++; console.log('FAIL  ' + name + '\n      ' + (e && e.stack || e)); }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const until = async (f, ms = 3000) => { const t = Date.now(); while (!f()) { if (Date.now() - t > ms) throw new Error('timed out waiting'); await sleep(5); } };

/* two ends of an in-memory link; `tap` may rewrite or drop what one side sends */
function pair() {
  const a = { inbox: new HL.Inbox(), sent: [] }, b = { inbox: new HL.Inbox(), sent: [] };
  const mk = (from, to) => ({ send(u8) { from.sent.push(u8); if (from.tap) u8 = from.tap(u8); if (u8) to.inbox.push(u8.slice()); }, close() { to.inbox.fail(new Error('peer closed')); from.inbox.fail(new Error('closed')); } });
  a.io = mk(a, b); b.io = mk(b, a);
  return [a, b];
}
const id = n => new Uint8Array(16).fill(n);

async function connect(code = 'ABCDEFGHJKMN', codeB = code, hs = {}) {
  const [c, s] = pair();
  const { group: gc } = await HL.derive(code), { group: gs } = await HL.derive(codeB);
  const log = { c: { control: [], media: [], closed: [] }, s: { control: [], media: [], closed: [] } };
  const handlers = who => ({ control: u => log[who].control.push(HL.json.dec(u)), media: (st, fl, pts, d) => log[who].media.push({ st, fl, pts, d: d.slice() }), closed: r => log[who].closed.push(r) });
  const [hc, hs2] = await Promise.all([HL.dial(c.io, c.inbox, id(1), gc, null).catch(e => e), HL.accept(s.io, s.inbox, id(2), gs).catch(e => e)]);
  const out = { c, s, log, hc, hs: hs2 };
  if (!(hc instanceof Error) && !(hs2 instanceof Error)) {
    out.cs = new HL.Session(c.io, c.inbox, hc, handlers('c')); out.ss = new HL.Session(s.io, s.inbox, hs2, handlers('s'));
    out.ss.start(); out.cs.start();
  }
  return out;
}

(async () => {
  console.log('Heat Link protocol');

  await test('derive: the room id and key match the Kotlin side (shared vectors)', async () => {
    const d = await HL.derive('abcd-efgh-jkmn');
    assert.strictEqual(d.rid, 'hl-4688b129da8518ae58cd');
    assert.strictEqual(HL.hex(d.group.tag), 'e5f1d443861b37f4');
    assert.strictEqual(HL.hex(d.group.raw), '740eed6a33dc695a8bcabb57f8082d3c8ee59623472a88250491d8e454e8ca29');
    await assert.rejects(HL.derive('short'));
  });

  await test('code helpers: normalise and format as typed', () => {
    assert.strictEqual(HL.norm('ab-cd ef:gh jk_mn xyz'), 'ABCDEFGHJKMN');
    assert.strictEqual(HL.fmt('abcdef'), 'ABCD-EF');
    assert.strictEqual(HL.norm('0O1IL'), '');
  });

  await test('handshake: both ends agree on who the other is and exchange control and media', async () => {
    const x = await connect();
    assert.ok(!(x.hc instanceof Error) && !(x.hs instanceof Error));
    assert.deepStrictEqual(Array.from(x.hc.peer), Array.from(id(2)));
    assert.deepStrictEqual(Array.from(x.hs.peer), Array.from(id(1)));
    x.cs.sendControl(HL.json.enc({ t: 'watch', video: true }));
    x.ss.sendControl(HL.json.enc({ t: 'status', armed: false }));
    await until(() => x.log.s.control.length === 1 && x.log.c.control.length === 1);
    assert.strictEqual(x.log.s.control[0].t, 'watch');
    assert.strictEqual(x.log.c.control[0].t, 'status');
    x.cs.close(); x.ss.close();
  });

  await test('control is exactly-once and in order, even when the link repeats a record', async () => {
    const x = await connect();
    for (let i = 1; i <= 20; i++) x.cs.sendControl(HL.json.enc({ t: 'n', i }));
    await until(() => x.log.s.control.length === 20);
    assert.deepStrictEqual(x.log.s.control.map(m => m.i), Array.from({ length: 20 }, (_, i) => i + 1));
    // a retransmission (as after a reconnect) must not deliver anything twice
    x.cs.reliable.resendAll();
    await sleep(150);
    assert.strictEqual(x.log.s.control.length, 20);
    x.cs.close(); x.ss.close();
  });

  await test('media: deltas wait for a keyframe, and a frame still being written is dropped, never queued', async () => {
    const x = await connect();
    assert.strictEqual(x.ss.sendMedia(HL.ST.VIDEO, 0, 1, new Uint8Array(10)), false);        // no keyframe yet
    assert.strictEqual(x.ss.sendMedia(HL.ST.VIDEO, HL.MF_KEY, 2, new Uint8Array(50).fill(7)), true);
    await until(() => x.log.c.media.length === 1);
    assert.strictEqual(x.log.c.media[0].fl, 1);
    assert.strictEqual(x.log.c.media[0].pts, 2);
    assert.strictEqual(x.log.c.media[0].d.length, 50);
    await sleep(20);
    assert.strictEqual(x.ss.sendMedia(HL.ST.VIDEO, 0, 3, new Uint8Array(10)), true);
    await until(() => x.log.c.media.length === 2);
    x.cs.close(); x.ss.close();
  });

  await test('a wrong code never gets in: the camera refuses at the first message', async () => {
    const x = await connect('ABCDEFGHJKMN', 'ABCDEFGHJKMP');
    assert.ok(x.hc instanceof Error && x.hc.auth, 'dialer should see a refusal');
    assert.ok(x.hs instanceof Error && x.hs.auth, 'listener should see a stranger');
  });

  await test('a record altered on the wire kills the session instead of being believed', async () => {
    const x = await connect();
    x.c.tap = u8 => { const t = u8.slice(); t[t.length - 1] ^= 1; return t; };      // from now on every record from the dialer is altered
    x.cs.sendControl(HL.json.enc({ t: 'arm', armed: true }));
    await until(() => x.log.s.closed.length === 1);
    assert.strictEqual(x.log.s.control.length, 0);
  });

  await test('liveness: a ping goes out and its answer gives a round trip', async () => {
    const x = await connect();
    await until(() => x.cs.rtt >= 0, 4000);
    assert.ok(x.cs.rtt >= 0 && x.cs.rtt < 500);
    x.cs.close(); x.ss.close();
  });

  await test('generated codes are 12 valid symbols, all different, all derivable', async () => {
    const seen = new Set();
    for (let i = 0; i < 300; i++) { const c = HL.newCode(); assert.strictEqual(c.length, 12); assert.strictEqual(HL.norm(c), c); seen.add(c); }
    assert.ok(seen.size > 295);
    await HL.derive(HL.newCode());
  });

  await test('a peer that hangs up before READY is a refusal (wrong key), not a bad link', async () => {
    const [c, s] = pair();
    const hs = { peer: id(2), awaitReady: true, tx: new HL.Aead(new Uint8Array(32), new Uint8Array(4)), rx: new HL.Aead(new Uint8Array(32), new Uint8Array(4)) };
    const closed = [];
    const sess = new HL.Session(c.io, c.inbox, hs, { closed: r => closed.push(r) });
    sess.start();
    s.io.close();                                  // the other end just disappears
    await until(() => closed.length === 1);
    assert.strictEqual(closed[0], 'REJECTED');
  });

  const annexb = (...parts) => HL.cat(...parts.map(p => HL.cat(p.long ? Uint8Array.of(0, 0, 0, 1) : Uint8Array.of(0, 0, 1), Uint8Array.from(p.b))));
  const SPS = [0x67, 0x42, 0xc0, 0x1f, 0x8c, 0x68], PPS = [0x68, 0xce, 0x3c, 0x80], IDR = [0x65, 0x88, 0x84, 0x00, 0x33], P = [0x41, 0x9a, 0x02, 0x11];

  await test('h264: Annex-B is split into NAL units whatever the start code length', () => {
    const list = AVC.nals(annexb({ b: SPS, long: 1 }, { b: PPS }, { b: IDR, long: 1 }));
    assert.deepStrictEqual(Array.from(list, n => AVC.type(n)), [7, 8, 5]);
    assert.deepStrictEqual(Array.from(list[0]), SPS);
    assert.deepStrictEqual(Array.from(list[2]), IDR);
    assert.ok(AVC.isKey(list));
    assert.ok(!AVC.isKey(AVC.nals(annexb({ b: P, long: 1 }))));
    assert.strictEqual(AVC.nals(new Uint8Array(0)).length, 0);
  });

  await test('h264: codec string, avcC and length-prefixed slices are what WebCodecs expects', () => {
    assert.strictEqual(AVC.codec(Uint8Array.from(SPS)), 'avc1.42c01f');
    const c = AVC.avcC(Uint8Array.from(SPS), Uint8Array.from(PPS));
    assert.strictEqual(c.length, 11 + SPS.length + PPS.length);
    const back = AVC.parseAvcC(c);
    assert.deepStrictEqual(Array.from(back.sps[0]), SPS);
    assert.deepStrictEqual(Array.from(back.pps[0]), PPS);
    assert.strictEqual(back.len, 4);
    const data = AVC.toAvcc(AVC.nals(annexb({ b: SPS, long: 1 }, { b: PPS, long: 1 }, { b: IDR, long: 1 })));
    assert.deepStrictEqual(Array.from(data), [0, 0, 0, IDR.length, ...IDR]);       // only the slice: parameter sets are in the description
  });

  await test('h264: an encoder that gives length-prefixed output converts back to Annex-B, parameter sets first on a keyframe', () => {
    const c = AVC.avcC(Uint8Array.from(SPS), Uint8Array.from(PPS));
    const key = AVC.nals(AVC.fromAvcc(Uint8Array.from([0, 0, 0, IDR.length, ...IDR]), c, true));
    assert.deepStrictEqual(Array.from(key, n => AVC.type(n)), [7, 8, 5]);
    const delta = AVC.nals(AVC.fromAvcc(Uint8Array.from([0, 0, 0, P.length, ...P]), c, false));
    assert.deepStrictEqual(Array.from(delta, n => AVC.type(n)), [1]);
  });

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})();
