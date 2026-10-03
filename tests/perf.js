// Startup cost of the glass: no long main-thread task on desktop or a dense phone (and under a 4x slower CPU, none over 250 ms).
const { serve, launch } = require('./lib'); const sleep = ms => new Promise(r => setTimeout(r, ms));
let ok = true;
(async () => {
  const web = await serve(8095), br = await launch();
  for (const [name, vp, dpr, mobile, cpu] of [['desktop', { width: 1280, height: 720 }, 1, false, 1], ['phone dpr3', { width: 390, height: 844 }, 3, true, 1], ['phone dpr3, 4x slower CPU', { width: 390, height: 844 }, 3, true, 4]]) {
    const ctx = await br.newContext({ viewport: vp, deviceScaleFactor: dpr, isMobile: mobile, hasTouch: mobile, serviceWorkers: 'block' });
    await ctx.addInitScript(`window.__lt=[];new PerformanceObserver(l=>l.getEntries().forEach(e=>window.__lt.push(Math.round(e.duration)))).observe({entryTypes:['longtask']})`);
    const p = await ctx.newPage(); const cdp = await ctx.newCDPSession(p); if (cpu > 1) await cdp.send('Emulation.setCPUThrottlingRate', { rate: cpu });
    const t0 = Date.now(); await p.goto('http://127.0.0.1:8095/'); await p.waitForFunction(() => [...document.querySelectorAll('.glass')].some(e => e.style.backdropFilter.includes('url(')), null, { timeout: 30000 });
    const ready = Date.now() - t0; await sleep(1500);
    const r = await p.evaluate(() => { const nav = performance.getEntriesByType('navigation')[0], fcp = performance.getEntriesByName('first-contentful-paint')[0]; return { dcl: Math.round(nav.domContentLoadedEventEnd), fcp: fcp && Math.round(fcp.startTime), longTasks: window.__lt, filters: document.querySelectorAll('#fd filter').length, html: Math.round(document.documentElement.outerHTML.length / 1024) }; });
    const worst = Math.max(0, ...r.longTasks), limit = cpu > 1 ? 250 : 120; const good = worst <= limit; ok = ok && good;
    console.log(good ? 'PASS' : 'FAIL', name.padEnd(28), `worst task ${worst} ms (limit ${limit})`, JSON.stringify({ lensReadyMs: ready, ...r }));
    await ctx.close();
  }
  console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL'); await br.close(); web.close(); process.exit(ok ? 0 : 1);
})();
