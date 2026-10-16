// 파일 불러오기 테스트. 사용법: node import-test.js <파일> <출력 png>
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1100, height: 1400 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + path.resolve(__dirname, '../geulbang.html'));
  const file = process.argv[2];
  const b64 = fs.readFileSync(file).toString('base64');
  const res = await page.evaluate(async ([b64, name]) => {
    try {
      const f = new File([base64ToBytes(b64)], name);
      await FileOps.load(f, null);
      return 'ok';
    } catch (e) { return 'ERR ' + e.message + '\n' + e.stack; }
  }, [b64, path.basename(file)]);
  console.log(res);
  const html = await page.evaluate(() => App.editor.innerHTML);
  console.log(html.replace(/base64,[A-Za-z0-9+/=]{20,}/g, 'base64,...'));
  await page.screenshot({ path: process.argv[3], fullPage: true });
  console.log('errors:', JSON.stringify(errors));
  await browser.close();
})();
