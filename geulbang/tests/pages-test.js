// 쪽 나누기(A4) 테스트: 줄이 쪽 경계(여백·쪽 사이)에 놓이지 않는지, 쪽 나누기·긴 표·쪽 수 확인
// 사용법: node pages-test.js [스크린샷 폴더]
const { chromium } = require('playwright');
const path = require('path');
const assert = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok  :', m); };
(async () => {
  const shots = process.argv[2];
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + path.resolve(__dirname, '../geulbang.html'));

  // 문단 길이가 다양한 긴 문서 + 쪽 나누기 + 한 쪽보다 긴 표 + 짧은 표
  await page.evaluate(() => {
    let html = '<h1>쪽 나누기 시험</h1>';
    for (let i = 1; i <= 40; i++) html += `<div>${i}번 문단. ` + '가나다라마바사 아자차카 타파하 English words here. '.repeat(1 + (i % 7)) + '</div>';
    html += '<div class="page-break" contenteditable="false"></div><div id="afterBreak">쪽 나누기 뒤 첫 문단</div>';
    let rows = '';
    for (let r = 1; r <= 45; r++) rows += `<tr><td><div>행 ${r}</div></td><td><div>${'내용 '.repeat(r % 3 ? 3 : 25)}</div></td></tr>`;
    html += `<div>긴 표 앞</div><table id="tall"><tbody>${rows}</tbody></table><div id="afterTall">긴 표 뒤 문단</div>`;
    html += '<table id="short"><tbody>' + '<tr><td><div>짧은 표</div></td><td><div>칸</div></td></tr>'.repeat(6) + '</tbody></table>';
    for (let i = 1; i <= 15; i++) html += `<div>끝 문단 ${i} ` + '마지막 부분 내용 '.repeat(8) + '</div>';
    createEditor(new DOMParser().parseFromString(html, 'text/html').body.childNodes.length ? (() => { const t = document.createElement('template'); t.innerHTML = html; return t.content; })() : null);
  });
  await page.waitForTimeout(300);
  const g = await page.evaluate(() => {
    const MM = 96 / 25.4, PAGE_H = 297 * MM, M = 96, GAP = 24, BODY = PAGE_H - 2 * M, PITCH = PAGE_H + GAP;
    const z = 1;
    const o = document.getElementById('pgFloats').getBoundingClientRect().top;
    const dead = (a, b) => { for (let k = 0; k < 200; k++) { const s = k * PITCH + BODY, e = (k + 1) * PITCH; if (b > s + 0.5 && a < e - 0.5) return k; } return -1; };
    const bad = [];
    // 모든 글줄 검사 (표 안은 표 처리 결과로 따로 검사)
    const w = document.createTreeWalker(App.editor, NodeFilter.SHOW_TEXT);
    let lines = 0;
    while (w.nextNode()) {
      const r = document.createRange(); r.selectNodeContents(w.currentNode);
      for (const q of r.getClientRects()) {
        lines++;
        const k = dead(q.top - o, q.bottom - o);
        if (k >= 0) bad.push(w.currentNode.data.slice(0, 12) + '@' + Math.round(q.top - o));
      }
    }
    const ab = document.getElementById('afterBreak').getBoundingClientRect().top - o;
    const tall = document.getElementById('tall').getBoundingClientRect();
    const afterTall = document.getElementById('afterTall').getBoundingClientRect().top - o;
    const shortT = document.getElementById('short').getBoundingClientRect();
    return {
      lines, bad, pages: Pages.count, sheets: document.querySelectorAll('.sheet').length,
      afterBreakOffset: ((ab % PITCH) + PITCH) % PITCH, tallTop: tall.top - o, tallBottom: tall.bottom - o, afterTall,
      shortTop: shortT.top - o, shortBottom: shortT.bottom - o, shortDead: dead(shortT.top - o, shortT.bottom - o), PITCH, BODY,
      pagesH: document.getElementById('pages').offsetHeight, expectH: Pages.count * PAGE_H + (Pages.count - 1) * GAP,
      status: document.getElementById('stPages').textContent,
    };
  });
  assert(g.bad.length === 0, `no text line in page gaps (${g.lines} line boxes checked) ${g.bad.slice(0, 5)}`);
  const off = Math.min(g.afterBreakOffset, g.PITCH - g.afterBreakOffset);
  assert(off < 1, 'content after page break starts at top of next page (off by ' + off.toFixed(2) + 'px)');
  assert(g.tallTop < g.afterTall && g.tallBottom <= g.afterTall + 1, `tall table stays in document order (${Math.round(g.tallTop)}..${Math.round(g.tallBottom)}, next ${Math.round(g.afterTall)})`);
  assert(g.shortDead === -1, 'short table not split by a page gap');
  assert(g.sheets === g.pages && g.pages >= 5 && Math.abs(g.pagesH - g.expectH) < 1, `pages ${g.pages}, sheets ${g.sheets}, height ok`);
  assert(/^쪽 \d+ \/ \d+$/.test(g.status), 'status shows page: ' + g.status);
  // 긴 표의 행이 쪽 경계에 걸리지 않는지 (행 내용 기준)
  const rowsBad = await page.evaluate(() => {
    const MM = 96 / 25.4, PAGE_H = 297 * MM, M = 96, BODY = PAGE_H - 2 * M, PITCH = PAGE_H + 24;
    const o = document.getElementById('pgFloats').getBoundingClientRect().top;
    const out = [];
    for (const tr of document.getElementById('tall').rows) {
      const c = tr.cells[1].firstElementChild.getBoundingClientRect();
      const a = c.top - o, b = c.bottom - o;
      for (let k = 0; k < 100; k++) { const s = k * PITCH + BODY, e = (k + 1) * PITCH; if (b > s + 0.5 && a < e - 0.5) out.push(tr.cells[0].textContent); }
    }
    return out;
  });
  assert(rowsBad.length === 0, 'tall table rows do not cross page gaps ' + rowsBad);

  // 입력하면서 쪽이 늘어나는지 + 커서가 화면에 보이는지
  const before = await page.evaluate(() => Pages.count);
  await page.evaluate(() => { const last = App.editor.lastElementChild; placeCaret(last, true); });
  for (let i = 0; i < 40; i++) await page.keyboard.press('Enter');
  await page.keyboard.type('새 줄 끝');
  const after = await page.evaluate(() => {
    const r = getSelection().getRangeAt(0).getClientRects()[0];
    const ws = document.getElementById('workspace').getBoundingClientRect();
    return { n: Pages.count, visible: r && r.top >= ws.top && r.bottom <= ws.bottom, status: document.getElementById('stPages').textContent };
  });
  assert(after.n === before + 1, `typing grows pages ${before} -> ${after.n}`);
  assert(after.visible, 'caret stays visible after moving to a new page');
  assert(after.status === `쪽 ${after.n} / ${after.n}`, 'status follows caret: ' + after.status);
  // '새 줄 끝'(5글자) + 줄바꿈 40개 지우기
  for (let i = 0; i < 45; i++) await page.keyboard.press('Backspace');
  const shrunk = await page.evaluate(() => Pages.count);
  assert(shrunk === before, `deleting shrinks pages back (${shrunk})`);

  // 확대/축소해도 쪽 수 동일
  await page.evaluate(() => setZoom(0.7));
  await page.waitForTimeout(100);
  const zoomed = await page.evaluate(() => { Pages.paginate(); return Pages.count; });
  assert(zoomed === before, 'same page count at 70% zoom');
  await page.evaluate(() => setZoom(1));

  if (shots) {
    for (const [name, y] of [['pg-top', 0], ['pg-break', 0.25], ['pg-table', 0.45]]) {
      await page.evaluate((f) => { const w = document.getElementById('workspace'); w.scrollTop = (w.scrollHeight - w.clientHeight) * f + (f ? 300 : 0); }, y);
      await page.screenshot({ path: path.join(shots, name + '.png') });
    }
    await page.evaluate(() => setZoom(0.5));
    await page.evaluate(() => { document.getElementById('workspace').scrollTop = 0; });
    await page.screenshot({ path: path.join(shots, 'pg-zoom50.png') });
  }
  // 인쇄(PDF)에서도 화면과 같은 쪽 수
  await page.evaluate(() => setZoom(1));
  await page.emulateMedia({ media: 'print' });
  const pdf = await page.pdf({ format: 'A4', preferCSSPageSize: true });
  if (shots) require('fs').writeFileSync(path.join(shots, 'print.pdf'), pdf);
  const pdfPages = (pdf.toString('latin1').match(/\/Type\s*\/Page[^s]/g) || []).length;
  const screenPages = await page.evaluate(() => Pages.count);
  assert(pdfPages === screenPages, `print pages ${pdfPages} vs screen ${screenPages}`);
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) process.exitCode = 1;
  await browser.close();
})();
