const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const [, , outDir, part, nparts] = process.argv; const FPS = 30, END = 103.5, total = Math.round(END * FPS);
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1.5 });
  p.on('pageerror', e => console.log('ERR', e.message));
  await p.goto('file://' + require('path').resolve(__dirname, '../film.html')); await p.waitForTimeout(800);
  fs.mkdirSync(outDir, { recursive: true });
  for (let f = +part; f < total; f += +nparts) {
    await p.evaluate((t) => window.renderFrame(t), f / FPS);
    await p.screenshot({ path: `${outDir}/${String(f).padStart(5, '0')}.jpg`, type: 'jpeg', quality: 92 });
  }
  await b.close();
})();
