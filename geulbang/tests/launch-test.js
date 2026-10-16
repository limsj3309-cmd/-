// 실행기가 넘긴 #open=<경로>로 문서 열기 테스트. 사용법: node launch-test.js <docx 파일>
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');
const assert = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok  :', m); };
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'geulbang-'));
  const doc = path.join(dir, '내 문서 #1 (테스트).docx');
  fs.copyFileSync(process.argv[2], doc);
  const url = 'file://' + path.resolve(__dirname, '../geulbang.html') + '#open=' + encodeURIComponent(doc);
  // 실행기와 같은 플래그
  let browser = await chromium.launch({ args: ['--allow-file-access-from-files'] });
  let page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(url);
  await page.waitForFunction(() => App.fileName !== '제목 없음', null, { timeout: 10000 });
  const r = await page.evaluate(() => ({ name: App.fileName, title: document.title, hash: location.hash, text: App.editor.innerText.slice(0, 40), brand: document.querySelector('.brand').textContent }));
  assert(r.name === '내 문서 #1 (테스트).docx' && /샘플 문서/.test(r.text), 'opened document from launch path: ' + JSON.stringify(r));
  assert(r.hash === '' && r.brand === '현서의 글방' && r.title.endsWith(' - 현서의 글방'), 'hash cleared and program name shown');
  await browser.close();
  // 플래그 없이 열면 안내 대화상자
  browser = await chromium.launch();
  page = await browser.newPage();
  await page.goto(url);
  await page.waitForSelector('#overlay:not([hidden])', { timeout: 10000 });
  const msg = await page.textContent('#dialog');
  assert(/열기\] 단추/.test(msg), 'without file access shows guidance: ' + msg);
  await browser.close();
  console.log('errors:', JSON.stringify(errors));
})();
