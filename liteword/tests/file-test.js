// 사용법: node file-test.js <작업 폴더> <LiteWord가 저장한 .doc>
// 텍스트·HTML .doc 열기, 파일 시스템 API(가짜)로 저장/덮어쓰기 흐름 테스트
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const assert = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok  :', m); };
(async () => {
  const dir = process.argv[2];
  const docPath = process.argv[3];
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'euckr.txt'), Buffer.from('c3b920c1d9204555432d4b5220c5d8bdbac6ae0d0a0d0abcc2c2b009c5c70d0a', 'hex'));
  fs.writeFileSync(path.join(dir, 'utf8.txt'), Buffer.from('\ufeffUTF-8 BOM 한글\nline2\n', 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('file://' + path.resolve(__dirname, '../LiteWord.html'));
  const load = (file) => page.evaluate(async ([b64, name]) => { await FileOps.load(new File([base64ToBytes(b64)], name), null); return App.editor.innerText; },
    [fs.readFileSync(file).toString('base64'), path.basename(file)]);
  let t = await load(path.join(dir, 'euckr.txt'));
  assert(t.includes('첫 줄 EUC-KR 텍스트') && t.includes('셋째\t탭'), 'EUC-KR text decoded: ' + JSON.stringify(t));
  t = await load(path.join(dir, 'utf8.txt'));
  assert(t.startsWith('UTF-8 BOM 한글'), 'UTF-8 BOM text: ' + JSON.stringify(t));

  // 우리가 만든 .doc(HTML) 다시 열기
  t = await load(docPath);
  const h = await page.evaluate(() => App.editor.innerHTML);
  assert(t.includes('LiteWord 테스트 문서') && /<h1/.test(h) && /<ul/.test(h) && /<table/.test(h) && /page-break/.test(h) && /<img/.test(h),
    'reopen own .doc keeps heading/list/table/page break/image');
  assert(/탭 뒤 {2}두 칸 공백/.test(t) && /\t/.test(t), 'reopen own .doc keeps tab and double spaces');

  // 가짜 파일 시스템 API로 저장 → 덮어쓰기
  const res = await page.evaluate(async () => {
    const writes = [];
    const mkHandle = (name) => ({ name, async createWritable() { const parts = []; return { async write(b) { parts.push(b); }, async close() { writes.push({ name, size: parts.reduce((a, b) => a + b.size, 0) }); } }; } });
    window.showSaveFilePicker = async (opt) => mkHandle(opt.suggestedName.replace('.docx', '.doc'));
    createEditor(); setFile(null, '제목 없음', null, false);
    App.editor.innerHTML = '<div>파일 저장</div>'; markDirty();
    await FileOps.save();               // 처음: 다른 이름으로 저장 (사용자가 .doc 선택한 것으로 흉내)
    const first = { title: document.title, fmt: App.fileFormat };
    App.editor.innerHTML = '<div>파일 저장 수정</div>'; markDirty();
    window.showSaveFilePicker = async () => { throw new Error('should not ask again'); };
    await FileOps.save();               // 두 번째: 같은 파일에 덮어쓰기
    return { writes, first, second: { title: document.title, dirty: App.dirty } };
  });
  assert(res.writes.length === 2 && res.writes.every((w) => w.name === '제목 없음.doc' && w.size > 100), 'saved twice to same handle ' + JSON.stringify(res.writes));
  assert(res.first.fmt === 'doc' && !res.second.dirty, 'format from chosen extension, clean after save ' + JSON.stringify(res));
  console.log('errors:', JSON.stringify(errors));
  await browser.close();
})();
