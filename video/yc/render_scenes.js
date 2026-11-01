// Renders the time-driven scenes of demoyc.html to JPEG frames: node render_scenes.js <sched.json> <outdir> <scene> [part nparts]
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [, , schedPath, outDir, scene, part = '0', nparts = '1'] = process.argv;
  const sched = JSON.parse(fs.readFileSync(schedPath)); const seg = sched.segs.find((s) => s.name === scene);
  const FPS = 30, pad = 0.35;                      // extra tail so the crossfade into the next segment has frames
  const total = Math.round((seg.dur + pad) * FPS);
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1.5 });
  await p.goto('file://' + require('path').resolve(__dirname, '../demoyc.html'));
  await p.evaluate((cfg) => { window.CFG = cfg; }, Object.fromEntries(sched.segs.filter((s) => s.kind === 'new').map((s) => [s.name, s.dur])));
  fs.mkdirSync(outDir, { recursive: true });
  for (let f = +part; f < total; f += +nparts) {
    await p.evaluate(([n, t]) => window.renderScene(n, Math.min(t, window.CFG[n] - 0.001)), [scene, f / FPS]);
    await p.screenshot({ path: `${outDir}/${String(f).padStart(5, '0')}.jpg`, type: 'jpeg', quality: 93 });
  }
  await b.close();
})();
