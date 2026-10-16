const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
(async () => {
  const out = process.argv[2];
  const sample = process.argv[3];
  const browser = await chromium.launch();
  const url = 'file://' + path.resolve(__dirname, '../LiteWord.html');
  const load = async (page) => page.evaluate(async ([b64, name]) => { await FileOps.load(new File([base64ToBytes(b64)], name), null); }, [fs.readFileSync(sample).toString('base64'), 'sample.docx']);
  // 1) 색상 팝업
  let page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page.goto(url); await load(page);
  await page.click('[data-pop="popFore"]');
  await page.screenshot({ path: path.join(out, 'v1-color.png') });
  // 2) 표 팝업(표 안에서)
  await page.keyboard.press('Escape');
  await page.evaluate(() => placeCaret(App.editor.querySelector('td')));
  await page.click('[data-pop="popTable"]');
  await page.hover('#tgrid span[data-r="3"][data-c="4"]');
  await page.screenshot({ path: path.join(out, 'v2-table.png') });
  // 3) 다른 이름으로 저장 대화상자(대체 경로)
  await page.keyboard.press('Escape');
  await page.evaluate(() => { window.showSaveFilePicker = undefined; FileOps.saveAs(); });
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(out, 'v3-saveas.png') });
  await page.close();
  // 4) 다크 모드
  const dark = await browser.newContext({ colorScheme: 'dark', viewport: { width: 1280, height: 800 } });
  page = await dark.newPage();
  await page.goto(url); await load(page);
  await page.evaluate(() => Find.open(false));
  await page.fill('#findText', '병합');
  await page.press('#findText', 'Enter');
  await page.screenshot({ path: path.join(out, 'v4-dark.png') });
  // 5) 모바일
  const mob = await browser.newContext({ viewport: { width: 390, height: 800 }, deviceScaleFactor: 1 });
  page = await mob.newPage();
  await page.goto(url); await load(page);
  await page.screenshot({ path: path.join(out, 'v5-mobile.png') });
  const ov = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth || document.body.scrollWidth > innerWidth);
  console.log('mobile horizontal overflow:', ov);
  // 6) 인쇄 미리보기
  page = await browser.newPage({ viewport: { width: 1000, height: 1300 } });
  await page.goto(url); await load(page);
  await page.emulateMedia({ media: 'print' });
  await page.screenshot({ path: path.join(out, 'v6-print.png') });
  await page.pdf({ path: path.join(out, 'v6-print.pdf'), format: 'A4' });
  await browser.close();
})();
