/* Plays the browser's half of Heat Link over a real TCP socket, for the Kotlin interop test (JsInteropTest).
   Not a user tool. Prints one JSON object per line on stdout.

     node hl-interop.js viewer <port> <code>          dial a Kotlin camera host, watch it, change a setting
     node hl-interop.js camera <code> <selfIdHex>     be a camera: listen, accept a Kotlin client, send it video */
const net = require('net');
const { loadHL } = require('./lib-hl');
const HL = loadHL();
const say = o => process.stdout.write(JSON.stringify(o) + '\n');

function wire(sock) {
  const inbox = new HL.Inbox();
  sock.on('data', b => inbox.push(new Uint8Array(b)));
  sock.on('close', () => inbox.fail(new Error('closed')));
  sock.on('error', () => inbox.fail(new Error('error')));
  return { inbox, io: { send: u8 => { if (process.env.HL_TRACE) say({ ev: 'tx', n: u8.length }); sock.write(Buffer.from(u8)); }, close: () => sock.destroy() } };
}

async function viewer(port, code) {
  const d = await HL.derive(code);
  const sock = net.connect(+port, '127.0.0.1');
  const { inbox, io } = wire(sock);
  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  const hs = await HL.dial(io, inbox, HL.rnd(16), d.group, null);
  let frames = 0, key = 0, firstWasKey = null, sawEcho = false;
  const session = new HL.Session(io, inbox, hs, {
    control: u => {
      const m = HL.json.dec(u); if (!m) return;
      if (m.t === 'cfg') { say({ ev: 'cfg', rev: m.rev, ackSeq: m.ackSeq, sensitivity: m.config && m.config.detect && m.config.detect.sensitivity }); if (m.ackSeq === 1 && m.config.detect.sensitivity === 77) sawEcho = true; }
      else say({ ev: m.t, name: m.name, role: m.role, armed: m.armed, width: m.stream && m.stream.width });
    },
    media: (stream, flags, pts, data) => { if (firstWasKey === null) firstWasKey = (flags & 1) === 1; frames++; if (flags & 1) key++; say({ ev: 'video', stream, key: flags & 1, pts, bytes: data.length }); },
    closed: (reason, err) => say({ ev: 'closed', reason, error: err && err.message })
  });
  session.start();
  say({ ev: 'up', peer: HL.hex(hs.peer) });
  session.sendControl(HL.json.enc({ t: 'hello', name: 'Browser', role: 'VIEWER', app: 'web-1', features: [] }));
  session.sendControl(HL.json.enc({ t: 'watch', video: true, audio: false, tier: 'MAX' }));
  session.sendControl(HL.json.enc({ t: 'set', seq: 1, patch: { detect: { sensitivity: 77 } } }));
  session.sendControl(HL.json.enc({ t: 'arm', armed: true, delaySec: 0 }));
  const t0 = Date.now();
  while (Date.now() - t0 < 15000) {
    if (frames >= 4 && sawEcho && firstWasKey) { say({ ev: 'done', frames, key }); session.bye(); setTimeout(() => process.exit(0), 400); return; }
    await new Promise(r => setTimeout(r, 25));
  }
  say({ ev: 'timeout', frames, key, sawEcho, firstWasKey });
  process.exit(1);
}

async function camera(code, selfHex) {
  const d = await HL.derive(code), self = HL.unhex(selfHex);
  const server = net.createServer(async sock => {
    const { inbox, io } = wire(sock);
    try {
      const hs = await HL.accept(io, inbox, self, d.group);
      let rev = 1, sens = 50, armed = false, sending = null, session;
      const cfg = ackSeq => ({ t: 'cfg', rev, ackSeq, config: { label: 'Web camera', detect: { sensitivity: sens } } });
      session = new HL.Session(io, inbox, hs, {
        control: u => {
          const m = HL.json.dec(u); if (!m) return;
          if (m.t === 'hello') {
            session.sendControl(HL.json.enc({ t: 'hello', name: 'Web camera', role: 'CAMERA', app: 'web-1', features: ['h264'] }));
            session.sendControl(HL.json.enc(cfg(0)));
            session.sendControl(HL.json.enc({ t: 'status', armed, stream: { width: 640, height: 480, fps: 30, bitrate: 1000000, codec: 'avc', rotation: 0 } }));
          } else if (m.t === 'watch' && m.video && !sending) {
            let n = 0;
            sending = setInterval(() => { session.sendMedia(HL.ST.VIDEO, n % 15 === 0 ? 1 : 0, n * 33333, new Uint8Array(n % 15 === 0 ? 20000 : 1500).fill(n & 255)); n++; }, 33);
          } else if (m.t === 'set') { sens = (m.patch.detect && m.patch.detect.sensitivity) || sens; rev++; session.sendControl(HL.json.enc(cfg(m.seq))); }
          else if (m.t === 'arm') { armed = !!m.armed; session.sendControl(HL.json.enc({ t: 'status', armed })); }
          else if (m.t === 'key') session.sendMedia(HL.ST.VIDEO, 1, Date.now(), new Uint8Array(20000));
        },
        needKey: () => {},
        closed: r => { clearInterval(sending); say({ ev: 'closed', reason: r }); }
      });
      session.start();
      say({ ev: 'accepted', peer: HL.hex(hs.peer) });
    } catch (e) { say({ ev: 'refused', error: String(e && e.message || e) }); sock.destroy(); }
  });
  server.listen(0, '127.0.0.1', () => say({ ev: 'listening', port: server.address().port }));
}

const [mode, a, b] = process.argv.slice(2);
if (mode === 'viewer') viewer(a, b).catch(e => { say({ ev: 'error', error: String(e && e.message || e) }); process.exit(1); });
else if (mode === 'camera') camera(a, b);
else { console.error('usage: viewer <port> <code> | camera <code> <selfIdHex>'); process.exit(2); }
