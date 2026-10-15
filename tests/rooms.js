// Rooms end to end, with real typing and real clicks (no scripted shortcuts):
//   A makes a room, B joins it from the code box and chooses HOST, C joins it and chooses RECEIVER.
//   Expect: B is a ready host, C sees both hosts connected, and B can switch to receiver and back.
// usage: node rooms.js           CHROMIUM=/path/to/chrome overrides the browser (the system Chrome is used if found)
// Uses the public PeerJS signaling server, like the real site, so it needs the internet.
const fs = require('fs');
const CHROME = process.env.CHROMIUM || ['C:/Program Files/Google/Chrome/Application/chrome.exe', '/usr/bin/google-chrome', '/usr/bin/chromium'].find(p => fs.existsSync(p));
if (CHROME) process.env.CHROMIUM = CHROME;      // before lib.js reads it
const { serve, launch } = require('./lib');

const PORT = 8091;

let failed = 0;
const check = (name, ok, extra) => { console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok ? '' : '  ' + (extra || ''))); if (!ok) failed++; };
const until = async (fn, ms, step = 400) => { const t = Date.now(); while (Date.now() - t < ms) { try { const v = await fn(); if (v) return v; } catch {} await new Promise(r => setTimeout(r, step)); } return null; };

(async () => {
  const server = await serve(PORT);
  const browser = await launch();
  try {
    const open = async () => {
      const ctx = await browser.newContext({ permissions: ['camera', 'microphone'] });
      const page = await ctx.newPage();
      page.on('pageerror', e => { console.log('PAGEERROR ' + e.message); failed++; });
      await page.goto(`http://127.0.0.1:${PORT}/index.html`);
      await page.evaluate(() => { localStorage.clear(); localStorage.setItem('km', 'link'); });
      await page.reload();
      await page.waitForSelector('#bh:not([hidden])');
      return page;
    };

    // A: start a room of its own
    const A = await open();
    await A.click('#bh');
    await A.waitForFunction(() => document.body.dataset.s === 'lkcam', null, { timeout: 20000 });
    await A.waitForSelector('#pop.s'); const code = (await A.textContent('#pc')).trim();
    await A.evaluate(() => document.querySelector('#pop').classList.remove('s'));
    check('A: made a room with a code', /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(code), code);
    check('A: is a ready host', !!(await until(() => A.evaluate(() => /Ready/.test(document.querySelector('#lkcst').textContent)), 20000)));

    // B: type the code, get asked, choose Host with a real click
    const B = await open();
    await B.click('#lki'); await B.keyboard.type(code.replace('-', ''), { delay: 40 });
    check('B: is asked host or receiver', !!(await until(() => B.evaluate(() => document.querySelector('#rolep').classList.contains('s')), 3000)));
    await B.click('#role-host');
    check('B: joined as a host', !!(await B.waitForFunction(() => document.body.dataset.s === 'lkcam', null, { timeout: 20000 }).catch(() => null)));
    check('B: ready in the room', !!(await until(() => B.evaluate(() => /Ready/.test(document.querySelector('#lkcst').textContent)), 30000)),
      await B.evaluate(() => document.querySelector('#lkcst').textContent));

    // C: type the code, choose Receiver with a real click
    const C = await open();
    await C.click('#lki'); await C.keyboard.type(code.replace('-', ''), { delay: 40 });
    await C.click('#role-recv');
    await C.waitForFunction(() => document.body.dataset.s === 'lkview', null, { timeout: 20000 });
    const both = await until(() => C.evaluate(() => { const m = LK.members(); return m.length === 2 && m.every(x => x.sess) ? m.length : 0; }), 60000, 1000);
    check('C: receives from both hosts', !!both, JSON.stringify(await C.evaluate(() => LK.members().map(m => m.slot + ':' + m.sess))));
    check('C: has a Hosts button', await C.evaluate(() => !document.querySelector('#lkhb').hidden));

    // B switches role without leaving the room, and back (the invite sheet that opens for a new host is dismissed first, as a person would)
    await B.mouse.click(5, 5);
    await until(() => B.evaluate(() => !document.querySelector('#pop').classList.contains('s')), 3000);
    await B.click('#lkcsw');
    check('B: switched to receiver', !!(await B.waitForFunction(() => document.body.dataset.s === 'lkview', null, { timeout: 20000 }).catch(() => null)));
    await B.click('#lkhb'); await B.click('#lkvsw');
    check('B: switched back to host', !!(await B.waitForFunction(() => document.body.dataset.s === 'lkcam', null, { timeout: 20000 }).catch(() => null)));

    // C removes B (two taps). B re-hosted, possibly in another slot, so wait until C is connected to a host other than A.
    const slot = await until(() => C.evaluate(() => (LK.members().find(m => m.slot > 0 && m.sess) || {}).slot), 60000, 1000);
    check('C: is connected to B again', !!slot, JSON.stringify(await C.evaluate(() => LK.members().map(m => m.slot + ':' + m.sess))));
    await C.click('#lkhb').catch(() => {});
    await C.waitForSelector(`[data-rm="${slot}"]`, { timeout: 10000 }).catch(() => {});
    await C.click(`[data-rm="${slot}"]`); await C.click(`[data-rm="${slot}"]`);
    check('B: removed by C (its tab closes itself, or shows the removed screen)', !!(await until(async () => B.isClosed() || await B.evaluate(() => document.body.dataset.s === 'removed'), 25000)));
  } catch (e) { console.log('HARNESS ' + e.message); failed++; }
  await browser.close(); server.close();
  console.log(failed ? 'ROOMS FAILED' : 'ROOMS PASSED');
  process.exit(failed ? 1 : 0);
})();
