// UI 동작 테스트: 표 편집/실행 취소, 찾기·바꾸기, 붙여넣기 정리, 다운로드 저장, 임시 저장 복구, 한글 조합 입력
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const assert = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok  :', m); };
(async () => {
  const out = process.argv[2];
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ acceptDownloads: true, viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const url = 'file://' + path.resolve(__dirname, '../geulbang.html');
  await page.goto(url);
  const k = page.keyboard;
  const html = () => page.evaluate(() => App.editor.innerHTML);
  await page.locator('#editor').click();

  // 1) 표 삽입과 행/열 추가, 실행 취소
  await page.evaluate(() => insertTable(2, 2));
  await k.type('a');
  await page.evaluate(() => tableCommand('rowBelow'));
  let rows = await page.evaluate(() => App.editor.querySelector('table').rows.length);
  assert(rows === 3, 'rowBelow adds a row (' + rows + ')');
  await page.evaluate(() => tableCommand('colRight'));
  let cols = await page.evaluate(() => App.editor.querySelector('table').rows[0].cells.length);
  assert(cols === 3, 'colRight adds a column (' + cols + ')');
  await k.type('X');
  const xCell = await page.evaluate(() => { const t = App.editor.querySelector('table'); return [...t.querySelectorAll('td')].findIndex((c) => c.textContent === 'X'); });
  assert(xCell === 4, 'caret placed in new column cell of new row (index ' + xCell + ')');
  await page.evaluate(() => exec('undo'));
  await page.evaluate(() => exec('undo'));
  cols = await page.evaluate(() => App.editor.querySelector('table').rows[0].cells.length);
  assert(cols === 2, 'undo reverts column insert (' + cols + ')');
  await page.evaluate(() => { placeCaret(App.editor.querySelector('td')); tableCommand('delRow'); });
  rows = await page.evaluate(() => App.editor.querySelector('table').rows.length);
  assert(rows === 2, 'delRow removes a row (' + rows + ')');
  await page.evaluate(() => { placeCaret(App.editor.querySelector('td')); tableCommand('delTable'); });
  assert(!(await html()).includes('<table'), 'delTable removes the table');

  // 2) 찾기·바꾸기
  await page.evaluate(() => { App.editor.innerHTML = '<div>사과 apple 사과</div><div>Apple 파이와 사과</div>'; });
  await page.evaluate(() => Find.open(true));
  await page.fill('#findText', '사과');
  await page.fill('#replaceText', '배');
  await page.click('#replaceAll');
  const t1 = await page.evaluate(() => App.editor.textContent);
  assert(t1 === '배 apple 배Apple 파이와 배', 'replace all Korean (' + t1 + ')');
  await page.fill('#findText', 'apple');
  await page.fill('#replaceText', 'PEAR');
  await page.click('#findNext');
  const info = await page.textContent('#findInfo');
  assert(info === '1 / 2', 'case-insensitive find count (' + info + ')');
  await page.click('#replaceOne');
  await page.click('#replaceOne');
  const t2 = await page.evaluate(() => App.editor.textContent);
  assert(t2 === '배 PEAR 배PEAR 파이와 배', 'replace one by one (' + t2 + ')');
  await k.press('Escape');
  assert(await page.isHidden('#findBar'), 'Esc closes find bar');

  // 3) 붙여넣기 정리 (Word식 HTML)
  await page.evaluate(() => { App.editor.innerHTML = '<div><br></div>'; placeCaret(App.editor.firstChild); });
  await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.setData('text/html', '<html><head><style>p{color:red}</style></head><body><!--StartFragment--><p class=MsoNormal style="margin:0;mso-line-height:1"><b>굵은</b> <span style="color:#0000ff;font-size:14pt;mso-bidi-font-size:10pt">파랑</span><o:p></o:p></p><script>alert(1)</script><p onclick="x()">둘째 <img src="x" onerror="alert(1)">줄</p><table border=1><tr><td>c1</td><td>c2</td></tr></table><!--EndFragment--></body></html>');
    dt.setData('text/plain', '굵은 파랑');
    App.editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
  });
  const ph = await html();
  assert(!/script|onclick|onerror|class=|mso-|<img/i.test(ph), 'paste removes scripts, handlers, classes, mso styles, broken images');
  assert(/<b>굵은<\/b>/.test(ph) && /color: ?rgb\(0, 0, 255\)|color:#0000ff/i.test(ph) && /<table/.test(ph), 'paste keeps bold, color and table');
  console.log('     pasted:', ph);

  // 4) 일반 텍스트 여러 줄 붙여넣기
  await page.evaluate(() => { App.editor.innerHTML = '<div><br></div>'; placeCaret(App.editor.firstChild); });
  await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.setData('text/plain', '첫 줄\r\n둘째 줄\r\n\r\n넷째 줄');
    App.editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
  });
  const pt = await page.evaluate(() => App.editor.innerText);
  assert(pt.split('\n').filter(Boolean).length === 3 && /넷째 줄/.test(pt), 'plain multi-line paste creates paragraphs');

  // 5) 저장: 파일 시스템 API가 없을 때 다운로드로 저장
  await page.evaluate(() => { delete window.showSaveFilePicker; window.showSaveFilePicker = undefined; App.editor.innerHTML = '<div>저장 테스트 Save test</div>'; markDirty(); });
  const dl = page.waitForEvent('download');
  await page.evaluate(() => { FileOps.save(); });
  await page.fill('#f_name', '테스트 문서');
  await page.click('#dialog button[type=submit]');
  const d = await dl;
  const target = path.join(out, d.suggestedFilename());
  await d.saveAs(target);
  assert(d.suggestedFilename() === '테스트 문서.docx', 'download name ' + d.suggestedFilename());
  assert(fs.statSync(target).size > 1000, 'downloaded docx has content');
  const title = await page.evaluate(() => document.title);
  assert(title === '테스트 문서.docx - 현서의 글방', 'title updated and not dirty (' + title + ')');

  // 6) 임시 저장 복구
  await page.evaluate(() => { App.editor.innerHTML = '<div>복구할 내용\t탭  두칸</div>'; markDirty(); saveDraft(); });
  const page2 = await ctx.newPage();
  page2.on('pageerror', (e) => errors.push(e.message));
  await page2.goto(url);
  await page2.waitForSelector('#overlay:not([hidden])');
  const msg = await page2.textContent('#dialog p');
  assert(/복구/.test(msg), 'recovery prompt shown');
  await page2.click('#dialog .btn.primary');
  const rec = await page2.evaluate(() => App.editor.textContent);
  assert(rec === '복구할 내용\t탭  두칸', 'draft restored with whitespace (' + JSON.stringify(rec) + ')');
  await page2.close();

  // 7) 한글 조합 입력 (IME 흉내)
  await page.evaluate(() => { App.editor.innerHTML = '<div><br></div>'; placeCaret(App.editor.firstChild); });
  const cdp = await ctx.newCDPSession(page);
  for (const [comp, commit] of [['ㅎ', null], ['하', null], ['한', null], ['한ㄱ', null], [null, '한'], ['ㄱ', null], ['그', null], ['글', null], [null, '글']]) {
    if (comp) await cdp.send('Input.imeSetComposition', { text: comp, selectionStart: comp.length, selectionEnd: comp.length });
    else await cdp.send('Input.insertText', { text: commit });
  }
  await k.press('Enter');
  await cdp.send('Input.insertText', { text: '다음 줄' });
  const ime = await page.evaluate(() => App.editor.innerText);
  assert(/한글/.test(ime) && /다음 줄/.test(ime), 'IME composition text committed (' + JSON.stringify(ime) + ')');

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) process.exitCode = 1;
  await browser.close();
})();
